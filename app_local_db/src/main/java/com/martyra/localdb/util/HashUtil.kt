package com.martyra.localdb.util

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Cryptographic hashing utilities for evidence files and arbitrary byte arrays.
 *
 * ── Streaming design ──────────────────────────────────────────────────────
 * [sha256(file)] reads the file in 8 KB chunks. A 500 MB video file is never
 * fully loaded into RAM — only the current chunk occupies the buffer.
 *
 * OS concept: Streaming I/O mimics a kernel read-loop: the process issues repeated
 * read(2) syscalls to fill a fixed-size buffer, processes the data, and loops.
 * This bounds the process's RSS to O(buffer_size) regardless of file size.
 *
 * ── Output format ─────────────────────────────────────────────────────────
 * All hashes are returned as 64 lowercase hexadecimal characters.
 * No Base64, no uppercase, no prefix. This format is stable across platforms.
 */
object HashUtil {

    private const val BUFFER_SIZE = 8 * 1024  // 8 KB — fits comfortably in L1 cache

    /**
     * Compute the SHA-256 hash of a file by streaming its bytes.
     *
     * @param file The file to hash. Must exist and be a regular file.
     * @return 64 lowercase hex characters.
     * @throws IOException if the file cannot be read.
     */
    @Throws(IOException::class)
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Compute the SHA-256 hash of an in-memory byte array.
     * Use [sha256(File)] for large files to avoid loading them entirely into RAM.
     */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Convenience: hash a UTF-8 string. */
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    // ── Internal ───────────────────────────────────────────────────────────

    /** Convert a byte array to a lowercase hex string. */
    internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
