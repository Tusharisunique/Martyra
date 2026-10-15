package com.martyra.localdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.security.FixedPassphraseProvider
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteException
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented security tests requiring an actual device or emulator.
 *
 * Run with:
 *   ./gradlew :app_local_db:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.martyra.localdb.DatabaseSecurityTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSecurityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPassphrase = "test_passphrase_for_security_tests_only"
    private val testDbName = "martyra_security_test.db"
    private lateinit var db: MartyraDatabase
    private lateinit var session: com.martyra.localdb.data.entity.CaptureSessionEntity

    @Before
    fun setUp() {
        // Clear any leftover from previous test runs
        context.deleteDatabase(testDbName)
        db = buildTestDb(testPassphrase, testDbName)
        session = TestFixtures.insertDemoSession(db)
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        MartyraDatabase.clearInstance()
        context.deleteDatabase(testDbName)
    }

    // ── Test 1: Wrong passphrase fails ─────────────────────────────────────

    /**
     * Opening an encrypted database with an incorrect passphrase must fail with a
     * decryption-related exception. This test opens the same DB file with a different
     * passphrase and expects an error when any query is issued.
     */
    @Test
    fun wrongPassphraseFails() {
        // 1. Insert data and close the correctly-keyed database.
        db.close()
        MartyraDatabase.clearInstance()

        // 2. Reopen with the wrong passphrase.
        val wrongDb = buildTestDb("WRONG_PASSPHRASE_DELIBERATE", testDbName)
        try {
            runBlocking {
                // Any query on the wrong-passphrase DB should throw.
                wrongDb.captureSessionDao().getAll()
            }
            fail("Expected an exception when opening DB with wrong passphrase, but no exception was thrown")
        } catch (e: Exception) {
            // Expected — SQLiteException or a wrapper from Room/SQLCipher.
            // We verify the exception is decryption-related by inspecting its chain.
            val isSqliteError = e is SQLiteException ||
                e.cause is SQLiteException ||
                e.javaClass.name.contains("SQLite", ignoreCase = true) ||
                e.message?.contains("file is not a database", ignoreCase = true) == true ||
                e.message?.contains("SQLite", ignoreCase = true) == true
            assertTrue(
                "Exception should be SQLite/decryption-related, but was: ${e.javaClass.name}: ${e.message}",
                isSqliteError
            )
        } finally {
            if (wrongDb.isOpen) wrongDb.close()
            MartyraDatabase.clearInstance()
        }
    }

    // ── Test 2: Encrypted database contains no plaintext evidence hashes ──

    /**
     * After inserting a known evidence record with a known file_hash, the raw database
     * bytes (and WAL file if present) must not contain the plaintext of that hash.
     *
     * WAL mode note: After the database is closed, SQLCipher checkpoints the WAL file
     * back to the main database. We close the database before inspecting the bytes to
     * ensure the WAL has been checkpointed.
     */
    @Test
    fun encryptedDatabaseContainsNoPlaintextHashes() = runBlocking {
        // 1. Insert one evidence record.
        val evidenceDir = File(context.filesDir, "security_test_evidence").also { it.mkdirs() }
        val repo = EvidenceRepositoryTestHelper.create(db, evidenceDir)
        val result = TestFixtures.insertOneEvidence(db, repo, session.sessionId, evidenceDir)
        val insertedEvidence = result.dataOrThrow()
        val knownHash = insertedEvidence.fileHash

        // 2. Close the database to checkpoint the WAL file.
        db.close()
        MartyraDatabase.clearInstance()

        // 3. Locate the database file.
        val dbFile = context.getDatabasePath(testDbName)
        assertTrue("Database file must exist after close: ${dbFile.path}", dbFile.exists())

        // 4. Read the raw bytes of the database file (and WAL if still present).
        val dbBytes = dbFile.readBytes()
        val walFile = File(dbFile.path + "-wal")
        val walBytes = if (walFile.exists()) walFile.readBytes() else ByteArray(0)
        val allBytes = dbBytes + walBytes

        // 5. Search for the known hash string in the raw bytes.
        val hashBytes = knownHash.toByteArray(Charsets.UTF_8)
        val containsHash = allBytes.containsSubsequence(hashBytes)
        assertTrue(
            "Encrypted database must NOT contain the plaintext file_hash '$knownHash'. " +
            "Found it in raw bytes — encryption is not working!",
            !containsHash
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun buildTestDb(passphrase: String, dbName: String): MartyraDatabase =
        MartyraDatabase.getInstance(context, FixedPassphraseProvider(passphrase))

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (i in 0..this.size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
