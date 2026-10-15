package com.martyra.localdb.demo

import android.content.Context
import com.martyra.localdb.data.db.MartyraDatabase
import com.martyra.localdb.data.entity.CaptureSessionEntity
import com.martyra.localdb.repo.EvidenceModels.CaptureRequest
import com.martyra.localdb.repo.EvidenceModels.MediaType
import com.martyra.localdb.repo.EvidenceRepository
import java.io.File
import java.util.UUID

/**
 * Inserts 20 synthetic evidence records using real repository methods.
 *
 * ── Purpose ───────────────────────────────────────────────────────────────
 * DemoSeeder exercises the complete ingest pipeline end-to-end:
 * file creation → SHA-256 hashing → metadata canonicalization → custody chain → sync queue.
 * It is useful for manual testing, screenshots, and verifying the full stack works
 * before running automated instrumented tests.
 *
 * ── Idempotency ───────────────────────────────────────────────────────────
 * Each seed run creates a new session with a fresh UUID and new temp files.
 * Running seed() multiple times will insert additional evidence records rather than
 * replacing existing ones. To start fresh, wipe the application data or recreate the database.
 *
 * ── Demo data policy ──────────────────────────────────────────────────────
 * - No real personal information, GPS coordinates are synthetic (grid around 28.6°N, 77.2°E).
 * - Small 1–3 KB text files are used instead of real media to keep test execution fast.
 * - Actual SHA-256 hashes are computed from the created files — no hardcoded fake hashes.
 * - All evidence uses valid foreign-key references (session must exist before evidence is inserted).
 */
class DemoSeeder(
    private val context: Context,
    private val repository: EvidenceRepository,
    private val db: MartyraDatabase,
) {
    /**
     * Seed 20 synthetic evidence records.
     * @param demoDir Where sample files will be created. Defaults to [Context.filesDir]/demo_evidence.
     */
    suspend fun seed(demoDir: File = File(context.filesDir, "demo_evidence")) {
        demoDir.mkdirs()

        // Create a demo session
        val sessionId = UUID.randomUUID().toString()
        val startMs   = System.currentTimeMillis() - TWENTY_RECORDS_SPAN_MS
        db.captureSessionDao().insert(
            CaptureSessionEntity(
                sessionId  = sessionId,
                startedAt  = startMs,
                deviceId   = DEMO_DEVICE_ID,
                appVersion = DEMO_APP_VERSION,
                sandboxPid = android.os.Process.myPid(),
            )
        )

        val mediaTypes = MediaType.values()

        for (i in 0 until 20) {
            // Create a small synthetic file
            val mediaType = mediaTypes[i % mediaTypes.size]
            val filename  = "demo_${mediaType.name.lowercase()}_${i.toString().padStart(2, '0')}.bin"
            val file      = File(demoDir, filename)
            writeSmallDemoFile(file, i, mediaType)

            val capturedAt  = startMs + i * 60_000L          // 1 minute apart
            val gpsLat      = if (i % 4 != 0) 28.6 + i * 0.001 else null
            val gpsLon      = if (i % 4 != 0) 77.2 + i * 0.001 else null
            val gpsAccuracy = when {
                i % 5 == 0 -> 150.0  // deliberately poor — triggers GPS plausibility flag
                else        -> 15.0 + i
            }

            val request = CaptureRequest(
                sessionId        = sessionId,
                filePath         = file.absolutePath,
                mediaType        = mediaType.name,
                fileSizeBytes    = file.length(),
                gpsLat           = gpsLat,
                gpsLon           = gpsLon,
                gpsAccuracyM     = gpsAccuracy,
                capturedAt       = capturedAt,
                deviceId         = DEMO_DEVICE_ID,
                sandboxSignature = "demo_signature_$i",
                appVersion       = DEMO_APP_VERSION,
            )

            val result = repository.recordCapture(request)
            if (result.isFailure) {
                // Log but do not crash the seeder — remaining records should still be attempted.
                android.util.Log.w(TAG, "DemoSeeder: failed to insert record $i: ${result.errorOrNull()}")
            }
        }

        android.util.Log.i(TAG, "DemoSeeder: seeded 20 evidence records in session $sessionId")
    }

    private fun writeSmallDemoFile(file: File, index: Int, mediaType: MediaType) {
        // Write synthetic but deterministic content (not real media — just printable bytes).
        // The actual SHA-256 of this content is computed by the repository.
        val content = buildString {
            append("MARTYRA DEMO EVIDENCE\n")
            append("Index: $index\n")
            append("MediaType: $mediaType\n")
            append("CreatedAt: ${System.currentTimeMillis()}\n")
            // Pad to make the file size non-trivial and buffer-spanning.
            repeat(50) { append("padding-line-$it\n") }
        }
        file.writeText(content, Charsets.UTF_8)
    }

    companion object {
        private const val TAG                     = "DemoSeeder"
        private const val DEMO_DEVICE_ID          = "DEMO_DEVICE_001"
        private const val DEMO_APP_VERSION        = "1.0.0-demo"
        private const val TWENTY_RECORDS_SPAN_MS  = 20 * 60 * 1000L  // 20 minutes
    }
}
