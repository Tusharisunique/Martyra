package com.martyra.localdb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.martyra.localdb.data.entity.SyncQueueEntity

/**
 * Data Access Object for the [sync_queue] table.
 *
 * Uses an abstract class (not interface) to provide [claimNext] — a @Transaction method
 * with a concrete body that orchestrates multiple queries atomically.
 *
 * OS concepts:
 *   Producer-consumer: Workers call [claimNext]; [EvidenceRepository] produces PENDING jobs.
 *   Atomic claiming: The SELECT + conditional UPDATE in [claimNext] runs inside a single
 *     Room transaction. SQLite serialises writers, so if two coroutines race to claim the
 *     same job, only the first UPDATE will find state='PENDING' and succeed. The second will
 *     return 0 rows affected and yield null, preventing double-claim.
 *   Lease / crash recovery: [recoverExpiredLeases] resets stale IN_PROGRESS jobs whose
 *     locked_at is older than a configurable timeout.
 *   Exponential backoff: [computeBackoffMs] applies min(2^attempts * 30s, 1h).
 *   Priority scheduling: jobs are ordered (priority ASC, queue_id ASC) so lower-numbered
 *     priorities (HASH_ONLY = 1) are always preferred, with FIFO tie-breaking.
 */
@Dao
abstract class SyncQueueDao {

    // ─── Insert ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(job: SyncQueueEntity): Long

    // ─── Read ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_queue WHERE evidence_id = :evidenceId AND job_type = :jobType LIMIT 1")
    abstract suspend fun getJob(evidenceId: String, jobType: String): SyncQueueEntity?

    /**
     * Highest-priority PENDING job whose next_retry_at <= now.
     * Ties broken by queue_id (FIFO order of insertion).
     * Internal helper — callers should use [claimNext].
     */
    @Query("""
        SELECT * FROM sync_queue
        WHERE state = 'PENDING' AND next_retry_at <= :now
        ORDER BY priority ASC, queue_id ASC
        LIMIT 1
    """)
    protected abstract suspend fun peekNextEligible(now: Long): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE state = 'PENDING' AND next_retry_at <= :now ORDER BY priority ASC, queue_id ASC")
    abstract suspend fun getEligibleJobs(now: Long): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state IN ('PENDING', 'IN_PROGRESS')")
    abstract suspend fun countPendingAndInProgress(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state = 'PENDING'")
    abstract suspend fun countPending(): Int

    /** Returns all IN_PROGRESS jobs whose lease has expired (locked_at < expiryThreshold). */
    @Query("SELECT * FROM sync_queue WHERE state = 'IN_PROGRESS' AND locked_at < :expiryThreshold")
    abstract suspend fun getExpiredLeases(expiryThreshold: Long): List<SyncQueueEntity>

    // ─── Conditional updates (return rows-affected for optimistic concurrency) ──

    /**
     * Atomically mark a job IN_PROGRESS.
     * The WHERE state='PENDING' condition ensures this is a no-op if another worker
     * already claimed the job between the peek and this update.
     */
    @Query("""
        UPDATE sync_queue
        SET state = 'IN_PROGRESS', locked_by = :workerId, locked_at = :now
        WHERE queue_id = :queueId AND state = 'PENDING'
    """)
    abstract suspend fun markInProgress(queueId: Long, workerId: String, now: Long): Int

    @Query("UPDATE sync_queue SET state = 'DONE' WHERE queue_id = :queueId AND locked_by = :workerId")
    abstract suspend fun markDone(queueId: Long, workerId: String): Int

    /**
     * Move a job back to PENDING with backoff applied.
     * attempts is incremented here so [computeBackoffMs] produces increasing delays.
     */
    @Query("""
        UPDATE sync_queue
        SET state = 'PENDING', locked_by = NULL, locked_at = NULL,
            attempts = attempts + 1, next_retry_at = :nextRetryAt
        WHERE queue_id = :queueId
    """)
    abstract suspend fun markForRetry(queueId: Long, nextRetryAt: Long): Int

    @Query("UPDATE sync_queue SET state = 'FAILED' WHERE queue_id = :queueId")
    abstract suspend fun markFailed(queueId: Long): Int

    /**
     * Recover an expired lease by resetting state to PENDING.
     * attempts is incremented so backoff grows on each recovery cycle.
     */
    @Query("""
        UPDATE sync_queue
        SET state = 'PENDING', locked_by = NULL, locked_at = NULL,
            attempts = attempts + 1, next_retry_at = :nextRetryAt
        WHERE queue_id = :queueId AND state = 'IN_PROGRESS'
    """)
    abstract suspend fun recoverLease(queueId: Long, nextRetryAt: Long): Int

    // ─── Composite operations ─────────────────────────────────────────────────

    /**
     * Atomically claim the highest-priority eligible PENDING job.
     *
     * Algorithm (all inside one Room transaction):
     * 1. Peek at the best eligible job.
     * 2. Attempt a conditional UPDATE WHERE state='PENDING'.
     * 3. If 0 rows were updated, another worker claimed it first — return null.
     * 4. Otherwise return the entity with state updated to IN_PROGRESS.
     *
     * OS concept: This implements the critical section for the producer-consumer queue.
     * SQLite serialises concurrent writers, so at most one coroutine wins the race even
     * without an explicit mutex. The WHERE state='PENDING' check is the compare-and-swap.
     *
     * Note: The caller should commit the claim (this transaction), perform network work
     * OUTSIDE any transaction, and then record the result in a separate transaction.
     * Holding a database transaction open during network I/O would starve other writers.
     */
    @Transaction
    open suspend fun claimNext(workerId: String, now: Long): SyncQueueEntity? {
        val job = peekNextEligible(now) ?: return null
        val affected = markInProgress(job.queueId, workerId, now)
        return if (affected > 0) {
            job.copy(state = "IN_PROGRESS", lockedBy = workerId, lockedAt = now)
        } else {
            null // Another worker claimed it between peek and update
        }
    }

    /**
     * Scan for and recover all expired leases.
     * Called by a periodic background scan; returns the number of leases recovered.
     */
    @Transaction
    open suspend fun recoverExpiredLeases(now: Long, leaseTimeoutMs: Long): Int {
        val expiryThreshold = now - leaseTimeoutMs
        val stale = getExpiredLeases(expiryThreshold)
        var recovered = 0
        for (job in stale) {
            val nextRetry = now + computeBackoffMs(job.attempts + 1)
            val rows = recoverLease(job.queueId, nextRetry)
            recovered += rows
        }
        return recovered
    }

    companion object {
        /**
         * Exponential backoff: min(2^attempts × 30 s, 1 h).
         *
         * attempts  delay
         *   0        30 s
         *   1        60 s
         *   2       120 s
         *   3       240 s
         *   4       480 s
         *   5       960 s (~16 min)
         *   6      1920 s (~32 min)
         *   7      3600 s (1 h, capped)
         *   ≥8     3600 s (stays at cap)
         *
         * Implementation: shift is capped at 7 so 2^7 * 30_000 = 3_840_000 > max,
         * and coerceAtMost clamps it. No integer overflow risk.
         */
        fun computeBackoffMs(attempts: Int): Long {
            val baseSec = 30_000L        // 30 seconds in ms
            val maxMs   = 3_600_000L    // 1 hour in ms
            val shift   = attempts.coerceIn(0, 7)
            return (baseSec * (1L shl shift)).coerceAtMost(maxMs)
        }
    }
}
