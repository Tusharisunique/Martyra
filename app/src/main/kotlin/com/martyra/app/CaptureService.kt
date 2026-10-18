package com.martyra.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.Executors

private const val SEGMENT_MS = 3_000L
private const val CH_ID = "martyra_capture"
private const val NOTIF_ID = 1

fun isRooted(): Boolean {
    val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
    if (paths.any { File(it).exists() }) return true
    return Build.TAGS?.contains("test-keys") == true
}

class CaptureService : LifecycleService() {

    inner class Cap : Binder() {
        fun service() = this@CaptureService
    }

    private val binder = Cap()
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var recording: Recording? = null
    private var previewSurface: Surface? = null
    private var sessionId = 0L
    private var seq = 0
    private var isBound = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        sealLeftovers()
        createChannel()
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                provider = future.get()
                bindCamera()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        isBound = true
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        isBound = false
        setPreviewSurface(null)
        if (recording == null) stopSelf()
        return false
    }

    override fun onDestroy() {
        stopSegmentLoop()
        provider?.unbindAll()
        executor.shutdown()
        super.onDestroy()
    }

    fun setPreviewSurface(s: Surface?) {
        previewSurface = s
        preview?.setSurfaceProvider(if (s != null) { req ->
            req.provideSurface(s, executor) {}
        } else null)
    }

    fun startRecording() {
        if (isRooted() || recording != null) return
        sessionId = System.currentTimeMillis()
        seq = 0
        promoteForeground()
        startSegment()
    }

    fun stopRecording() {
        stopSegmentLoop()
        if (!isBound) stopSelf()
    }

    fun takePhoto() {
        val vc = videoCapture ?: return
        val ts = System.currentTimeMillis()
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${ts}_photo.jpg")
        val imageCapture = androidx.camera.core.ImageCapture.Builder().build()
        val useCases = listOfNotNull(
            if (previewSurface != null) preview else null,
            vc,
            imageCapture
        )
        provider?.unbindAll()
        try {
            provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        } catch (_: Exception) { return }
        imageCapture.takePicture(
            androidx.camera.core.ImageCapture.OutputFileOptions.Builder(raw).build(),
            executor,
            object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: androidx.camera.core.ImageCapture.OutputFileResults) {
                    if (raw.length() > 0) {
                        val loc = lastLocation()
                        seal(applicationContext, raw, ts, 0, ts, loc?.latitude, loc?.longitude)
                        enqueueUpload(applicationContext)
                    }
                    provider?.unbindAll()
                    bindCamera()
                }
                override fun onError(e: androidx.camera.core.ImageCaptureException) {
                    raw.delete()
                    provider?.unbindAll()
                    bindCamera()
                }
            }
        )
    }

    private fun startSegment() {
        val vc = videoCapture ?: return
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${sessionId}_${seq}.mp4")
        val opts = FileOutputOptions.Builder(raw)
            .setDurationLimitMillis(SEGMENT_MS)
            .build()
        recording = vc.output.prepareRecording(this, opts)
            .withAudioEnabled()
            .start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> onFinalize(event, raw)
                    else -> Unit
                }
            }
    }

    private fun onFinalize(event: VideoRecordEvent.Finalize, raw: File) {
        recording = null
        val keepGoing = sessionId != 0L
        val fileOk = raw.exists() && raw.length() > 0
        if (fileOk) {
            val loc = lastLocation()
            val curSeq = seq
            seq++
            seal(applicationContext, raw, sessionId, curSeq, System.currentTimeMillis(), loc?.latitude, loc?.longitude)
            enqueueUpload(applicationContext)
        } else {
            raw.delete()
        }
        if (keepGoing) startSegment() else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!isBound) stopSelf()
        }
    }

    private fun stopSegmentLoop() {
        val s = sessionId
        sessionId = 0L
        if (s != 0L) recording?.stop()
    }

    private fun bindCamera() {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        preview = Preview.Builder().build().also { p ->
            previewSurface?.let { s ->
                p.setSurfaceProvider { req -> req.provideSurface(s, executor) {} }
            }
        }
        try {
            provider?.unbindAll()
            provider?.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture
            )
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? = try {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    private fun promoteForeground() {
        val hasLoc = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        return Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.recording))
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CH_ID, getString(R.string.recording), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun sealLeftovers() {
        val raw = File(filesDir, "raw")
        if (!raw.exists()) return
        raw.listFiles()?.forEach { f ->
            if (f.length() > 0) {
                val ts = System.currentTimeMillis()
                seal(applicationContext, f, ts, 0, ts, null, null)
            } else {
                f.delete()
            }
        }
    }
}
