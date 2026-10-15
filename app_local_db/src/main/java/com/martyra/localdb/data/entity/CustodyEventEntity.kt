package com.martyra.localdb.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single entry in the hash-chained custody log for a piece of evidence.
 *
 * Append-only invariant:
 * Two SQLite triggers prevent any modification or deletion of rows in this table:
 *   - prevent_custody_update (BEFORE UPDATE)
 *   - prevent_custody_delete (BEFORE DELETE)
 * Both execute RAISE(ABORT, 'custody_events is append-only').
 * See DatabaseCallback.kt.
 *
 * Hash chain design:
 * Each event commits to all previous events through the hash chain:
 *   event_hash = SHA-256("MARTYRA-CUSTODY-V1\n" + canonical_json)
 * where canonical_json contains {details, event_time, event_type, evidence_id, prev_event_hash}
 * in strict alphabetical key order.
 *
 * Why event_id is NOT included in the hash:
 * SQLite assigns the AUTOINCREMENT rowid during the INSERT, which occurs after the hash is
 * computed and supplied as a column value. Including event_id would create a circular dependency.
 * Instead, the chain integrity is maintained by the prev_event_hash field.
 *
 * The first event in any chain uses a prev_event_hash of 64 zero characters ("000...000").
 *
 * OS concept: The append-only property enforced by database triggers mirrors the write-once
 * semantics of a secure audit log. No application code path can overwrite history.
 */
@Entity(
    tableName = "custody_events",
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
        Index(value = ["evidence_id", "event_id"]),  // Chain traversal in ascending order
    ],
)
data class CustodyEventEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "event_id")
    val eventId: Long = 0,

    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,

    /**
     * One of the allowed event types:
     * CAPTURED, HASHED, QUEUED, HASH_SYNCED, FILE_SYNCED, ANCHORED, CONFIRMED,
     * FILE_MISSING_AFTER_CAPTURE, SYNC_RETRY, SYNC_FAILED
     */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /** UTC epoch milliseconds when this event occurred. */
    @ColumnInfo(name = "event_time")
    val eventTime: Long,

    /**
     * Compact JSON details (e.g. warning flags, error messages, proof references).
     * NULL for routine lifecycle events. The format is event-type-specific.
     */
    @ColumnInfo(name = "details")
    val details: String? = null,

    /**
     * event_hash of the immediately preceding event for this evidence_id, in ascending event_id order.
     * For the very first event, this is a 64-character all-zero hex string.
     */
    @ColumnInfo(name = "prev_event_hash")
    val prevEventHash: String,

    /**
     * SHA-256 of the canonical event payload. See CustodyChain.computeEventHash().
     * This field is set by the application before insertion and verified during chain validation.
     */
    @ColumnInfo(name = "event_hash")
    val eventHash: String,
)
