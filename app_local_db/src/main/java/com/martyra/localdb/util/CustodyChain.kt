package com.martyra.localdb.util

import com.martyra.localdb.data.dao.CustodyEventDao
import com.martyra.localdb.data.entity.CustodyEventEntity

/**
 * Computes and verifies the hash chain for custody events.
 *
 * ── Canonical event hash ──────────────────────────────────────────────────
 * The hash input is:
 *
 *   "MARTYRA-CUSTODY-V1\n" + canonical_json
 *
 * where canonical_json contains exactly these five fields in alphabetical key order:
 *   { "details": <string|null>, "event_time": <integer>, "event_type": <string>,
 *     "evidence_id": <string>, "prev_event_hash": <string> }
 *
 * The version prefix "MARTYRA-CUSTODY-V1\n" provides domain separation: a hash computed
 * for custody events cannot collide with hashes produced by other MARTYRA subsystems.
 *
 * ── Why event_id is excluded ──────────────────────────────────────────────
 * SQLite assigns the AUTOINCREMENT rowid during the INSERT statement's execution.
 * At the point where the application computes the event_hash (before calling INSERT),
 * the event_id does not yet exist. Including it would require a two-phase write:
 * insert a placeholder row, compute the hash, and then update — but the update would
 * fire the BEFORE UPDATE trigger that prevents modifications. The chain's integrity
 * does not depend on event_id; it depends on the ordering implied by the hash chain itself.
 *
 * ── Chain construction (per evidence item) ────────────────────────────────
 * First event:  prev_event_hash = ZERO_HASH (64 zero characters)
 * Later events: prev_event_hash = event_hash of the immediately preceding event
 *               (highest event_id for that evidence_id at the moment of insertion,
 *                read within the same Room transaction).
 *
 * ── Concurrent safety ─────────────────────────────────────────────────────
 * Chain-link reads and event inserts occur inside a single [withTransaction] block in
 * [EvidenceRepository]. SQLite serialises writers, so two concurrent ingest operations
 * for the same evidence item (not a normal use case) cannot interleave their chain links.
 */
object CustodyChain {

    /** Predecessor hash used for the very first event in a chain. */
    const val ZERO_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

    private const val HASH_VERSION_PREFIX = "MARTYRA-CUSTODY-V1\n"

    // ── Hash computation ───────────────────────────────────────────────────

    /**
     * Compute the canonical event hash.
     * Inputs are the five chain fields in alphabetical JSON key order.
     * @return 64 lowercase hex characters.
     */
    fun computeEventHash(
        evidenceId: String,
        eventType: String,
        eventTime: Long,
        details: String?,
        prevEventHash: String,
    ): String {
        val canonical = buildCanonicalInput(evidenceId, eventType, eventTime, details, prevEventHash)
        return HashUtil.sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * Build the canonical string that is hashed.
     * Keys in alphabetical order: details, event_time, event_type, evidence_id, prev_event_hash.
     */
    private fun buildCanonicalInput(
        evidenceId: String,
        eventType: String,
        eventTime: Long,
        details: String?,
        prevEventHash: String,
    ): String = buildString {
        append(HASH_VERSION_PREFIX)
        append('{')
        appendJsonStringOrNull("details", details)
        append(',')
        appendJsonLong("event_time", eventTime)
        append(',')
        appendJsonString("event_type", eventType)
        append(',')
        appendJsonString("evidence_id", evidenceId)
        append(',')
        appendJsonString("prev_event_hash", prevEventHash)
        append('}')
    }

    // ── Chain verification ─────────────────────────────────────────────────

    /**
     * Verify the entire custody chain for a given evidence item.
     *
     * Algorithm:
     * 1. Load events in ascending event_id order.
     * 2. For each event:
     *    a. Confirm event.prev_event_hash equals the previous event's event_hash
     *       (or ZERO_HASH for the first event).
     *    b. Recompute the event_hash from the canonical event data.
     *    c. Compare with the stored event_hash.
     * 3. Return the first failure, or [VerificationResult.Valid].
     *
     * This detects in-place mutations and broken links, but does NOT detect a completely
     * fabricated consistent chain. See [VerificationResult] for the full limitation.
     */
    suspend fun verify(evidenceId: String, custodyEventDao: CustodyEventDao): VerificationResult {
        val events = custodyEventDao.getChain(evidenceId)
        if (events.isEmpty()) return VerificationResult.EvidenceNotFound

        var expectedPrevHash = ZERO_HASH
        for (event in events) {
            // Step a: Link check
            if (event.prevEventHash != expectedPrevHash) {
                return VerificationResult.BrokenLink(event.eventId)
            }
            // Step b/c: Hash recomputation and comparison
            val recomputed = computeEventHash(
                evidenceId    = event.evidenceId,
                eventType     = event.eventType,
                eventTime     = event.eventTime,
                details       = event.details,
                prevEventHash = event.prevEventHash,
            )
            if (recomputed != event.eventHash) {
                return VerificationResult.InvalidEventHash(event.eventId)
            }
            expectedPrevHash = event.eventHash
        }
        return VerificationResult.Valid
    }

    // ── JSON primitives (same escaping as MetadataCanonicalizer) ──────────

    private fun StringBuilder.appendJsonString(key: String, value: String) {
        append('"').append(MetadataCanonicalizer.escapeJsonString(key)).append('"')
        append(':')
        append('"').append(MetadataCanonicalizer.escapeJsonString(value)).append('"')
    }

    private fun StringBuilder.appendJsonStringOrNull(key: String, value: String?) {
        append('"').append(MetadataCanonicalizer.escapeJsonString(key)).append('"')
        append(':')
        if (value == null) append("null")
        else append('"').append(MetadataCanonicalizer.escapeJsonString(value)).append('"')
    }

    private fun StringBuilder.appendJsonLong(key: String, value: Long) {
        append('"').append(key).append('"')
        append(':')
        append(value)
    }

    // ── Helper for repository ─────────────────────────────────────────────

    /**
     * Build a [CustodyEventEntity] with a correctly chained hash.
     * Must be called within the same database transaction that reads [prevHash].
     */
    fun buildEvent(
        evidenceId: String,
        eventType: String,
        eventTime: Long,
        details: String?,
        prevHash: String,
    ): CustodyEventEntity {
        val hash = computeEventHash(evidenceId, eventType, eventTime, details, prevHash)
        return CustodyEventEntity(
            evidenceId    = evidenceId,
            eventType     = eventType,
            eventTime     = eventTime,
            details       = details,
            prevEventHash = prevHash,
            eventHash     = hash,
        )
    }
}
