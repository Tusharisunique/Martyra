package com.martyra.localdb.util

/**
 * Typed result of a [CustodyChain.verify] operation.
 *
 * Using sealed classes instead of strings or error codes makes call sites exhaustive
 * (the Kotlin compiler can warn on non-exhaustive when expressions) and avoids
 * typo-prone string comparisons.
 *
 * ── Interpretation ────────────────────────────────────────────────────────
 * [Valid]              — All events passed hash verification. The local chain is self-consistent.
 * [BrokenLink]         — event.prev_event_hash does not match the preceding event's event_hash.
 *                        This indicates either a gap in the chain or a tampered prev_event_hash.
 * [InvalidEventHash]   — The event_hash stored in the database does not match the hash recomputed
 *                        from the event's canonical data. This indicates the event row was mutated
 *                        after insertion (the BEFORE UPDATE trigger should have prevented this, but
 *                        an out-of-band database write could bypass triggers).
 * [EvidenceNotFound]   — No custody events exist for the given evidence_id.
 *
 * ── Limitation ────────────────────────────────────────────────────────────
 * [Valid] means the stored chain is internally self-consistent — it does NOT prove the chain
 * was never completely replaced by a fabricated consistent chain. An independent verification
 * root (e.g. a blockchain anchor or trusted notary) is required to detect a full database swap.
 * This verifier detects partial tampering, deletions, and in-place mutations of event rows.
 */
sealed class VerificationResult {

    /** Every event in the chain passed hash verification. */
    object Valid : VerificationResult()

    /**
     * An event's prev_event_hash does not match the event_hash of its predecessor.
     * @param eventId The event_id of the first event that failed the link check.
     */
    data class BrokenLink(val eventId: Long) : VerificationResult()

    /**
     * An event's stored event_hash does not match the hash recomputed from its canonical data.
     * @param eventId The event_id of the first event with an invalid hash.
     */
    data class InvalidEventHash(val eventId: Long) : VerificationResult()

    /** No custody events were found for the given evidence_id. */
    object EvidenceNotFound : VerificationResult()
}
