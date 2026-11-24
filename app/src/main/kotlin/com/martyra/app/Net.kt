package com.martyra.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

private const val PREFS_AUTH = "auth"
private const val KEY_REFRESH = "refresh_token"

@Volatile private var accessToken: String? = null
@Volatile private var tokenExpiry: Long = 0L

private fun storeRefresh(ctx: Context, plain: String) {
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, vaultKey()) }
    val enc = c.doFinal(plain.toByteArray())
    ctx.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit()
        .putString(KEY_REFRESH, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
}

private fun loadRefresh(ctx: Context): String? = runCatching {
    val blob = ctx.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).getString(KEY_REFRESH, null) ?: return null
    val raw = Base64.decode(blob, Base64.NO_WRAP)
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, raw.copyOf(12))) }
    String(c.doFinal(raw.copyOfRange(12, raw.size)))
}.getOrNull()

private fun saveToken(ctx: Context, json: JSONObject) {
    storeRefresh(ctx, json.getString("refresh_token"))
    tokenExpiry = System.currentTimeMillis() + json.getLong("expires_in") * 1000L
    accessToken = json.getString("access_token")
}

private fun postAuth(ctx: Context, path: String, payload: String): Int {
    val conn = (URL("${BuildConfig.SUPABASE_URL}$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
        setRequestProperty("Content-Type", "application/json")
        doOutput = true; outputStream.use { it.write(payload.toByteArray()) }
    }
    return try {
        val code = conn.responseCode
        if (code in 200..299) saveToken(ctx, JSONObject(String(conn.inputStream.use { it.readBytes() })))
        else Log.e("Net", "postAuth $path $code")
        code
    } catch (e: Exception) { Log.e("Net", "postAuth $path: ${e.message}"); 500 }
    finally { conn.disconnect() }
}

fun getToken(ctx: Context): String? {
    if (accessToken != null && System.currentTimeMillis() + 60_000L < tokenExpiry) return accessToken
    val refresh = loadRefresh(ctx)
    if (refresh != null) {
        val code = runCatching { postAuth(ctx, "/auth/v1/token?grant_type=refresh_token", """{"refresh_token":"$refresh"}""") }.getOrDefault(500)
        if (code in 200..299) return accessToken
        if (code !in 400..403) return null
    }
    val code = runCatching { postAuth(ctx, "/auth/v1/signup", "{}") }.getOrDefault(500)
    return if (code in 200..299) accessToken else null
}

class HttpUploader(private val ctx: Context) : Uploader {
    override suspend fun upload(data: InputStream, meta: Evidence): Boolean = withContext(Dispatchers.IO) {
        val token = getToken(ctx) ?: run { data.close(); return@withContext false }
        val fp = deviceKeyFp()
        val record = buildRecord(fp, meta.sha256, meta.session, meta.seq, meta.ts, meta.lat, meta.lon, meta.kind, meta.device)
        // Use sig from DB row (set at seal time); if empty, resign now
        val sig = meta.sig.ifEmpty { signRecord(record) }
        val pk = devicePublicKeyB64()
        val latS = meta.lat?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
        val lonS = meta.lon?.let { String.format(Locale.US, "%.5f", it) } ?: "na"
        val name = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("name", "unknown") ?: "unknown"
        val q = "kind=${meta.kind}&ts=${meta.ts}&lat=${latS}&lon=${lonS}&sha256=${meta.sha256}" +
                "&device=${Uri.encode(meta.device.take(64))}&name=${Uri.encode(name)}" +
                "&sig=${Uri.encode(sig)}&pk=${Uri.encode(pk)}"
        val url = URL("${BuildConfig.SERVER_URL}/v1/evidence/${meta.session}/${meta.seq}?$q")
        val encFile = File(ctx.filesDir, "outbox/${meta.file}")
        val decryptedLen: Long = if (encFile.exists()) (encFile.length() - 28L).coerceAtLeast(0L) else -1L
        val readTimeoutMs = when { decryptedLen > 2_000_000L -> 120_000; decryptedLen > 500_000L -> 90_000; else -> 60_000 }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; connectTimeout = 15_000; readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            doOutput = true
            if (decryptedLen >= 0L) setFixedLengthStreamingMode(decryptedLen) else setChunkedStreamingMode(65536)
        }
        try {
            conn.outputStream.use { out -> data.use { it.copyTo(out, bufferSize = 65536) } }
            val code = conn.responseCode
            val body = runCatching { if (code in 200..299) conn.inputStream.use { it.readBytes() } else conn.errorStream?.use { it.readBytes() } }.getOrNull()
            if (code == 401) { accessToken = null; ctx.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit().remove(KEY_REFRESH).apply() }
            if (code !in 200..299) Log.e("HttpUploader", "Upload ${meta.file} HTTP $code: ${body?.let { String(it) }}")
            else Log.i("HttpUploader", "Upload ${meta.file} OK")
            code in 200..299
        } catch (e: Exception) { Log.e("HttpUploader", "Upload failed: ${e.message}"); accessToken = null; false }
        finally { conn.disconnect() }
    }
}
