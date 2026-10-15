package com.martyra.localdb

import com.martyra.localdb.repo.EvidenceModels.CaptureMetadata
import com.martyra.localdb.util.MetadataCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [MetadataCanonicalizer].
 * Run with: ./gradlew :app_local_db:test
 */
class MetadataCanonicalizerTest {

    private val baseMetadata = CaptureMetadata(
        appVersion       = "1.0.0",
        capturedAt       = 1_700_000_000_000L,
        deviceId         = "DEVICE-001",
        gpsAccuracyM     = 12.5,
        gpsLat           = 28.6139,
        gpsLon           = 77.2090,
        mediaType        = "PHOTO",
        sandboxSignature = "sig-abc123",
    )

    // ── Canonical output format ────────────────────────────────────────────

    @Test
    fun `canonicalize produces valid JSON with keys in alphabetical order`() {
        val bytes = MetadataCanonicalizer.canonicalize(baseMetadata)
        val json  = bytes.toString(Charsets.UTF_8)

        // Must start and end with braces
        assert(json.startsWith("{")) { "Expected '{' but got: $json" }
        assert(json.endsWith("}"))   { "Expected '}' but got: $json" }

        // Keys must appear in alphabetical order
        val keyOrder = listOf(
            "app_version", "captured_at", "device_id", "gps_accuracy_m",
            "gps_lat", "gps_lon", "media_type", "sandbox_signature"
        )
        var lastIndex = -1
        for (key in keyOrder) {
            val idx = json.indexOf("\"$key\"")
            assert(idx > lastIndex) { "Key '$key' out of alphabetical order in: $json" }
            lastIndex = idx
        }
    }

    // ── Key-order independence ─────────────────────────────────────────────

    @Test
    fun `hash is identical regardless of field assignment order`() {
        // In Kotlin data classes, field order is fixed, but we verify that the same logical
        // metadata always produces the same hash regardless of how the object is constructed.
        val meta1 = baseMetadata
        val meta2 = baseMetadata.copy() // identical copy

        assertEquals(MetadataCanonicalizer.hash(meta1), MetadataCanonicalizer.hash(meta2))
    }

    @Test
    fun `hash is stable across multiple calls`() {
        val hash1 = MetadataCanonicalizer.hash(baseMetadata)
        val hash2 = MetadataCanonicalizer.hash(baseMetadata)
        assertEquals("Hash must be deterministic", hash1, hash2)
    }

    // ── Changed field → different hash ────────────────────────────────────

    @Test
    fun `changing app_version changes the hash`() {
        val changed = baseMetadata.copy(appVersion = "2.0.0")
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(changed))
    }

    @Test
    fun `changing captured_at changes the hash`() {
        val changed = baseMetadata.copy(capturedAt = baseMetadata.capturedAt + 1)
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(changed))
    }

    @Test
    fun `changing gps_lat changes the hash`() {
        val changed = baseMetadata.copy(gpsLat = baseMetadata.gpsLat!! + 0.000001)
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(changed))
    }

    @Test
    fun `changing media_type changes the hash`() {
        val changed = baseMetadata.copy(mediaType = "VIDEO")
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(changed))
    }

    @Test
    fun `changing sandbox_signature changes the hash`() {
        val changed = baseMetadata.copy(sandboxSignature = "sig-xyz789")
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(changed))
    }

    // ── Null handling ──────────────────────────────────────────────────────

    @Test
    fun `null gps fields serialize as JSON null literal`() {
        val noGps = baseMetadata.copy(gpsLat = null, gpsLon = null, gpsAccuracyM = null)
        val json  = MetadataCanonicalizer.canonicalize(noGps).toString(Charsets.UTF_8)

        assert("\"gps_lat\":null" in json)       { "Expected null literal for gps_lat: $json" }
        assert("\"gps_lon\":null" in json)       { "Expected null literal for gps_lon: $json" }
        assert("\"gps_accuracy_m\":null" in json) { "Expected null literal for gps_accuracy_m: $json" }
    }

    @Test
    fun `null and non-null gps produce different hashes`() {
        val noGps  = baseMetadata.copy(gpsLat = null)
        assertNotEquals(MetadataCanonicalizer.hash(baseMetadata), MetadataCanonicalizer.hash(noGps))
    }

    // ── Special character escaping ─────────────────────────────────────────

    @Test
    fun `special characters in string fields are JSON-escaped`() {
        val special = baseMetadata.copy(sandboxSignature = "has\"quote\\backslash\nnewline")
        val json    = MetadataCanonicalizer.canonicalize(special).toString(Charsets.UTF_8)

        // The raw characters must not appear unescaped in the JSON output
        assert("\\\"" in json) { "Expected escaped quote in: $json" }
        assert("\\\\" in json) { "Expected escaped backslash in: $json" }
        assert("\\n"  in json) { "Expected escaped newline in: $json" }
    }

    // ── Hash output format ─────────────────────────────────────────────────

    @Test
    fun `hash returns 64 lowercase hex characters`() {
        val hash = MetadataCanonicalizer.hash(baseMetadata)
        assertEquals(64, hash.length)
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) { "Hash not lowercase hex: $hash" }
    }
}
