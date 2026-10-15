package com.martyra.localdb.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pending or in-progress synchronization job for a piece of evidence.
 *
 * Job types:
 *   HASH_ONLY   — Upload the file hash and metadata to the backend (priority 1, highest).
 *   FILE_UPLOAD — Upload the actual file bytes to the backend (priority 2).
 *
 * The priority ordering (HASH_ONLY < FILE_UPLOAD numerically) ensures hash registration
 * is attempted before the heavier file upload. This lets the backend anchor the hash
 * even if the file upload subsequently fails.
 *
 * Priority enforcement:
 * The application layer (EvidenceRepository) validates the job_type ↔ priority relationship.
 * A SQLite trigger [enforce_sync_priority] additionally rejects any INSERT that violates it.
 *
 * OS concepts implemented here:
 *   Producer-consumer: EvidenceRepository (producer) inserts PENDING jobs; worker coroutines
 *     (consumers) claim them via SyncQueueDao.claimNext().
 *   Worker lease: locked_by + locked_at implement a heartbeat-style lease. If a worker
 *     crashes, a recovery scan identifies stale IN_PROGRESS jobs (locked_at < now - timeout).
 *   Priority scheduling: jobs are ordered by (priority ASC, queue_id ASC) so HASH_ONLY
 *     jobs are always drained before FILE_UPLOAD jobs.
 *   Exponential backoff: next_retry_at = now + min(2^attempts * 30s, 1h).
 */
@Entity(
    tableName = "sync_queue",
    foreignKeys = [
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["evidence_id", "job_type"], unique = true),   // One job per (evidence, type)
        Index(value = ["state", "priority", "next_retry_at"]),       // Worker eligibility scan
    ],
)
data class SyncQueueEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "queue_id")
    val queueId: Long = 0,

    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,

    /**
     * HASH_ONLY (priority 1) or FILE_UPLOAD (priority 2).
     * The (evidence_id, job_type) unique index ensures at most one job of each type per evidence.
     */
    @ColumnInfo(name = "job_type")
    val jobType: String,

    /** 1 = highest priority. HASH_ONLY must be 1; FILE_UPLOAD must be 2. */
    @ColumnInfo(name = "priority")
    val priority: Int,

    /** How many times this job has been attempted (including the current attempt if IN_PROGRESS). */
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    /** UTC epoch milliseconds; the job is not eligible for claiming before this time. */
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long,

    /** PENDING, IN_PROGRESS, DONE, or FAILED. */
    @ColumnInfo(name = "state")
    val state: String,

    /** ID of the worker coroutine / thread that claimed this job. NULL when not claimed. */
    @ColumnInfo(name = "locked_by")
    val lockedBy: String? = null,

    /** UTC epoch milliseconds when this job was last claimed. Used for lease-expiry recovery. */
    @ColumnInfo(name = "locked_at")
    val lockedAt: Long? = null,
)
