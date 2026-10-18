package com.martyra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.io.File
import java.io.InputStream

const val ACTION_SEALED = "com.martyra.app.SEALED"

fun interface Uploader {
    suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean
}

class LocalUploader(ctx: Context) : Uploader {
    private val cloud = ctx.getExternalFilesDir("cloud").also { it?.mkdirs() }

    override suspend fun upload(key: String, data: InputStream, meta: Evidence): Boolean {
        val destDir = cloud ?: return false
        val finalName = meta.file.removeSuffix(".enc")
        val dest = File(destDir, finalName)
        val tmp = File(destDir, "$finalName.tmp")
        return try {
            tmp.outputStream().use { out ->
                data.use { input -> input.copyTo(out) }
            }
            if (tmp.renameTo(dest)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("UploadWorker", "LocalUploader failed for ${meta.file}", e)
            tmp.delete()
            false
        }
    }
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        val db = Db(applicationContext)
        val outbox = File(applicationContext.filesDir, "outbox")
        val uploader = LocalUploader(applicationContext)
        val uid = applicationContext.getSharedPreferences("profile", Context.MODE_PRIVATE)
            .getString("user_id", "unknown") ?: "unknown"

        outbox.listFiles { f -> f.name.endsWith(".enc") }?.forEach { f ->
            parseEvidence(f.name)?.let { db.insertOrIgnore(it) }
        }

        var retry = false
        for (ev in db.pending()) {
            val f = File(outbox, ev.file)
            if (!f.exists()) {
                db.setStatus(ev.file, UPLOADED)
                continue
            }
            db.setStatus(ev.file, SYNCING)
            val ext = if (ev.kind == "photo") "jpg" else "mp4"
            val key = "$uid/${ev.session}/${"${ev.seq}".padStart(4, '0')}.$ext"
            try {
                val ok = uploader.upload(key, open(applicationContext, f), ev)
                if (ok) {
                    db.setStatus(ev.file, UPLOADED)
                    f.delete()
                } else {
                    android.util.Log.w("UploadWorker", "upload returned false for ${ev.file}")
                    retry = true
                }
            } catch (e: Exception) {
                android.util.Log.e("UploadWorker", "exception in loop for ${ev.file}", e)
                retry = true
            }
        }
        if (retry) Result.retry() else Result.success()
    } catch (e: Throwable) {
        android.util.Log.e("UploadWorker", "Error processing uploads", e)
        Result.retry()
    }
}

class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SEALED) {
            enqueueUpload(context)
        }
    }
}

fun enqueueUpload(ctx: Context) {
    val appContext = ctx.applicationContext
    if (!WorkManager.isInitialized()) {
        try {
            WorkManager.initialize(appContext, Configuration.Builder().build())
        } catch (_: Exception) {
        }
    }
    WorkManager.getInstance(appContext).enqueueUniqueWork(
        "martyra_upload",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    )
}
