package com.martyra.localdb.repo

import com.martyra.localdb.data.dao.CaptureSessionDao
import com.martyra.localdb.data.dao.CustodyEventDao
import com.martyra.localdb.data.dao.EvidenceDao
import com.martyra.localdb.data.dao.SyncQueueDao
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.data.entity.EvidenceEntity
import com.martyra.localdb.data.entity.SyncQueueEntity
import com.martyra.localdb.repo.EvidenceModels.CaptureMetadata
import com.martyra.localdb.repo.EvidenceModels.CaptureRequest
import com.martyra.localdb.repo.EvidenceModels.EvidenceStatus
import com.martyra.localdb.repo.EvidenceModels.MissingFileScanResult
import com.martyra.localdb.repo.EvidenceModels.SyncJobState
import com.martyra.localdb.repo.EvidenceModels.SyncJobType
import com.martyra.localdb.util.CustodyChain
import com.martyra.localdb.util.HashUtil
import com.martyra.localdb.util.MetadataCanonicalizer
import com.martyra.localdb.util.VerificationResult
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Coordinates all evidence lifecycle operations: capture ingest, status transitions,
 * file-existence verification, and custody-chain queries.
 *
 * ── Dependency injection ───────────────────────────────────────────────────
 * Constructor parameters are explicit so that tests can supply a test database,
 * a temp directory, and optional hooks without touching production code paths.
 *
 * ── Permitted evidence directory ──────────────────────────────────────────
 * [permittedEvidenceDir] is the only directory from which evidence file paths are accepted.
 * All file paths are canonicalized before comparison so symlinks and ".." components cannot
 * bypass the boundary. This is the filesystem access-control enforcement point.
 *
 * OS concept: least privilege — the capture module should place files only in a private
 * app directory owned by this app's UID. The repository validates the canonical path before
 * accepting any file, preventing path traversal even if a malicious caller supplied
 * "../../../sdcard/faked_evidence.jpg".
 *
 * ── Atomic ingest sequence ─────────────────────────────────────────────────
 * File hashing is performed OUTSIDE the database transaction (it may take several seconds
 * for a large video). All database writes (evidence insert + custody events + queue jobs +
 * status updates) occur inside a single [withTransaction] block. If any step fails, all
 * database changes are rolled back — no partial evidence record is left behind.
 *
 * OS concept: The file hash + database transaction combination resembles a two-phase commit.
 * Phase 1 (outside transaction): compute the content hash from the filesystem.
 * Phase 2 (inside transaction): write all database rows atomically.
 * If Phase 2 fails, the file is still present and the ingest can be retried cleanly.
 *
 * ── Status-transition policy ──────────────────────────────────────────────
 * See [EvidenceModels.EvidenceStatus] for the full state machine.
 * Invalid transitions are rejected before any database write.
 *
 * ── Plausibility flags ────────────────────────────────────────────────────
 * Timestamp and GPS accuracy warnings are appended as custody events (event_type = CAPTURED
 * with a structured details JSON). These are informational flags, not proof of fraud.
 */
