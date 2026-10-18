package com.martyra.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.InputStream

fun interface Uploader {
    suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean
}

private class LocalUploader(ctx: Context) : Uploader {
    private val cloud = ctx.getExternalFilesDir("cloud")?.also { it.mkdirs() }

    override suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean {
        if (cloud == null) return false
        val dest = File(cloud, key.replace("/", "_"))
        dest.outputStream().use { data.copyTo(it) }
        return true
    }
}

private const val WORK_NAME = "martyra_upload"
private const val STATUS_CAPTURED = 0
private const val STATUS_SYNCING = 1
private const val STATUS_UPLOADED = 2

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val db = Db(applicationContext)
        val outbox = File(applicationContext.filesDir, "outbox")
        val uploader = LocalUploader(applicationContext)
        val prefs = applicationContext.getSharedPreferences("profile", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "unknown") ?: "unknown"

        outbox.listFiles { f -> f.name.endsWith(".enc") }?.forEach { f ->
            val ev = parseEvidence(f.name) ?: return@forEach
            db.insertOrIgnore(ev)
        }

        val pending = db.pending()
        var retry = false

        for (ev in pending) {
            if (ev.status >= STATUS_UPLOADED) continue
            db.setStatus(ev.file, STATUS_SYNCING)
            val ext = ev.file.substringBeforeLast(".enc").substringAfterLast(".")
            val key = "$userId/${ev.session}/${"%04d".format(ev.seq)}.$ext"
            val encFile = File(outbox, ev.file)
            if (!encFile.exists()) {
                db.setStatus(ev.file, STATUS_UPLOADED)
                continue
            }
            try {
                val stream = open(applicationContext, encFile)
                val ok = uploader.upload(key, stream, ev)
                if (ok) {
                    db.setStatus(ev.file, STATUS_UPLOADED)
                    encFile.delete()
                } else {
                    db.setStatus(ev.file, STATUS_CAPTURED)
                    retry = true
                }
            } catch (_: Exception) {
                db.setStatus(ev.file, STATUS_CAPTURED)
                retry = true
            }
        }

        return if (retry) Result.retry() else Result.success()
    }
}

fun enqueueUpload(ctx: Context) {
    WorkManager.getInstance(ctx).enqueueUniqueWork(
        WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()
    )
}
