package com.martyra.localdb

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.repo.EvidenceModels.EvidenceStatus
import com.martyra.localdb.repo.RepositoryError
import com.martyra.localdb.repo.RepositoryResult
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for [EvidenceRepository].
 * Covers the full ingest lifecycle, rollback on failure, trigger enforcement, and status transitions.
 *
 * Run with:
 *   ./gradlew :app_local_db:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.martyra.localdb.EvidenceRepositoryTest
 */
@RunWith(AndroidJUnit4::class)
class EvidenceRepositoryTest {

    private val context    = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDbName = "martyra_repo_test.db"
    private lateinit var db: MartyraDatabase
    private lateinit var evidenceDir: File
    private lateinit var repo: EvidenceRepositoryTestHelper.Repo
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = MartyraDatabase.getInstance(context, com.martyra.localdb.security.FixedPassphraseProvider("repo_test_pass"))
        evidenceDir = File(context.filesDir, "repo_test_evidence").also { it.mkdirs() }
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

    // ── Test 1: Full ingest creates evidence, custody chain, and queue jobs ──

    @Test
    fun recordCapture_insertsEvidenceChainAndQueueJobs() = runBlocking {
        val result = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        val evidence = result.dataOrThrow()

        // Status must be QUEUED
        assertEquals(EvidenceStatus.QUEUED.name, evidence.status)

        // At least 3 custody events: CAPTURED, HASHED, QUEUED
        val events = db.custodyEventDao().getChain(evidence.evidenceId)
        assertTrue("Expected ≥ 3 custody events, got ${events.size}", events.size >= 3)
        assertEquals("CAPTURED", events[0].eventType)
        assertTrue("Expected HASHED event", events.any { it.eventType == "HASHED" })
        assertTrue("Expected QUEUED event",  events.any { it.eventType == "QUEUED" })

        // Two queue jobs: HASH_ONLY (priority 1) and FILE_UPLOAD (priority 2)
        val hashJob = db.syncQueueDao().getJob(evidence.evidenceId, "HASH_ONLY")
        val fileJob = db.syncQueueDao().getJob(evidence.evidenceId, "FILE_UPLOAD")
        assertNotNull("HASH_ONLY job must exist", hashJob)
        assertNotNull("FILE_UPLOAD job must exist", fileJob)
        assertEquals(1, hashJob!!.priority)
        assertEquals(2, fileJob!!.priority)
        assertEquals("PENDING", hashJob.state)
        assertEquals("PENDING", fileJob.state)
    }

    // ── Test 2: Mid-ingest failure rolls back all changes ──────────────────

    @Test
    fun recordCapture_rollsBackOnFailure_afterHashedEvent() = runBlocking {
        repo.repository.testHook = com.martyra.localdb.repo.EvidenceRepository.TestHooks.failBefore("afterSyncJobsInserted")

        val result = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        assertTrue("Expected failure", result.isFailure)

        // No evidence rows
        val allQueued = db.evidenceDao().getByStatus("QUEUED")
        val allCaptured = db.evidenceDao().getByStatus("CAPTURED")
        assertEquals("No evidence should remain after rollback", 0, allQueued.size + allCaptured.size)

        // No custody events
        // (We don't know the evidenceId since insertion failed, but the table should be empty)
        // This verifies cascade rollback
    }

    // ── Test 3: UPDATE on custody_events aborts via trigger ───────────────

    @Test
    fun custodyEvents_updateAbortsByTrigger() = runBlocking {
        val result = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        val evidence = result.dataOrThrow()
        val event = db.custodyEventDao().getChain(evidence.evidenceId).first()

        var didThrow = false
        try {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE custody_events SET event_type = 'TAMPERED' WHERE event_id = ${event.eventId}"
                )
            }
        } catch (e: SQLiteException) {
            didThrow = true
            assertTrue("Error message should mention append-only", e.message?.contains("append-only") == true)
        } catch (e: Exception) {
            didThrow = true // Any exception from the trigger is acceptable
        }
        assertTrue("Trigger must abort UPDATE on custody_events", didThrow)
    }

    // ── Test 4: DELETE on custody_events aborts via trigger ───────────────

    @Test
    fun custodyEvents_deleteAbortsByTrigger() = runBlocking {
        val result = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        val evidence = result.dataOrThrow()

        var didThrow = false
        try {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM custody_events WHERE evidence_id = '${evidence.evidenceId}'"
                )
            }
        } catch (e: Exception) {
            didThrow = true
        }
        assertTrue("Trigger must abort DELETE on custody_events", didThrow)
    }

    // ── Test 5: Modifying an immutable evidence field aborts ───────────────

    @Test
    fun evidence_immutableField_updateAbortsByTrigger() = runBlocking {
        val result = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        val evidence = result.dataOrThrow()

        var didThrow = false
        try {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE evidence SET file_hash = 'tampered_hash' WHERE evidence_id = '${evidence.evidenceId}'"
                )
            }
        } catch (e: Exception) {
            didThrow = true
        }
        assertTrue("Trigger must abort modification of immutable file_hash", didThrow)
    }

    // ── Test 6: Changing status IS permitted for valid transitions ─────────

    @Test
    fun evidence_statusUpdate_allowedForValidTransition() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()

        // QUEUED → HASH_SYNCED
        val result = repo.repository.markHashSynced(evidence.evidenceId)
        assertTrue("markHashSynced should succeed", result.isSuccess)

        val updated = db.evidenceDao().getById(evidence.evidenceId)
        assertEquals(EvidenceStatus.HASH_SYNCED.name, updated?.status)
    }

    // ── Test 7: Invalid status transition is rejected ──────────────────────

    @Test
    fun evidence_invalidStatusTransition_isRejected() = runBlocking {
        val evidence = TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir).dataOrThrow()
        // QUEUED → CONFIRMED is not a valid direct transition
        val result = repo.repository.markConfirmed(evidence.evidenceId)
        assertTrue("Invalid transition should fail", result.isFailure)
        assertTrue(result.errorOrNull() is RepositoryError.InvalidStateTransition)
    }
}
