package com.martyra.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "martyra_vault"
private const val PREFS_VAULT = "vault"
private const val PREF_PASS = "pass"
private const val IV_LEN = 12
private const val TAG_LEN = 128

private fun keystoreKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
}

private fun passphrase(ctx: Context): ByteArray {
    val prefs = ctx.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE)
    val stored = prefs.getString(PREF_PASS, null)
    if (stored != null) {
        val raw = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        val iv = raw.copyOf(IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_LEN, iv))
        return c.doFinal(ct)
    }
    val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, keystoreKey())
    val ct = c.doFinal(pass)
    val blob = c.iv + ct
    prefs.edit().putString(PREF_PASS, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply()
    return pass
}

fun seal(ctx: Context, raw: File, sessionId: Long, seq: Int, ts: Long, lat: Double?, lon: Double?) {
    val outbox = File(ctx.filesDir, "outbox").also { it.mkdirs() }
    val ext = if (raw.name.endsWith(".jpg")) "jpg" else "mp4"
    val hash = sha256Mapped(raw)
    val latS = lat?.let { "%.6f".format(it) } ?: "na"
    val lonS = lon?.let { "%.6f".format(it) } ?: "na"
    val name = "${sessionId}_${"%04d".format(seq)}_${ts}_${latS}_${lonS}_${hash}.${ext}.enc"
    val tmp = File(outbox, "$name.tmp")
    val enc = File(outbox, name)
    try {
        val pass = passphrase(ctx)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey(pass))
        val iv = c.iv
        tmp.outputStream().use { out ->
            out.write(iv)
            raw.inputStream().use { c.wrap(it).copyTo(out) }
        }
        tmp.fd()
        tmp.renameTo(enc)
        raw.delete()
    } catch (_: Exception) {
        tmp.delete()
    }
}

private fun sha256Mapped(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(file, "r").use { raf ->
        val ch = raf.channel
        var pos = 0L
        val size = ch.size()
        val win = 1L shl 20
        while (pos < size) {
            val len = minOf(win, size - pos)
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, pos, len)
            val arr = ByteArray(len.toInt())
            buf.get(arr)
            digest.update(arr)
            pos += len
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun aesKey(passphrase: ByteArray): SecretKey {
    return javax.crypto.spec.SecretKeySpec(passphrase, "AES")
}

fun open(ctx: Context, enc: File): InputStream {
    val pass = passphrase(ctx)
    val raw = enc.inputStream()
    val iv = ByteArray(IV_LEN).also { raw.read(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, aesKey(pass), GCMParameterSpec(TAG_LEN, iv))
    return c.wrap(raw)
}

private fun File.fd() = RandomAccessFile(this, "rw").use { it.fd.sync() }
