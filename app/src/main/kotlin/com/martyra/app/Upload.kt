package com.martyra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeoutOrNull

const val ACTION_SEALED = "com.martyra.app.SEALED"

fun interface Uploader {
    suspend fun upload(data: InputStream, meta: Evidence): Boolean
}

internal val isDrainingOutbox = AtomicBoolean(false)

suspend fun drainOutbox(ctx: Context): Boolean {
    val outbox = File(ctx.filesDir, "outbox")
    val hasEnc = outbox.listFiles { f -> f.name.endsWith(".enc") }?.isNotEmpty() == true
    if (!hasEnc) return true
    if (!isDrainingOutbox.compareAndSet(false, true)) return false
    val db = Db(ctx)
    return try {
        val uploader = HttpUploader(ctx)
        var anyFailed = false
        while (true) {
            val encFiles = outbox.listFiles { f -> f.name.endsWith(".enc") } ?: emptyArray()
            android.util.Log.i("Upload", "Found ${encFiles.size} enc files in outbox")
            encFiles.forEach { f ->
                db.ensurePending(f.name)
                parseEvidence(f.name)?.let { db.insertOrIgnore(it) }
            }
            val list = db.pending()
            android.util.Log.i("Upload", "db.pending() has ${list.size} item(s)")
            if (list.isEmpty()) break
            var madeProgress = false
            for (ev in list) {
                val f = File(outbox, ev.file)
                if (!f.exists()) { db.setStatus(ev.file, UPLOADED); madeProgress = true; continue }
                db.setStatus(ev.file, SYNCING)
                val ok = withTimeoutOrNull(60_000L) {
                    uploader.upload(open(ctx, f), ev)
                } ?: false
                if (ok) {
                    db.setStatus(ev.file, UPLOADED)
                    f.delete()
                    madeProgress = true
                } else {
                    db.resetToCapture(ev.file)
                    anyFailed = true
                }
            }
            if (!madeProgress) break
        }
        !anyFailed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        android.util.Log.e("Upload", "drainOutbox error", e)
        false
    } finally {
        db.close()
        isDrainingOutbox.set(false)
    }
}

class UploadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel("martyra_upload", "Uploading evidence", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(applicationContext, "martyra_upload")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Uploading evidence…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, n)
        }
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val ok = drainOutbox(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
}

class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SEALED) enqueueUpload(context)
    }
}

fun enqueueUpload(ctx: Context) {
    runCatching { UploadService.start(ctx) }

    val appContext = ctx.applicationContext
    if (!WorkManager.isInitialized()) {
        runCatching { WorkManager.initialize(appContext, Configuration.Builder().build()) }
    }
    WorkManager.getInstance(appContext).enqueueUniqueWork(
        "martyra_upload",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
    )
}
