package com.martyra.localdb.repo

/**
 * Domain model types shared between callers and [EvidenceRepository].
 * Entities ([EvidenceEntity] etc.) are only returned directly by DAO queries;
 * the repository also exposes them wrapped in [RepositoryResult] for error handling.
 */
object EvidenceModels {

    // ── Status and type enumerations ───────────────────────────────────────

    /**
     * Lifecycle status of an evidence item.
     *
     * Allowed transitions (enforced by EvidenceRepository):
     *
     *   CAPTURED ──→ HASHED ──→ QUEUED ──→ HASH_SYNCED ──→ FILE_SYNCED ──→ ANCHORED ──→ CONFIRMED
     *                                  └──→ FILE_SYNCED  (hash+file sync in one batch)
     *                                  └──→ SYNC_FAILED  (permanent failure after max retries)
     *   Any non-terminal ──→ FILE_MISSING  (detected by verifyFilesExist())
     *
     * Terminal statuses (no further progression): CONFIRMED, SYNC_FAILED.
     * FILE_MISSING and FILE_SYNCED/ANCHORED are quasi-terminal for local-file purposes.
     *
     * Note: CAPTURED → HASHED → QUEUED happen automatically during [EvidenceRepository.recordCapture]
     * in a single atomic transaction. Callers never call markHashed or markQueued directly.
     */
    enum class EvidenceStatus {
        CAPTURED,
        HASHED,
        QUEUED,
        HASH_SYNCED,
        FILE_SYNCED,
        ANCHORED,
        CONFIRMED,
        FILE_MISSING,
        SYNC_FAILED,
    }

    /** Allowed media types. Stored as String (name) in the database. */
    enum class MediaType { PHOTO, VIDEO, AUDIO }

    /** Sync job types. HASH_ONLY must always have priority 1; FILE_UPLOAD priority 2. */
    enum class SyncJobType { HASH_ONLY, FILE_UPLOAD }

    /** Sync job states. */
    enum class SyncJobState { PENDING, IN_PROGRESS, DONE, FAILED }

    /** Allowed custody event types. Stored as String (name) in the database. */
    enum class CustodyEventType {
        CAPTURED,
        HASHED,
        QUEUED,
        HASH_SYNCED,
        FILE_SYNCED,
        ANCHORED,
        CONFIRMED,
        FILE_MISSING_AFTER_CAPTURE,
        SYNC_RETRY,
        SYNC_FAILED,
    }

    // ── Input model ────────────────────────────────────────────────────────

    /**
     * All inputs required to record a new evidence capture.
     * Validated by [EvidenceRepository.recordCapture] before any database write.
     */
    data class CaptureRequest(
        val sessionId: String,
        val filePath: String,
        val mediaType: String,          // Use MediaType.name
        val fileSizeBytes: Long,
        val gpsLat: Double?,
        val gpsLon: Double?,
        val gpsAccuracyM: Double?,
        val capturedAt: Long,           // UTC epoch milliseconds
        val deviceId: String,
        val sandboxSignature: String,
        val appVersion: String,
        /** If provided, the repository will verify this matches the computed SHA-256. */
        val callerProvidedHash: String? = null,
    )

    /**
     * Canonical metadata fields that contribute to [EvidenceEntity.metadataHash].
     * Derived from [CaptureRequest] + session data by [EvidenceRepository].
     * Field set is documented as a breaking-change boundary in [MetadataCanonicalizer].
     */
    data class CaptureMetadata(
        val appVersion: String,
        val capturedAt: Long,
        val deviceId: String,
        val gpsAccuracyM: Double?,
        val gpsLat: Double?,
        val gpsLon: Double?,
        val mediaType: String,
        val sandboxSignature: String,
    )

    // ── Output models ──────────────────────────────────────────────────────

    /** Full evidence item with its complete custody chain. */
    data class EvidenceWithChain(
        val evidence: com.martyra.localdb.data.entity.EvidenceEntity,
        val events: List<com.martyra.localdb.data.entity.CustodyEventEntity>,
    )

    /** Summary of a missing-file detection scan. */
    data class MissingFileScanResult(
        val checkedCount: Int,
        val missingIds: List<String>,
    )
}
