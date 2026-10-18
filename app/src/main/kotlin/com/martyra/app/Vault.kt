package com.martyra.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ALIAS = "martyra_vault"
private const val WIN = 1_048_576L

fun vaultKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    if (existing != null) return existing

    val kg = KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore")
    kg.init(
        KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
            .setBlockModes(BLOCK_MODE_GCM)
            .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
    )
    return kg.generateKey()
}

fun dbPassphrase(ctx: Context): ByteArray {
    val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
    prefs.getString("pass", null)?.let { s ->
        val raw = Base64.decode(s, Base64.NO_WRAP)
        val iv = raw.copyOf(12)
        val cipherBytes = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, iv))
        return c.doFinal(cipherBytes)
    }
    val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, vaultKey())
    val encrypted = c.doFinal(pass)
    prefs.edit().putString("pass", Base64.encodeToString(c.iv + encrypted, Base64.NO_WRAP)).apply()
    return pass
}

fun seal(ctx: Context, raw: File, session: Long, seq: Int, ts: Long, lat: Double?, lon: Double?): Boolean {
    val outbox = File(ctx.filesDir, "outbox").also { it.mkdirs() }
    val ext = if (raw.name.endsWith(".jpg")) "jpg" else "mp4"
    val hash = sha256(raw) ?: return false
    val latS = lat?.let { "%.6f".format(it) } ?: "na"
    val lonS = lon?.let { "%.6f".format(it) } ?: "na"
    val name = "${session}_${"%04d".format(seq)}_${ts}_${latS}_${lonS}_$hash.$ext.enc"
    val tmp = File(outbox, "$name.tmp")
    val enc = File(outbox, name)
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, vaultKey())
        tmp.outputStream().use { out ->
            out.write(c.iv)
            CipherOutputStream(out, c).use { cos ->
                raw.inputStream().use { input ->
                    input.copyTo(cos)
                }
            }
        }
        RandomAccessFile(tmp, "rw").use { it.fd.sync() }
        if (tmp.renameTo(enc)) {
            raw.delete()
            true
        } else {
            tmp.delete()
            false
        }
    } catch (e: Exception) {
        tmp.delete()
        false
    }
}

fun open(ctx: Context, enc: File): InputStream {
    val s = enc.inputStream()
    val iv = ByteArray(12)
    var read = 0
    while (read < 12) {
        val r = s.read(iv, read, 12 - read)
        if (r < 0) break
        read += r
    }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, iv))
    return CipherInputStream(s, c)
}

fun sha256(f: File): String? {
    return try {
        val d = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { raf ->
            val ch = raf.channel
            var pos = 0L
            val sz = ch.size()
            while (pos < sz) {
                val len = minOf(WIN, sz - pos)
                val buf = ch.map(FileChannel.MapMode.READ_ONLY, pos, len)
                d.update(buf)
                pos += len
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}
