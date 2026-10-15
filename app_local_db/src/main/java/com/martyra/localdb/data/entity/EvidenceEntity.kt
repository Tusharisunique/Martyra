package com.martyra.localdb.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single piece of captured evidence (photo, video, or audio).
 *
 * Immutability contract:
 * The following fields are set once at capture time and must never change afterwards:
 *   file_hash, metadata_hash, gps_lat, gps_lon, captured_at, device_id,
 *   sandbox_signature, file_path
 *
 * This immutability is enforced by a SQLite BEFORE UPDATE trigger [enforce_evidence_immutable]
 * installed in DatabaseCallback.onCreate(). See DatabaseCallback.kt.
 *
 * Mutable fields (the only ones the application may change after insertion):
 *   status, server_tx_id, merkle_proof
 *
 * OS concept: file_path points into the app's private sandbox directory
 * (/data/data/<package>/files/evidence/). The OS enforces that only the app process can
 * read or write this path. Storing only the private-sandbox path (not an external path)
 * provides a filesystem access-control boundary without additional permissions.
 */
@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.RESTRICT,   // Prevent session deletion while evidence references it
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["captured_at"]),          // Range queries by time
        Index(value = ["status"]),               // Filter by status (QUEUED, FILE_MISSING, etc.)
        Index(value = ["session_id"]),           // List all evidence in a session
    ],
)
data class EvidenceEntity(

    @PrimaryKey
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** One of PHOTO, VIDEO, AUDIO — validated at the repository layer before insertion. */
    @ColumnInfo(name = "media_type")
    val mediaType: String,

    /**
     * Absolute path inside the app's private sandbox.
     * Immutable after insertion. Validated with canonical-path resolution to prevent
     * path traversal and symlink attacks.
     */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,

    /**
     * Lowercase SHA-256 hex of the file content (64 characters, UNIQUE).
     * Computed by streaming the file through an 8 KB buffer — never loaded entirely into RAM.
     * Immutable after insertion.
     */
    @ColumnInfo(name = "file_hash")
    val fileHash: String,

    /**
     * SHA-256 of the canonical metadata JSON.
     * Canonical format is deterministic (sorted keys, explicit nulls, UTF-8).
     * Immutable after insertion.
     */
    @ColumnInfo(name = "metadata_hash")
    val metadataHash: String,

    /** WGS-84 latitude; NULL if GPS was unavailable. Immutable after insertion. */
    @ColumnInfo(name = "gps_lat")
    val gpsLat: Double? = null,

    /** WGS-84 longitude; NULL if GPS was unavailable. Immutable after insertion. */
    @ColumnInfo(name = "gps_lon")
    val gpsLon: Double? = null,

    /** Estimated horizontal accuracy in metres; NULL if not reported. Immutable after insertion. */
    @ColumnInfo(name = "gps_accuracy_m")
    val gpsAccuracyM: Double? = null,

    /** UTC epoch milliseconds when the capture occurred. Immutable after insertion. */
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,

    /** Stable device identifier. Immutable after insertion. */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /**
     * Signature from the capture module identifying the trusted capture context.
     * The future capture module is responsible for generating and verifying this value.
     * Stored verbatim here; this module does not validate the signature itself.
     * Immutable after insertion.
     */
    @ColumnInfo(name = "sandbox_signature")
    val sandboxSignature: String,

    /**
     * Lifecycle status — one of the values in [EvidenceStatus].
     * Valid transitions are enforced by EvidenceRepository. Only this field, server_tx_id,
     * and merkle_proof may be mutated after insertion.
     */
    @ColumnInfo(name = "status")
    val status: String,

    /**
     * Blockchain / backend transaction ID returned after HASH_SYNCED or FILE_SYNCED.
     * NULL until the sync module supplies it. Set atomically with an anchoring event.
     */
    @ColumnInfo(name = "server_tx_id")
    val serverTxId: String? = null,

    /**
     * JSON-encoded Merkle proof from the backend anchoring service.
     * NULL until ANCHORED. Set atomically with a ANCHORED custody event.
     */
    @ColumnInfo(name = "merkle_proof")
    val merkleProof: String? = null,
)
