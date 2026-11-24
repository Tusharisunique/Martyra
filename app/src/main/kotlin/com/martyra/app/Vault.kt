package com.martyra.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties.*
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ALIAS = "martyra_vault"
private const val SIGN_ALIAS = "martyra_sign"
private const val WIN = 1_048_576L

fun vaultKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    if (existing != null) return existing
    val kg = KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore")
    kg.init(KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
        .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
        .setKeySize(256).setUserAuthenticationRequired(false).build())
    return kg.generateKey()
}

private fun ensureSignKey(): KeyStore {
    val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    if (!ks.containsAlias(SIGN_ALIAS)) {
        val kg = KeyPairGenerator.getInstance(KEY_ALGORITHM_EC, "AndroidKeyStore")
        kg.initialize(KeyGenParameterSpec.Builder(SIGN_ALIAS, PURPOSE_SIGN or PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(DIGEST_SHA256).setUserAuthenticationRequired(false).build())
        kg.generateKeyPair()
    }
    return ks
}

/** SPKI DER bytes of the device's P-256 public key, base64url without padding. */
fun devicePublicKeyB64(): String {
    val ks = ensureSignKey()
    val cert = ks.getCertificate(SIGN_ALIAS)
    return Base64.encodeToString(cert.publicKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** SHA-256 of the SPKI DER bytes, hex. */
fun deviceKeyFp(): String {
    val ks = ensureSignKey()
    val spki = ks.getCertificate(SIGN_ALIAS).publicKey.encoded
    return MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
}

/** Sanitize device name per SIG-1: keep [A-Za-z0-9 ._-], max 64 chars. */
fun sanitizeDevice(raw: String): String =
    raw.replace(Regex("[^A-Za-z0-9 ._-]"), "").take(64).ifEmpty { "unknown" }

/** Build the canonical record string (SIG-1). Used at seal and at upload. */
fun buildRecord(fp: String, sha256: String, session: Long, seq: Int, ts: Long, lat: Double?, lon: Double?, kind: String, device: String): String {
    val latS = lat?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
    val lonS = lon?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
    return "martyra1|$fp|$sha256|$session|$seq|$ts|$latS|$lonS|$kind|$device"
}

/** Sign the record with the device key; returns base64url DER signature without padding. */
fun signRecord(record: String): String {
    val ks = ensureSignKey()
    val priv = ks.getKey(SIGN_ALIAS, null) as java.security.PrivateKey
    val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(record.toByteArray()) }.sign()
    return Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, vaultKey())
    val enc = c.doFinal(pass)
    prefs.edit().putString("pass", Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
    return pass
}

/** Seal: hash -> build record -> sign -> encrypt -> rename. File name carries the sig. */
fun seal(ctx: Context, raw: File, session: Long, seq: Int, ts: Long, lat: Double?, lon: Double?): Boolean {
    val outbox = File(ctx.filesDir, "outbox").also { it.mkdirs() }
    val ext = if (raw.name.endsWith(".jpg")) "jpg" else "mp4"
    val kind = if (ext == "jpg") "photo" else "video"
    val hash = sha256(raw) ?: return false
    val device = sanitizeDevice(Build.MODEL ?: "unknown")
    val fp = deviceKeyFp()
    val record = buildRecord(fp, hash, session, seq, ts, lat, lon, kind, device)
    val sig = signRecord(record)
    // AP-2: name stays under 255 bytes; sig is base64url so no slash; underscores in sig are ok
    val latS = lat?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
    val lonS = lon?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
    val name = "${session}_${seq}_${ts}_${latS}_${lonS}_${hash}_${sig}.$ext.enc"
    val tmp = File(outbox, "$name.tmp"); val enc = File(outbox, name)
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, vaultKey())
        tmp.outputStream().use { out -> out.write(c.iv); CipherOutputStream(out, c).use { cos -> raw.inputStream().use { it.copyTo(cos) } } }
        RandomAccessFile(tmp, "rw").use { it.fd.sync() }
        if (tmp.renameTo(enc)) { raw.delete(); true } else { tmp.delete(); false }
    } catch (_: Exception) { tmp.delete(); false }
}

fun open(ctx: Context, enc: File): InputStream {
    val s = enc.inputStream(); val iv = ByteArray(12); var read = 0
    while (read < 12) { val r = s.read(iv, read, 12 - read); if (r < 0) break; read += r }
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, iv))
    return CipherInputStream(s, c)
}

fun sha256(f: File): String? = runCatching {
    val d = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(f, "r").use { raf ->
        val ch = raf.channel; var pos = 0L; val sz = ch.size()
        while (pos < sz) { val len = minOf(WIN, sz - pos); d.update(ch.map(FileChannel.MapMode.READ_ONLY, pos, len)); pos += len }
    }
    d.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()
