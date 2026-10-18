package com.martyra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.io.File

private const val NOTIF_ID = 2
private const val CH_ID = "martyra_upload"

class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var drainJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CH_ID, "Uploading evidence", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "martyra:upload")
                .also { it.acquire(10 * 60 * 1000L) }
        }
        if (drainJob?.isActive != true) {
            drainJob = scope.launch {
                try {
                    while (true) {
                        val ok = drainOutbox(applicationContext)
                        val outbox = File(applicationContext.filesDir, "outbox")
                        val hasPending = outbox.listFiles { f -> f.name.endsWith(".enc") }?.isNotEmpty() == true
                        if (!hasPending) break
                        if (!ok) delay(3_000L)
                    }
                } finally {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Uploading evidence…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        fun start(ctx: Context) {
            val intent = Intent(ctx.applicationContext, UploadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
        }
    }
}
