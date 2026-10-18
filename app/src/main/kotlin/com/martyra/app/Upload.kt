package com.martyra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

const val ACTION_SEALED = "com.martyra.app.SEALED"

fun interface Uploader {
    suspend fun upload(data: InputStream, meta: Evidence): Boolean
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        val db = Db(applicationContext)
        val outbox = File(applicationContext.filesDir, "outbox")
        val uploader = HttpUploader(applicationContext)

        var retry = false
        while (true) {
            outbox.listFiles { f -> f.name.endsWith(".enc") }?.forEach { f ->
                parseEvidence(f.name)?.let { db.insertOrIgnore(it) }
            }
            val list = db.pending()
            if (list.isEmpty()) break
            var okAny = false
            for (ev in list) {
                val f = File(outbox, ev.file)
                if (!f.exists()) { db.setStatus(ev.file, UPLOADED); continue }
                db.setStatus(ev.file, SYNCING)
                if (uploader.upload(open(applicationContext, f), ev)) {
                    db.setStatus(ev.file, UPLOADED)
                    f.delete()
                    okAny = true
                } else {
                    retry = true
                    break
                }
            }
            if (retry || !okAny) break
        }
        if (retry) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        android.util.Log.e("UploadWorker", "worker error", e)
        Result.retry()
    }
}

class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SEALED) enqueueUpload(context)
    }
}

fun enqueueUpload(ctx: Context) {
    val appContext = ctx.applicationContext
    if (!WorkManager.isInitialized()) {
        try { WorkManager.initialize(appContext, Configuration.Builder().build()) } catch (_: Exception) {}
    }
    WorkManager.getInstance(appContext).enqueueUniqueWork(
        "martyra_upload",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
    )
}
