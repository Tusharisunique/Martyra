package com.martyra.localdb.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.martyra.localdb.data.dao.CaptureSessionDao
import com.martyra.localdb.data.dao.CustodyEventDao
import com.martyra.localdb.data.dao.EvidenceDao
import com.martyra.localdb.data.dao.SyncQueueDao
import com.martyra.localdb.data.entity.CaptureSessionEntity
import com.martyra.localdb.data.entity.CustodyEventEntity
import com.martyra.localdb.data.entity.EvidenceEntity
import com.martyra.localdb.data.entity.SyncQueueEntity
import com.martyra.localdb.security.DatabasePassphraseProvider
import net.sqlcipher.database.SupportFactory

/**
 * The Room database for MARTYRA's local evidence store.
 *
 * ── Encryption ────────────────────────────────────────────────────────────
 * The database is encrypted with SQLCipher (AES-256-CBC) via [SupportFactory].
 * The passphrase is supplied by [DatabasePassphraseProvider]; the production implementation
 * wraps it with Android Keystore AES-GCM so the plaintext passphrase never touches disk.
 *
 * The passphrase ByteArray is zeroed in a finally block immediately after the Room builder
 * consumes it. SQLCipher derives the actual database key from the passphrase internally;
 * this module does not retain the plaintext passphrase in any long-lived object.
 *
 * ── WAL mode ──────────────────────────────────────────────────────────────
 * Write-Ahead Logging is enabled both via [JournalMode.WRITE_AHEAD_LOGGING] and via a
 * PRAGMA in [DatabaseCallback.onOpen]. WAL allows concurrent readers without blocking writers
 * and provides better crash safety than the default rollback journal.
 *
 * OS concept: WAL is analogous to a redo log — writes are first appended to a separate
 * log file (the WAL) and later checkpointed to the main database file. A crash between
 * the write and the checkpoint is safe because the WAL can be replayed or discarded.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────
 * [getInstance] is protected by a double-checked locking pattern (@Volatile + synchronized).
 * Room itself serialises all write operations; reads can be concurrent in WAL mode.
 *
 * ── Schema export ─────────────────────────────────────────────────────────
 * Room exports a JSON schema file to app_local_db/schemas/ (configured via KSP args in
 * build.gradle.kts). These files must be committed to version control and are used by
 * Room's migration testing utilities.
 */
@Database(
    entities = [
        CaptureSessionEntity::class,
        EvidenceEntity::class,
        CustodyEventEntity::class,
        SyncQueueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class MartyraDatabase : RoomDatabase() {

    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun custodyEventDao(): CustodyEventDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        private const val DB_NAME = "martyra_evidence.db"

        @Volatile
        private var INSTANCE: MartyraDatabase? = null

        /**
         * Return the singleton database instance, creating it on first call.
         *
         * @param context Application context (not Activity context).
         * @param passphraseProvider Supplies the SQLCipher passphrase. Use
         *   [KeystorePassphraseProvider] in production and [FixedPassphraseProvider] in tests.
         */
        fun getInstance(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider,
        ): MartyraDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphraseProvider).also { INSTANCE = it }
            }

        /**
         * Destroy the singleton instance (useful in tests to reset state between tests).
         * Must only be called after the database has been closed.
         */
        @Synchronized
        fun clearInstance() {
            INSTANCE = null
        }

        private fun buildDatabase(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider,
        ): MartyraDatabase {
            // Retrieve the passphrase and zero it as soon as the factory has consumed it.
            // The passphrase is only needed at database open time; retaining it in a field
            // would unnecessarily extend its lifetime in heap memory.
            val passphrase = passphraseProvider.getPassphrase()
            return try {
                val factory = SupportFactory(passphrase)
                Room.databaseBuilder(
                    context.applicationContext,
                    MartyraDatabase::class.java,
                    DB_NAME,
                )
                    .openHelperFactory(factory)
                    .addCallback(DatabaseCallback())
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
            } finally {
                // Zero the passphrase byte array to reduce its heap lifetime.
                // Note: the JVM may retain copies in GC'd regions; this is a best-effort
                // measure. See DatabaseKeyManager for a full discussion of this limitation.
                passphrase.fill(0)
            }
        }
    }
}
