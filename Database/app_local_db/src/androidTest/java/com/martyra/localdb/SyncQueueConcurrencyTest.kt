package com.martyra.localdb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.data.dao.SyncQueueDao
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented concurrency tests for [SyncQueueDao].
 *
 * Run with:
 *   ./gradlew :app_local_db:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.martyra.localdb.SyncQueueConcurrencyTest
 */
@RunWith(AndroidJUnit4::class)
class SyncQueueConcurrencyTest {

    private val context    = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDbName = "martyra_queue_test.db"
    private lateinit var db: MartyraDatabase
    private lateinit var evidenceDir: java.io.File
    private lateinit var repo: EvidenceRepositoryTestHelper.Repo
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
        db = MartyraDatabase.getInstance(context, com.martyra.localdb.security.FixedPassphraseProvider("queue_test_pass"))
        evidenceDir = java.io.File(context.filesDir, "queue_test_evidence").also { it.mkdirs() }
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

    // ── Test 1: Two concurrent workers — each job claimed by at most one ───

    /**
     * Insert 5 evidence records (10 jobs total: 5 HASH_ONLY + 5 FILE_UPLOAD).
     * Launch 10 concurrent workers. Each worker calls claimNext once.
     * Verify that each of the 10 jobs is claimed by at most one worker.
     *
     * OS concept: This tests the mutual exclusion property of the atomic claiming
     * mechanism (SELECT + conditional UPDATE in a Room transaction, serialised by SQLite).
     */
    @Test
    fun concurrentWorkers_eachJobClaimedByAtMostOneWorker() = runBlocking {
        // Insert 2 evidence records → 4 jobs
        repeat(2) {
            TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        }

        val now = System.currentTimeMillis()
        val claimedJobIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val nullClaims = AtomicInteger(0)

        // Launch 10 coroutines racing to claim jobs
        val workers = (1..10).map { i ->
            async {
                val workerId = "worker-$i"
                val claimed = db.syncQueueDao().claimNext(workerId, now)
                if (claimed != null) {
                    synchronized(claimedJobIds) { claimedJobIds.add(claimed.queueId) }
                } else {
                    nullClaims.incrementAndGet()
                }
            }
        }
        workers.awaitAll()

        // No job ID should appear twice
        val distinct = claimedJobIds.distinct()
        assertEquals(
            "Each job must be claimed by at most one worker (duplicates: ${claimedJobIds.size - distinct.size})",
            distinct.size,
            claimedJobIds.size
        )

        // At most 4 jobs were available (2 HASH_ONLY + 2 FILE_UPLOAD)
        assertTrue("At most 4 workers should have claimed a job", claimedJobIds.size <= 4)
        // The rest should have gotten null
        assertTrue("Remaining workers should have gotten null", nullClaims.get() >= 6)
    }

    // ── Test 2: Expired lease recovery ────────────────────────────────────

    /**
     * Simulate a crashed worker by:
     * 1. Claiming a job (state = IN_PROGRESS, locked_at = oldTime).
     * 2. Setting locked_at to a time far in the past to simulate an expired lease.
     * 3. Running recoverExpiredLeases.
     * 4. Verifying the job is back to PENDING with incremented attempts.
     */
    @Test
    fun expiredLease_recoveredByRecoveryScan() = runBlocking {
        TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)

        val now = System.currentTimeMillis()
        // Claim the first job
        val claimed = db.syncQueueDao().claimNext("worker-crash", now)
        assertNotNull("Should have claimed a job", claimed)

        // Simulate lease expiry: set locked_at to 2 hours ago
        val staleTime = now - (2 * 60 * 60 * 1000L)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE sync_queue SET locked_at = $staleTime WHERE queue_id = ${claimed!!.queueId}"
        )

        // Recover expired leases with a 30-minute timeout
        val recovered = db.syncQueueDao().recoverExpiredLeases(now, leaseTimeoutMs = 30 * 60 * 1000L)
        assertTrue("At least 1 lease should be recovered", recovered >= 1)

        // The job should now be PENDING again with attempts incremented
        val job = db.syncQueueDao().getJob(claimed.evidenceId, claimed.jobType)
        assertNotNull(job)
        assertEquals("PENDING", job!!.state)
        assertEquals(null, job.lockedBy)
        assertTrue("Attempts should have incremented", job.attempts > claimed.attempts)
    }

    // ── Test 3: Backoff formula ────────────────────────────────────────────

    @Test
    fun backoffFormula_producesCorrectDelays() {
        assertEquals(30_000L,     SyncQueueDao.computeBackoffMs(0))   // 2^0 * 30s
        assertEquals(60_000L,     SyncQueueDao.computeBackoffMs(1))   // 2^1 * 30s
        assertEquals(120_000L,    SyncQueueDao.computeBackoffMs(2))   // 2^2 * 30s
        assertEquals(3_600_000L,  SyncQueueDao.computeBackoffMs(7))   // cap = 1 hour
        assertEquals(3_600_000L,  SyncQueueDao.computeBackoffMs(100)) // still 1 hour
    }

    // ── Test 4: Priority ordering — HASH_ONLY claimed before FILE_UPLOAD ───

    @Test
    fun priorityOrdering_hashOnlyClaimedBeforeFileUpload() = runBlocking {
        TestFixtures.insertOneEvidence(db, repo.repository, sessionId, evidenceDir)
        val now = System.currentTimeMillis()

        val first  = db.syncQueueDao().claimNext("worker-A", now)
        val second = db.syncQueueDao().claimNext("worker-B", now)

        assertNotNull("First claim must succeed", first)
        assertNotNull("Second claim must succeed", second)
        assertEquals("First claim must be HASH_ONLY (priority 1)", "HASH_ONLY", first!!.jobType)
        assertEquals("Second claim must be FILE_UPLOAD (priority 2)", "FILE_UPLOAD", second!!.jobType)
    }
}
