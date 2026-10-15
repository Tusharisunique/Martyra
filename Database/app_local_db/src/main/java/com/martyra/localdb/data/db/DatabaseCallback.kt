package com.martyra.localdb.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase

/**
 * Room database lifecycle callback.
 *
 * Responsibilities:
 * 1. Install custom SQLite triggers that Room cannot express through annotations.
 * 2. Enable WAL journal mode and foreign-key enforcement on every database open.
 *
 * Why triggers are installed here (not in a migration):
 * Room 2.x does not have an annotation for arbitrary triggers. Installing in onCreate ensures
 * triggers exist from the very first use. Migrations must reinstall triggers manually because
 * SQLite DROP TABLE in a migration also removes its triggers.
 *
 * Schema version compatibility: Trigger names are suffixed with _v1 so they can be
 * individually replaced in future migrations without ambiguity.
 */
class DatabaseCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        installTriggers(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        // These PRAGMAs must be re-applied on every open; they are not persisted.
        db.execSQL("PRAGMA foreign_keys = ON")
        // Journal mode is set by Room.JournalMode.WRITE_AHEAD_LOGGING, but we repeat it for safety
        // when opening with a custom SupportFactory (SQLCipher may reset pragmas).
        db.execSQL("PRAGMA journal_mode = WAL")
    }

    private fun installTriggers(db: SupportSQLiteDatabase) {
        // ── Custody events: append-only ──────────────────────────────────────

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS prevent_custody_update_v1
            BEFORE UPDATE ON custody_events
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'custody_events is append-only');
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS prevent_custody_delete_v1
            BEFORE DELETE ON custody_events
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'custody_events is append-only');
            END
        """.trimIndent())

        // ── Evidence: immutable fields ────────────────────────────────────────
        //
        // SQLite IS / IS NOT operators perform null-safe comparison:
        //   NULL IS NOT NULL  → true  (would fire if an immutable field changed to NULL)
        //   'x'  IS NOT 'x'  → false (no change, trigger does not fire)
        //
        // This protects against:
        //   - Overwriting a value with a different value
        //   - Setting a non-NULL field to NULL
        //   - Populating a NULL field with a non-NULL value
        //
        // Mutable fields NOT covered by this trigger: status, server_tx_id, merkle_proof.

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS enforce_evidence_immutable_v1
            BEFORE UPDATE ON evidence
            FOR EACH ROW
            WHEN
                NEW.file_hash         IS NOT OLD.file_hash         OR
                NEW.metadata_hash     IS NOT OLD.metadata_hash     OR
                NEW.gps_lat           IS NOT OLD.gps_lat           OR
                NEW.gps_lon           IS NOT OLD.gps_lon           OR
                NEW.captured_at       IS NOT OLD.captured_at       OR
                NEW.device_id         IS NOT OLD.device_id         OR
                NEW.sandbox_signature IS NOT OLD.sandbox_signature OR
                NEW.file_path         IS NOT OLD.file_path
            BEGIN
                SELECT RAISE(ABORT, 'Attempt to modify an immutable evidence field');
            END
        """.trimIndent())

        // ── Sync queue: priority contract ─────────────────────────────────────
        //
        // HASH_ONLY must always have priority 1; FILE_UPLOAD must always have priority 2.
        // This trigger enforces the contract at the database level, complementing
        // the application-layer validation in EvidenceRepository.

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS enforce_sync_priority_v1
            BEFORE INSERT ON sync_queue
            FOR EACH ROW
            WHEN NOT (
                (NEW.job_type = 'HASH_ONLY'   AND NEW.priority = 1) OR
                (NEW.job_type = 'FILE_UPLOAD' AND NEW.priority = 2)
            )
            BEGIN
                SELECT RAISE(ABORT, 'HASH_ONLY must have priority 1; FILE_UPLOAD must have priority 2');
            END
        """.trimIndent())
    }

    companion object {
        /**
         * Must be called from a new Room migration block that rebuilds the triggers
         * after any schema change that drops and recreates the affected tables.
         * Example usage:
         *   val MIGRATION_1_2 = object : Migration(1, 2) {
         *       override fun migrate(db: SupportSQLiteDatabase) {
         *           // ... table changes ...
         *           DatabaseCallback.reinstallTriggers(db)
         *       }
         *   }
         */
        fun reinstallTriggers(db: SupportSQLiteDatabase) {
            DatabaseCallback().installTriggers(db)
        }
    }
}
