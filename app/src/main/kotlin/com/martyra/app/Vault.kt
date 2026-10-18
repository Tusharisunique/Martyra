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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ALIAS = "martyra_vault"
private const val WIN = 1_048_576L

private fun vaultKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    return (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        ?: KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build())
        }.generateKey()
}

fun dbPassphrase(ctx: Context): ByteArray {
    val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
    prefs.getString("pass", null)?.let { s ->
        val raw = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, raw.copyOf(12)))
        return c.doFinal(raw.copyOfRange(12, raw.size))
    }
    val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, vaultKey()) }
    prefs.edit().putString("pass", Base64.encodeToString(c.iv + c.doFinal(pass), Base64.NO_WRAP)).apply()
    return pass
}

fun seal(ctx: Context, raw: File, session: Long, seq: Int, ts: Long, lat: Double?, lon: Double?) {
    val outbox = File(ctx.filesDir, "outbox").also { it.mkdirs() }
    val ext = if (raw.name.endsWith(".jpg")) "jpg" else "mp4"
    val hash = sha256(raw)
    val latS = lat?.let { "%.6f".format(it) } ?: "na"
    val lonS = lon?.let { "%.6f".format(it) } ?: "na"
    val name = "${session}_${"%04d".format(seq)}_${ts}_${latS}_${lonS}_$hash.$ext.enc"
    val tmp = File(outbox, "$name.tmp"); val enc = File(outbox, name)
    try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dbPassphrase(ctx), "AES"))
        tmp.outputStream().use { out ->
            out.write(c.iv)
            CipherInputStream(raw.inputStream(), c).use { it.copyTo(out) }
        }
        RandomAccessFile(tmp, "rw").use { it.fd.sync() }
        tmp.renameTo(enc); raw.delete()
    } catch (_: Exception) { tmp.delete() }
}

fun open(ctx: Context, enc: File): InputStream {
    val s = enc.inputStream(); val iv = ByteArray(12).also { s.read(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dbPassphrase(ctx), "AES"), GCMParameterSpec(128, iv))
    return CipherInputStream(s, c)
}

private fun sha256(f: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(f, "r").use { raf ->
        val ch = raf.channel; var pos = 0L; val sz = ch.size()
        while (pos < sz) {
            val len = minOf(WIN, sz - pos)
            val arr = ByteArray(len.toInt())
            ch.map(FileChannel.MapMode.READ_ONLY, pos, len).get(arr)
            d.update(arr); pos += len
        }
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}