class EvidenceRepository(
    private val db: MartyraDatabase,
    /** Root directory that all evidence file paths must be located within. */
    val permittedEvidenceDir: File,
    /**
     * Internal test hook.  MUST be null in production; set only from instrumented tests
     * to inject failures at specific points in the ingest sequence.
     */
    internal var testHook: IngestTestHook? = null,
) {
    private val evidenceDao: EvidenceDao             get() = db.evidenceDao()
    private val custodyEventDao: CustodyEventDao     get() = db.custodyEventDao()
    private val syncQueueDao: SyncQueueDao           get() = db.syncQueueDao()
    private val captureSessionDao: CaptureSessionDao get() = db.captureSessionDao()

    // ══════════════════════════════════════════════════════════════════════
    // recordCapture — atomic ingest of a captured evidence file
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validate, hash, and persist a newly captured evidence file.
     *
     * Pre-conditions (all validated before the database transaction opens):
     * - The capture session identified by [CaptureRequest.sessionId] must exist.
     * - [CaptureRequest.filePath] must resolve (canonically) to a path inside [permittedEvidenceDir].
     * - The file must exist and be a regular file.
     * - The file's actual size must match [CaptureRequest.fileSizeBytes].
     * - [CaptureRequest.mediaType] must be one of PHOTO, VIDEO, AUDIO.
     * - [CaptureRequest.deviceId] and [CaptureRequest.sandboxSignature] must be non-blank.
     * - If [CaptureRequest.callerProvidedHash] is non-null, it must match the computed SHA-256.
     *
     * Ingest sequence (inside a single Room transaction):
     * 1. Insert evidence with status CAPTURED.
     * 2. Append CAPTURED custody event.
     * 3. If |capturedAt − now| > 24 h, append a plausibility warning custody event.
     * 4. Update status to HASHED; append HASHED custody event.
     * 5. If gpsAccuracyM > 100 m (or null with explicit null handling), append GPS warning event.
     * 6. Insert HASH_ONLY queue job (priority 1, PENDING).
     * 7. Insert FILE_UPLOAD queue job (priority 2, PENDING).
     * 8. Update status to QUEUED; append QUEUED custody event.
     *
     * The [testHook] is called at defined points so instrumented tests can force a failure
     * mid-transaction and verify that all changes roll back.
     *
     * @return [RepositoryResult.Success] containing the final evidence entity (status = QUEUED),
     *         or [RepositoryResult.Failure] with a typed error.
     */
    suspend fun recordCapture(request: CaptureRequest): RepositoryResult<EvidenceEntity> {
        // ── Phase 1: Pre-transaction validation ───────────────────────────

        // 1a. Session must exist.
        val session = captureSessionDao.getById(request.sessionId)
            ?: return RepositoryResult.Failure(RepositoryError.NotFound(request.sessionId))

        // 1b. Resolve the canonical file path and validate the sandbox boundary.
        val file = File(request.filePath)
        val canonicalFile: File = try {
            file.canonicalFile
        } catch (e: IOException) {
            return RepositoryResult.Failure(RepositoryError.FileError("Cannot resolve canonical path: ${e.message}"))
        }
        val canonicalPermitted = permittedEvidenceDir.canonicalFile
        if (!isInsideDirectory(canonicalFile, canonicalPermitted)) {
            return RepositoryResult.Failure(
                RepositoryError.SecurityError(
                    "File path is outside the permitted evidence directory. " +
                    "Resolved: ${canonicalFile.path}, Permitted: ${canonicalPermitted.path}"
                )
            )
        }

        // 1c. File must exist and be a regular file.
        if (!canonicalFile.exists() || !canonicalFile.isFile) {
            return RepositoryResult.Failure(RepositoryError.FileError("Evidence file does not exist or is not a regular file: ${canonicalFile.path}"))
        }

        // 1d. File size must match.
        val actualSize = canonicalFile.length()
        if (actualSize != request.fileSizeBytes) {
            return RepositoryResult.Failure(RepositoryError.FileError("File size mismatch: expected ${request.fileSizeBytes}, actual $actualSize"))
        }

        // 1e. Media type must be valid.
        val validMediaTypes = setOf("PHOTO", "VIDEO", "AUDIO")
        if (request.mediaType !in validMediaTypes) {
            return RepositoryResult.Failure(RepositoryError.ValidationError("Invalid media_type '${request.mediaType}'. Must be one of: $validMediaTypes"))
        }

        // 1f. Required string fields must be non-blank.
        if (request.deviceId.isBlank())
            return RepositoryResult.Failure(RepositoryError.ValidationError("deviceId must not be blank"))
        if (request.sandboxSignature.isBlank())
            return RepositoryResult.Failure(RepositoryError.ValidationError("sandboxSignature must not be blank"))

        // ── Phase 2: File hashing (outside transaction — may take time for large files) ──
        //
        // OS concept: I/O is kept outside the transaction to avoid holding a database write lock
        // during a potentially slow streaming read. The hash is computed first, then the
        // transaction atomically records the result.

        val computedHash: String = try {
            HashUtil.sha256(canonicalFile)
        } catch (e: IOException) {
            return RepositoryResult.Failure(RepositoryError.FileError("I/O error while hashing file: ${e.message}"))
        }

        // Verify caller-provided hash if supplied.
        if (request.callerProvidedHash != null && request.callerProvidedHash != computedHash) {
            return RepositoryResult.Failure(
                RepositoryError.SecurityError("Caller-provided hash does not match computed hash")
            )
        }

        // ── Phase 3: Metadata hash ─────────────────────────────────────────
        val captureMetadata = CaptureMetadata(
            appVersion       = session.appVersion,
            capturedAt       = request.capturedAt,
            deviceId         = request.deviceId,
            gpsAccuracyM     = request.gpsAccuracyM,
            gpsLat           = request.gpsLat,
            gpsLon           = request.gpsLon,
            mediaType        = request.mediaType,
            sandboxSignature = request.sandboxSignature,
        )
        val metadataHash = MetadataCanonicalizer.hash(captureMetadata)

        // ── Phase 4: Single atomic database transaction ────────────────────
        val evidenceId = UUID.randomUUID().toString()
        val nowMs      = System.currentTimeMillis()

        return try {
            db.withTransaction {
                // ── 4a. Insert evidence (CAPTURED) ─────────────────────────
                testHook?.beforeInsertEvidence()

                val entity = EvidenceEntity(
                    evidenceId       = evidenceId,
                    sessionId        = request.sessionId,
                    mediaType        = request.mediaType,
                    filePath         = canonicalFile.path,
                    fileSizeBytes    = request.fileSizeBytes,
                    fileHash         = computedHash,
                    metadataHash     = metadataHash,
                    gpsLat           = request.gpsLat,
                    gpsLon           = request.gpsLon,
                    gpsAccuracyM     = request.gpsAccuracyM,
                    capturedAt       = request.capturedAt,
                    deviceId         = request.deviceId,
                    sandboxSignature = request.sandboxSignature,
                    status           = EvidenceStatus.CAPTURED.name,
                )
                evidenceDao.insert(entity)

                // ── 4b. CAPTURED custody event ─────────────────────────────
                appendCustodyEvent(evidenceId, "CAPTURED", nowMs, null)
                testHook?.afterCapturedEvent()

                // ── 4c. Timestamp plausibility flag ────────────────────────
                val timeDiffMs = kotlin.math.abs(nowMs - request.capturedAt)
                if (timeDiffMs > 24 * 60 * 60 * 1000L) {
                    val details = buildString {
                        append("{\"warning\":\"TIMESTAMP_PLAUSIBILITY\"")
                        append(",\"captured_at\":${request.capturedAt}")
                        append(",\"device_time_at_ingest\":$nowMs")
                        append(",\"diff_ms\":$timeDiffMs}")
                    }
                    appendCustodyEvent(evidenceId, "CAPTURED", nowMs, details)
                }

                // ── 4d. Status → HASHED ────────────────────────────────────
                evidenceDao.updateStatus(evidenceId, EvidenceStatus.HASHED.name)
                appendCustodyEvent(evidenceId, "HASHED", nowMs, null)
                testHook?.afterHashedEvent()

                // ── 4e. GPS accuracy plausibility flag ────────────────────
                // Explicitly handle the null case: missing accuracy is not flagged as poor,
                // but missing accuracy itself may be noted in a future hardening pass.
                val accuracyM = request.gpsAccuracyM
                if (accuracyM != null && accuracyM > 100.0) {
                    val details = "{\"warning\":\"GPS_ACCURACY_POOR\",\"gps_accuracy_m\":$accuracyM}"
                    appendCustodyEvent(evidenceId, "HASHED", nowMs, details)
                }

                // ── 4f. HASH_ONLY queue job ────────────────────────────────
                syncQueueDao.insert(
                    SyncQueueEntity(
                        evidenceId  = evidenceId,
                        jobType     = SyncJobType.HASH_ONLY.name,
                        priority    = 1,
                        nextRetryAt = nowMs,
                        state       = SyncJobState.PENDING.name,
                    )
                )

                // ── 4g. FILE_UPLOAD queue job ──────────────────────────────
                syncQueueDao.insert(
                    SyncQueueEntity(
                        evidenceId  = evidenceId,
                        jobType     = SyncJobType.FILE_UPLOAD.name,
                        priority    = 2,
                        nextRetryAt = nowMs,
                        state       = SyncJobState.PENDING.name,
                    )
                )
                testHook?.afterSyncJobsInserted()

                // ── 4h. Status → QUEUED ────────────────────────────────────
                evidenceDao.updateStatus(evidenceId, EvidenceStatus.QUEUED.name)
                appendCustodyEvent(evidenceId, "QUEUED", nowMs, null)

                RepositoryResult.Success(entity.copy(status = EvidenceStatus.QUEUED.name))
            }
        } catch (e: Exception) {
            RepositoryResult.Failure(RepositoryError.DatabaseError(e))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Status transitions
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Mark the evidence as HASH_SYNCED after the backend has received and confirmed the metadata hash.
     * Valid from: QUEUED.
     */
    suspend fun markHashSynced(evidenceId: String): RepositoryResult<Unit> =
        performStatusTransition(
            evidenceId  = evidenceId,
            allowedFrom = setOf(EvidenceStatus.QUEUED),
            to          = EvidenceStatus.HASH_SYNCED,
            eventType   = "HASH_SYNCED",
        )

    /**
     * Mark the evidence as FILE_SYNCED after the raw file bytes have been uploaded.
     * Valid from: QUEUED, HASH_SYNCED (both are acceptable because hash sync and file sync
     * may complete in the same network batch).
     */
    suspend fun markFileSynced(evidenceId: String): RepositoryResult<Unit> =
        performStatusTransition(
            evidenceId  = evidenceId,
            allowedFrom = setOf(EvidenceStatus.QUEUED, EvidenceStatus.HASH_SYNCED),
            to          = EvidenceStatus.FILE_SYNCED,
            eventType   = "FILE_SYNCED",
        )

    /**
     * Record a blockchain anchor. [txId] must be a non-blank transaction identifier.
     * [merkleProofJson] is optional JSON from the backend proving inclusion in the block.
     * Valid from: FILE_SYNCED.
     */
    suspend fun markAnchored(
        evidenceId: String,
        txId: String,
        merkleProofJson: String?,
    ): RepositoryResult<Unit> {
        if (txId.isBlank()) {
            return RepositoryResult.Failure(RepositoryError.ValidationError("txId must not be blank for ANCHORED transition"))
        }
        val evidence = evidenceDao.getById(evidenceId)
            ?: return RepositoryResult.Failure(RepositoryError.NotFound(evidenceId))

        val current = EvidenceStatus.valueOf(evidence.status)
        if (current !in setOf(EvidenceStatus.FILE_SYNCED)) {
            return RepositoryResult.Failure(RepositoryError.InvalidStateTransition(evidence.status, EvidenceStatus.ANCHORED.name))
        }

        return try {
            db.withTransaction {
                evidenceDao.updateAnchored(evidenceId, EvidenceStatus.ANCHORED.name, txId, merkleProofJson)
                appendCustodyEvent(
                    evidenceId = evidenceId,
                    eventType  = "ANCHORED",
                    eventTime  = System.currentTimeMillis(),
                    details    = "{\"tx_id\":\"${MetadataCanonicalizer.escapeJsonString(txId)}\"}"
                )
            }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure(RepositoryError.DatabaseError(e))
        }
    }

    /**
     * Mark evidence as CONFIRMED after on-chain finality is reached.
     * Valid from: ANCHORED.
     */
    suspend fun markConfirmed(evidenceId: String): RepositoryResult<Unit> =
        performStatusTransition(
            evidenceId  = evidenceId,
            allowedFrom = setOf(EvidenceStatus.ANCHORED),
            to          = EvidenceStatus.CONFIRMED,
            eventType   = "CONFIRMED",
        )

    // ══════════════════════════════════════════════════════════════════════
    // Missing-file detection
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Scan the evidence database and mark any items whose local file no longer exists.
     *
     * Only items in statuses where the file is expected to be locally present are checked:
     * CAPTURED, HASHED, QUEUED, HASH_SYNCED.
     * FILE_SYNCED, ANCHORED, and CONFIRMED are excluded because the file may have been
     * legitimately removed by the upload module after a successful transfer.
     * FILE_MISSING is excluded to avoid appending duplicate detection events on every scan.
     *
     * For each missing file:
     * - Validate the path against the permitted evidence directory (symlinks / traversal check).
     * - Update the status to FILE_MISSING.
     * - Append a FILE_MISSING_AFTER_CAPTURE custody event with a JSON explanation.
     * - Both operations occur in the same database transaction.
     *
     * OS concept: Filesystem and database are separate resources; there is no distributed
     * transaction spanning both. The database update is the authoritative record that the file
     * was found missing at a specific time.
     *
     * @return A [MissingFileScanResult] listing how many items were checked and which IDs were missing.
     */
    suspend fun verifyFilesExist(): MissingFileScanResult {
        val candidates = evidenceDao.getEvidenceRequiringLocalFile()
        val missingIds = mutableListOf<String>()
        val canonicalPermitted = permittedEvidenceDir.canonicalFile

        for (evidence in candidates) {
            try {
                val file = File(evidence.filePath)
                val canonicalFile = file.canonicalFile

                // Re-validate the path boundary (in case a symlink was created post-capture).
                if (!isInsideDirectory(canonicalFile, canonicalPermitted)) {
                    // Path is outside permitted dir — treat as missing (do not follow it).
                    recordFileMissing(evidence.evidenceId, reason = "PATH_OUTSIDE_PERMITTED_DIRECTORY")
                    missingIds.add(evidence.evidenceId)
                    continue
                }

                if (!canonicalFile.exists() || !canonicalFile.isFile) {
                    recordFileMissing(evidence.evidenceId, reason = "FILE_NOT_FOUND")
                    missingIds.add(evidence.evidenceId)
                }
            } catch (e: IOException) {
                recordFileMissing(evidence.evidenceId, reason = "IO_ERROR_CHECKING_PATH")
                missingIds.add(evidence.evidenceId)
            }
        }
        return MissingFileScanResult(checkedCount = candidates.size, missingIds = missingIds)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Queries
    // ══════════════════════════════════════════════════════════════════════

    suspend fun getEvidenceById(evidenceId: String): EvidenceEntity? =
        evidenceDao.getById(evidenceId)

    suspend fun getEvidenceByStatus(status: EvidenceStatus): List<EvidenceEntity> =
        evidenceDao.getByStatus(status.name)

    suspend fun getEvidenceBySession(sessionId: String): List<EvidenceEntity> =
        evidenceDao.getBySession(sessionId)

    suspend fun getEvidenceByDateRange(startMs: Long, endMs: Long): List<EvidenceEntity> =
        evidenceDao.getByDateRange(startMs, endMs)

    suspend fun getCustodyChain(evidenceId: String) =
        custodyEventDao.getChain(evidenceId)

    suspend fun getLatestCustodyEvent(evidenceId: String) =
        custodyEventDao.getLatestEvent(evidenceId)

    suspend fun countPendingSyncJobs(): Int =
        syncQueueDao.countPendingAndInProgress()

    suspend fun verifyCustodyChain(evidenceId: String): VerificationResult =
        CustodyChain.verify(evidenceId, custodyEventDao)

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generic status transition helper.
     * Validates the current status, updates it in the database, and appends a custody event
     * — all inside a single transaction.
     */
    private suspend fun performStatusTransition(
        evidenceId: String,
        allowedFrom: Set<EvidenceStatus>,
        to: EvidenceStatus,
        eventType: String,
        details: String? = null,
    ): RepositoryResult<Unit> {
        val evidence = evidenceDao.getById(evidenceId)
            ?: return RepositoryResult.Failure(RepositoryError.NotFound(evidenceId))

        val current = try {
            EvidenceStatus.valueOf(evidence.status)
        } catch (e: IllegalArgumentException) {
            return RepositoryResult.Failure(RepositoryError.ValidationError("Unrecognised evidence status in DB: ${evidence.status}"))
        }

        if (current !in allowedFrom) {
            return RepositoryResult.Failure(
                RepositoryError.InvalidStateTransition(evidence.status, to.name)
            )
        }

        return try {
            db.withTransaction {
                evidenceDao.updateStatus(evidenceId, to.name)
                appendCustodyEvent(evidenceId, eventType, System.currentTimeMillis(), details)
            }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure(RepositoryError.DatabaseError(e))
        }
    }

    /**
     * Append a custody event with a correctly chained hash.
     * MUST be called inside an active database transaction.
     */
    private suspend fun appendCustodyEvent(
        evidenceId: String,
        eventType: String,
        eventTime: Long,
        details: String?,
    ) {
        val prevHash = custodyEventDao.getLatestEventHash(evidenceId) ?: CustodyChain.ZERO_HASH
        val event = CustodyChain.buildEvent(evidenceId, eventType, eventTime, details, prevHash)
        custodyEventDao.insert(event)
    }

    /**
     * Record that a file has been found missing. Runs in its own transaction.
     */
    private suspend fun recordFileMissing(evidenceId: String, reason: String) {
        val nowMs = System.currentTimeMillis()
        db.withTransaction {
            evidenceDao.updateStatus(evidenceId, EvidenceStatus.FILE_MISSING.name)
            appendCustodyEvent(
                evidenceId = evidenceId,
                eventType  = "FILE_MISSING_AFTER_CAPTURE",
                eventTime  = nowMs,
                details    = "{\"reason\":\"${MetadataCanonicalizer.escapeJsonString(reason)}\"}",
            )
        }
    }

    /**
     * Returns true if [file] is located directly inside [directory] or any subdirectory.
     * Uses canonical paths to prevent symlink and ".." traversal attacks.
     */
    private fun isInsideDirectory(file: File, directory: File): Boolean {
        val dirPath = directory.path + File.separator
        return file.path.startsWith(dirPath) || file.path == directory.path
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal test hook interface — NOT part of the public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Allows instrumented tests to inject a [RuntimeException] at specific points during
     * [recordCapture] to verify that the Room transaction rolls back cleanly.
     *
     * MUST be null in production code. The interface uses default (no-op) implementations
     * so tests only override the injection point they need.
     */
    internal interface IngestTestHook {
        fun beforeInsertEvidence() {}
        fun afterCapturedEvent()   {}
        fun afterHashedEvent()     {}
        fun afterSyncJobsInserted() {}
    }

    /** Pre-built hook implementations for common test scenarios. */
    internal object TestHooks {
        fun failBefore(point: String): IngestTestHook = object : IngestTestHook {
            private fun maybeThrow(here: String) {
                if (here == point) throw RuntimeException("Test-injected failure at [$point]")
            }
            override fun beforeInsertEvidence()  { maybeThrow("beforeInsertEvidence") }
            override fun afterCapturedEvent()    { maybeThrow("afterCapturedEvent") }
            override fun afterHashedEvent()      { maybeThrow("afterHashedEvent") }
            override fun afterSyncJobsInserted() { maybeThrow("afterSyncJobsInserted") }
        }
    }
}
