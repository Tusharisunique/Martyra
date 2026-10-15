package com.martyra.localdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.repo.EvidenceModels.EvidenceStatus
import com.martyra.localdb.util.CustodyChain
import com.martyra.localdb.util.VerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for database tamper detection and missing-file detection.
 *
 * Run with:
 *   ./gradlew :app_local_db:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.martyra.localdb.DatabaseTamperTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTamperTest {

    private val context    = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDbName = "martyra_tamper_test.db"
    private lateinit var db: MartyraDatabase
    private lateinit var evidenceDir: File
    private lateinit var repo: EvidenceRepositoryTestHelper.Repo
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = MartyraDatabase.getInstance(context, com.martyra.localdb.security.FixedPassphraseProvider("tamper_test_pass"))
        evidenceDir = File(context.filesDir, "tamper_test_evidence").also { it.mkdirs() }
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

    // ── Test 1: Delete evidence file → FILE_MISSING + custody event ────────

    @Test
    fun deletedFile_markedFileMissingWithCustodyEvent() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        val file = File(evidence.filePath)
        assertTrue("Evidence file must exist before deletion", file.exists())

        // Delete the file to simulate loss
        file.delete()
        assertTrue("File must be deleted for this test", !file.exists())

        // Run the missing-file scan
        val scanResult = repo.repository.verifyFilesExist()
        assertTrue("Evidence ID should be in missing list", evidence.evidenceId in scanResult.missingIds)

        // Status should be FILE_MISSING
        val updated = db.evidenceDao().getById(evidence.evidenceId)
        assertEquals(EvidenceStatus.FILE_MISSING.name, updated?.status)

        // A FILE_MISSING_AFTER_CAPTURE custody event should exist
        val events = db.custodyEventDao().getChain(evidence.evidenceId)
        val missingEvent = events.find { it.eventType == "FILE_MISSING_AFTER_CAPTURE" }
        assertNotNull("FILE_MISSING_AFTER_CAPTURE event must be appended", missingEvent)
        assertNotNull("Missing event must have details JSON", missingEvent?.details)
    }

    // ── Test 2: Scan does not append duplicate missing events ──────────────

    @Test
    fun missingFileScan_doesNotAppendDuplicateEvents() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        File(evidence.filePath).delete()

        // First scan
        repo.repository.verifyFilesExist()
        val countAfterFirst = db.custodyEventDao().countEvents(evidence.evidenceId)

        // Second scan — already FILE_MISSING, should not re-check
        repo.repository.verifyFilesExist()
        val countAfterSecond = db.custodyEventDao().countEvents(evidence.evidenceId)

        assertEquals(
            "Second scan must not append duplicate FILE_MISSING events",
            countAfterFirst,
            countAfterSecond
        )
    }

    // ── Test 3: Sync-priority trigger rejects wrong priority ──────────────

    @Test
    fun syncQueue_triggerRejectsWrongPriority() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()

        var didThrow = false
        try {
            // Attempt to insert HASH_ONLY with priority 2 (should be 1)
            db.openHelper.writableDatabase.execSQL("""
                INSERT INTO sync_queue (evidence_id, job_type, priority, attempts, next_retry_at, state)
                VALUES ('${evidence.evidenceId}_extra', 'HASH_ONLY', 2, 0, 0, 'PENDING')
            """.trimIndent())
        } catch (e: Exception) {
            didThrow = true
        }
        assertTrue("Priority trigger must reject HASH_ONLY with priority != 1", didThrow)
    }

    // ── Test 4: Chain remains Valid even after FILE_MISSING ───────────────

    @Test
    fun chainVerificationPasses_afterFileMissingEvent() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        File(evidence.filePath).delete()
        repo.repository.verifyFilesExist()

        val result = CustodyChain.verify(evidence.evidenceId, db.custodyEventDao())
        assertEquals("Chain should still be valid after FILE_MISSING event", VerificationResult.Valid, result)
    }
}
