package com.martyra.app

import android.content.Context
import androidx.work.*
import java.io.File
import java.io.InputStream

fun interface Uploader { suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean }

private class LocalUploader(ctx: Context) : Uploader {
    private val cloud = ctx.getExternalFilesDir("cloud").also { it?.mkdirs() }
    override suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean {
        val dest = File(cloud ?: return false, key.replace("/", "_"))
        dest.outputStream().use { data.copyTo(it) }
        return true
    }
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val db = Db(applicationContext)
        val outbox = File(applicationContext.filesDir, "outbox")
        val uploader = LocalUploader(applicationContext)
        val uid = applicationContext.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("user_id", "unknown")!!

        outbox.listFiles { f -> f.name.endsWith(".enc") }?.forEach { f ->
            parseEvidence(f.name)?.let { db.insertOrIgnore(it) }
        }

        var retry = false
        for (ev in db.pending()) {
            db.setStatus(ev.file, SYNCING)
            val ext = ev.file.removeSuffix(".enc").substringAfterLast('.')
            val f = File(outbox, ev.file)
            if (!f.exists()) { db.setStatus(ev.file, UPLOADED); continue }
            try {
                val ok = uploader.upload("$uid/${ev.session}/${"${ev.seq}".padStart(4, '0')}.$ext", open(applicationContext, f), ev)
                if (ok) { db.setStatus(ev.file, UPLOADED); f.delete() }
                else { db.setStatus(ev.file, CAPTURED); retry = true }
            } catch (_: Exception) { db.setStatus(ev.file, CAPTURED); retry = true }
        }
        return if (retry) Result.retry() else Result.success()
    }
}

fun enqueueUpload(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
    "martyra_upload", ExistingWorkPolicy.APPEND_OR_REPLACE,
    OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build())
