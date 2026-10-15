package com.martyra.localdb

import com.martyra.localdb.util.HashUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Unit tests for [HashUtil].
 * Run with: ./gradlew :app_local_db:test
 */
class HashUtilTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── Known-output tests ─────────────────────────────────────────────────

    @Test
    fun `sha256 of known string matches reference vector`() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469f628369e1d5ee46
        val hash = HashUtil.sha256("abc".toByteArray(Charsets.UTF_8))
        assertEquals("ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469f628369e1d5ee46f", hash)
    }

    @Test
    fun `sha256 of known file content matches reference vector`() {
        val file = tempFolder.newFile("known.txt")
        file.writeText("abc", Charsets.UTF_8)
        val hash = HashUtil.sha256(file)
        assertEquals("ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469f628369e1d5ee46f", hash)
    }

    @Test
    fun `sha256 output is exactly 64 lowercase hex characters`() {
        val hash = HashUtil.sha256("test content".toByteArray())
        assertEquals(64, hash.length)
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Hash must be lowercase hex but was: $hash"
        }
    }

    // ── Empty file ─────────────────────────────────────────────────────────

    @Test
    fun `sha256 of empty file matches reference vector`() {
        // SHA-256 of empty input = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val emptyFile = tempFolder.newFile("empty.bin")
        val hash = HashUtil.sha256(emptyFile)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    // ── Large file (multi-buffer) ──────────────────────────────────────────

    @Test
    fun `sha256 of file larger than 8KB buffer produces correct hash`() {
        // Create a file of 20 KB (> 2x buffer size of 8 KB)
        val largeFile = tempFolder.newFile("large.bin")
        val data = ByteArray(20 * 1024) { (it % 256).toByte() }
        largeFile.writeBytes(data)

        val fileHash    = HashUtil.sha256(largeFile)
        val inMemHash   = HashUtil.sha256(data)

        assertEquals("Streaming hash must equal in-memory hash for the same content", inMemHash, fileHash)
        assertEquals(64, fileHash.length)
    }

    @Test
    fun `sha256 of 1MB file produces consistent result`() {
        val file = tempFolder.newFile("onemb.bin")
        val data = ByteArray(1024 * 1024) { (it % 128).toByte() }
        file.writeBytes(data)

        val hash1 = HashUtil.sha256(file)
        val hash2 = HashUtil.sha256(file)
        assertEquals("Repeated hash of same file must be identical", hash1, hash2)
    }

    // ── Different content → different hash ────────────────────────────────

    @Test
    fun `sha256 differs when file content differs`() {
        val file1 = tempFolder.newFile("a.bin").also { it.writeText("content A") }
        val file2 = tempFolder.newFile("b.bin").also { it.writeText("content B") }
        assertNotEquals(HashUtil.sha256(file1), HashUtil.sha256(file2))
    }

    // ── I/O error handling ─────────────────────────────────────────────────

    @Test
    fun `sha256 throws IOException for nonexistent file`() {
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        assertThrows(IOException::class.java) {
            HashUtil.sha256(nonExistent)
        }
    }
}
