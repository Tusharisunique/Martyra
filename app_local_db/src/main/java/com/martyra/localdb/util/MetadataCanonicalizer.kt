package com.martyra.localdb.util

import com.martyra.localdb.repo.EvidenceModels.CaptureMetadata

/**
 * Produces a deterministic, canonical JSON representation of evidence capture metadata.
 *
 * ── Why canonical serialization matters ───────────────────────────────────
 * Standard JSON serializers (Gson, Jackson, kotlinx.serialization) do not guarantee
 * map key ordering. If key order is non-deterministic, two logically identical metadata
 * objects would produce different JSON bytes and therefore different SHA-256 hashes.
 * The metadata_hash would then fail to verify on any other device or after the metadata
 * was serialized a second time.
 *
 * ── Canonical format ──────────────────────────────────────────────────────
 * - Keys in strict ASCII alphabetical order (same as JSON RFC 8785 / JCS).
 * - String values are JSON-escaped with \", \\, \n, \r, \t, \uXXXX for control chars.
 * - Long values are serialized as JSON integers (no quotes, no decimal point).
 * - Double values are serialized using Kotlin's default toString (no trailing zeros).
 * - null is serialized as the JSON literal null (no quotes).
 * - No trailing commas, no whitespace outside string values.
 * - Encoded as UTF-8.
 *
 * ── Included fields (alphabetical order) ──────────────────────────────────
 *   app_version, captured_at, device_id, gps_accuracy_m,
 *   gps_lat, gps_lon, media_type, sandbox_signature
 *
 * The set of fields is fixed and documented. Adding a field is a breaking change
 * that would alter all previously computed metadata_hash values.
 */
object MetadataCanonicalizer {

    /**
     * Produce the canonical UTF-8 bytes for [metadata].
     * The returned bytes are the input to SHA-256 for metadata_hash computation.
     */
    fun canonicalize(metadata: CaptureMetadata): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        // Keys must appear in strict alphabetical order.
        // app_version < captured_at < device_id < gps_accuracy_m < gps_lat < gps_lon
        // < media_type < sandbox_signature
        appendString(sb, "app_version",       metadata.appVersion)
        sb.append(',')
        appendLong(sb, "captured_at",         metadata.capturedAt)
        sb.append(',')
        appendString(sb, "device_id",         metadata.deviceId)
        sb.append(',')
        appendDoubleOrNull(sb, "gps_accuracy_m", metadata.gpsAccuracyM)
        sb.append(',')
        appendDoubleOrNull(sb, "gps_lat",        metadata.gpsLat)
        sb.append(',')
        appendDoubleOrNull(sb, "gps_lon",        metadata.gpsLon)
        sb.append(',')
        appendString(sb, "media_type",        metadata.mediaType)
        sb.append(',')
        appendString(sb, "sandbox_signature", metadata.sandboxSignature)
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Compute the SHA-256 of the canonical metadata bytes.
     * @return 64 lowercase hex characters.
     */
    fun hash(metadata: CaptureMetadata): String =
        HashUtil.sha256(canonicalize(metadata))

    // ── JSON building primitives ───────────────────────────────────────────

    private fun appendString(sb: StringBuilder, key: String, value: String) {
        sb.append('"').append(escapeJsonString(key)).append('"')
        sb.append(':')
        sb.append('"').append(escapeJsonString(value)).append('"')
    }

    private fun appendLong(sb: StringBuilder, key: String, value: Long) {
        sb.append('"').append(key).append('"')
        sb.append(':')
        sb.append(value)
    }

    private fun appendDoubleOrNull(sb: StringBuilder, key: String, value: Double?) {
        sb.append('"').append(key).append('"')
        sb.append(':')
        if (value == null) sb.append("null") else sb.append(value)
    }

    /**
     * JSON string escaping per RFC 8259 §7.
     * Escapes: quotation mark, reverse solidus, and control characters (U+0000–U+001F).
     * Does NOT escape forward slashes or non-ASCII characters (they are valid in JSON strings).
     */
    internal fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
