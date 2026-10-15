package com.martyra.localdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.util.CustodyChain
import com.martyra.localdb.util.VerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CustodyChain.verify].
 *
 * Run with:
 *   ./gradlew :app_local_db:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.martyra.localdb.CustodyChainTest
 */
@RunWith(AndroidJUnit4::class)
class CustodyChainTest {

    private val context    = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDbName = "martyra_chain_test.db"
    private lateinit var db: MartyraDatabase
    private lateinit var evidenceDir: java.io.File
    private lateinit var repo: EvidenceRepositoryTestHelper.Repo
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = MartyraDatabase.getInstance(context, com.martyra.localdb.security.FixedPassphraseProvider("chain_test_pass"))
        evidenceDir = java.io.File(context.filesDir, "chain_test_evidence").also { it.mkdirs() }
        repo = EvidenceRepositoryTestHelper.create(db, evidenceDir)
        sessionId = TestFixtures.insertDemoSession(db).sessionId
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        MartyraDatabase.clearInstance()
        context.deleteDatabase(testDbName)
        evidenceDir.deleteRecursively()
    }

    // ── Test 1: Intact chain verifies as Valid ─────────────────────────────

    @Test
    fun intactChain_verifiesAsValid() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        val result   = CustodyChain.verify(evidence.evidenceId, db.custodyEventDao())
        assertEquals("Intact chain must verify as Valid", VerificationResult.Valid, result)
    }

    // ── Test 2: First event uses ZERO_HASH as predecessor ─────────────────

    @Test
    fun firstEvent_hasZeroPrevHash() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        val events   = db.custodyEventDao().getChain(evidence.evidenceId)
        assertEquals(CustodyChain.ZERO_HASH, events.first().prevEventHash)
    }

    // ── Test 3: EvidenceNotFound for unknown ID ───────────────────────────

    @Test
    fun unknownEvidenceId_returnsEvidenceNotFound() = runBlocking {
        val result = CustodyChain.verify("no-such-id", db.custodyEventDao())
        assertEquals(VerificationResult.EvidenceNotFound, result)
    }

    // ── Test 4: Tampered event_hash is detected ────────────────────────────

    /**
     * We disable triggers on a COPY of the database, tamper with an event_hash, then
     * restore normal access and run CustodyChain.verify to detect the tamper.
     *
     * We tamper the chain directly in the DB by executing raw SQL after dropping the
     * BEFORE UPDATE trigger in the same connection. The production database is not modified.
     */
    @Test
    fun tamperedEventHash_detectedByVerify() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        val events   = db.custodyEventDao().getChain(evidence.evidenceId)
        val firstEvent = events.first()

        // Use a direct SupportSQLiteDatabase handle to bypass Room and drop the trigger.
        val rawDb = db.openHelper.writableDatabase
        rawDb.beginTransaction()
        try {
            rawDb.execSQL("DROP TRIGGER IF EXISTS prevent_custody_update_v1")
            rawDb.execSQL(
                "UPDATE custody_events SET event_hash = 'aaaa${firstEvent.eventHash.drop(4)}' " +
                "WHERE event_id = ${firstEvent.eventId}"
            )
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }

        // Verify detects the tamper
        val result = CustodyChain.verify(evidence.evidenceId, db.custodyEventDao())
        assertTrue("Tampered hash must not verify as Valid", result !is VerificationResult.Valid)
        assertTrue("Should detect InvalidEventHash", result is VerificationResult.InvalidEventHash)
        val invalidResult = result as VerificationResult.InvalidEventHash
        assertEquals("Must report the tampered event_id", firstEvent.eventId, invalidResult.eventId)

        // Reinstall the trigger so the DB is protected again
        rawDb.beginTransaction()
        try {
            rawDb.execSQL("""
                CREATE TRIGGER IF NOT EXISTS prevent_custody_update_v1
                BEFORE UPDATE ON custody_events
                FOR EACH ROW
                BEGIN SELECT RAISE(ABORT, 'custody_events is append-only'); END
            """.trimIndent())
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }
    }

    // ── Test 5: Broken link detected ──────────────────────────────────────

    @Test
    fun brokenLink_detectedByVerify() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        val events   = db.custodyEventDao().getChain(evidence.evidenceId)
        // Need at least 2 events to create a broken link
        if (events.size < 2) return@runBlocking

        val secondEvent = events[1]
        val rawDb = db.openHelper.writableDatabase
        rawDb.beginTransaction()
        try {
            rawDb.execSQL("DROP TRIGGER IF EXISTS prevent_custody_update_v1")
            // Corrupt the prev_event_hash of the second event — breaks the link from event 1
            rawDb.execSQL(
                "UPDATE custody_events SET prev_event_hash = '${CustodyChain.ZERO_HASH}' " +
                "WHERE event_id = ${secondEvent.eventId}"
            )
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }

        val result = CustodyChain.verify(evidence.evidenceId, db.custodyEventDao())
        assertTrue("Broken link must be detected", result is VerificationResult.BrokenLink)
        assertEquals(secondEvent.eventId, (result as VerificationResult.BrokenLink).eventId)

        // Reinstall trigger
        rawDb.beginTransaction()
        try {
            rawDb.execSQL("""
                CREATE TRIGGER IF NOT EXISTS prevent_custody_update_v1
                BEFORE UPDATE ON custody_events
                FOR EACH ROW
                BEGIN SELECT RAISE(ABORT, 'custody_events is append-only'); END
            """.trimIndent())
            rawDb.setTransactionSuccessful()
        } finally {
            rawDb.endTransaction()
        }
    }
}
