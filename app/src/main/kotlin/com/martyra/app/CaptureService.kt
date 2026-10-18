package com.martyra.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.Executors

private const val SEGMENT_MS = 3_000L
private const val NOTIF_ID = 1
private const val CH_ID = "martyra_capture"

fun isRooted() = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
    .any { File(it).exists() } || Build.TAGS?.contains("test-keys") == true

class CaptureService : LifecycleService() {
    inner class Cap : Binder() { fun svc() = this@CaptureService }

    private val binder = Cap()
    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var vc: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    private var sessionId = 0L; private var seq = 0
    private var isBound = false

    override fun onCreate() {
        super.onCreate()
        sealLeftovers()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        ProcessCameraProvider.getInstance(this).addListener({
            provider = ProcessCameraProvider.getInstance(this).get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onBind(i: Intent): IBinder { super.onBind(i); isBound = true; return binder }
    override fun onUnbind(i: Intent): Boolean { isBound = false; setSurfaceProvider(null); if (recording == null) stopSelf(); return false }
    override fun onDestroy() { stopLoop(); provider?.unbindAll(); exec.shutdown(); super.onDestroy() }

    fun setSurfaceProvider(p: Preview.SurfaceProvider?) = preview?.setSurfaceProvider(p)

    fun startRecording() {
        if (isRooted() || recording != null) return
        sessionId = System.currentTimeMillis(); seq = 0
        val hasLoc = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val n = Notification.Builder(this, CH_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("Recording").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        else startForeground(NOTIF_ID, n)
        startSegment()
    }

    fun stopRecording() { stopLoop(); if (!isBound) stopSelf() }

    fun takePhoto() {
        val ts = System.currentTimeMillis()
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${ts}_photo.jpg")
        imageCapture?.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), exec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { if (raw.length() > 0) { seal(applicationContext, raw, ts, 0, ts, lastLocation()?.latitude, lastLocation()?.longitude); enqueueUpload(applicationContext) } else raw.delete() }
            override fun onError(e: ImageCaptureException) { raw.delete() }
        })
    }

    private fun startSegment() {
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${sessionId}_$seq.mp4")
        recording = (vc ?: return).output.prepareRecording(this, FileOutputOptions.Builder(raw).setDurationLimitMillis(SEGMENT_MS).build())
            .withAudioEnabled().start(exec) { if (it is VideoRecordEvent.Finalize) onFinalize(raw) }
    }

    private fun onFinalize(raw: File) {
        recording = null
        val going = sessionId != 0L
        if (raw.exists() && raw.length() > 0) { val loc = lastLocation(); val s = seq++; seal(applicationContext, raw, sessionId, s, System.currentTimeMillis(), loc?.latitude, loc?.longitude); enqueueUpload(applicationContext) } else raw.delete()
        if (going) startSegment() else { stopForeground(STOP_FOREGROUND_REMOVE); if (!isBound) stopSelf() }
    }

    private fun stopLoop() { val s = sessionId; sessionId = 0L; if (s != 0L) recording?.stop() }

    private fun bindCamera() {
        val rec = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD))).build()
        vc = VideoCapture.withOutput(rec); preview = Preview.Builder().build(); imageCapture = ImageCapture.Builder().build()
        runCatching { provider?.unbindAll(); provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc, imageCapture) }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? = runCatching {
        val lm = getSystemService(LocationManager::class.java)
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }.getOrNull()

    private fun sealLeftovers() = File(filesDir, "raw").listFiles()?.forEach { f ->
        if (f.length() > 0) { val t = System.currentTimeMillis(); seal(applicationContext, f, t, 0, t, null, null) } else f.delete()
    }
}
