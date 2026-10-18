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
import android.os.*
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.Executors

const val MSG_REGISTER = 1
const val MSG_UNREGISTER = 2
const val MSG_SET_SURFACE = 3
const val MSG_START_RECORDING = 4
const val MSG_STOP_RECORDING = 5
const val MSG_TAKE_PHOTO = 6
const val MSG_STATE = 7

private const val SEGMENT_MS = 3_000L
private const val NOTIF_ID = 1
private const val CH_ID = "martyra_capture"

fun isRooted(): Boolean = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
    .any { File(it).exists() } || Build.TAGS?.contains("test-keys") == true

class CaptureService : LifecycleService() {
    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var vc: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    private var sessionId = 0L
    private var lastSessionId = 0L
    private var seq = 0
    private var recordStartRealtime = 0L

    private var clientMessenger: Messenger? = null
    private var currentSurface: Surface? = null
    private var pendingSurfaceRequest: SurfaceRequest? = null

    private class IncomingHandler(service: CaptureService, looper: Looper) : Handler(looper) {
        private val ref = java.lang.ref.WeakReference(service)
        override fun handleMessage(msg: Message) {
            val svc = ref.get() ?: return
            when (msg.what) {
                MSG_REGISTER -> {
                    svc.clientMessenger = msg.replyTo
                    svc.notifyState()
                }
                MSG_UNREGISTER -> {
                    svc.clientMessenger = null
                    if (svc.recording == null) svc.stopSelf()
                }
                MSG_SET_SURFACE -> {
                    @Suppress("DEPRECATION")
                    val s = (msg.obj as? Surface)
                        ?: msg.data?.getParcelable("surface")
                    svc.updateSurface(s)
                }
                MSG_START_RECORDING -> svc.startRecording()
                MSG_STOP_RECORDING -> svc.stopRecording()
                MSG_TAKE_PHOTO -> svc.takePhoto()
            }
        }
    }

    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(this, Looper.getMainLooper()))
        sealLeftovers()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CH_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindCamera()
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        clientMessenger = null
        updateSurface(null)
        if (recording == null) stopSelf()
        return false
    }

    override fun onDestroy() {
        stopLoop()
        updateSurface(null)
        provider?.unbindAll()
        exec.shutdown()
        super.onDestroy()
    }

    private fun updateSurface(s: Surface?) {
        currentSurface = s
        if (s != null && s.isValid) {
            pendingSurfaceRequest?.let { req ->
                req.provideSurface(s, ContextCompat.getMainExecutor(this)) { }
                pendingSurfaceRequest = null
            }
        } else {
            pendingSurfaceRequest?.willNotProvideSurface()
            pendingSurfaceRequest = null
        }
    }

    private fun notifyState() {
        val client = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, MSG_STATE).apply {
                arg1 = if (sessionId != 0L) 1 else 0
                data = Bundle().apply { putLong("start_time", recordStartRealtime) }
            }
            client.send(msg)
        } catch (_: RemoteException) { clientMessenger = null }
    }

    fun startRecording() {
        if (isRooted() || sessionId != 0L || recording != null || vc == null) return

        sessionId = System.currentTimeMillis()
        seq = 0
        recordStartRealtime = SystemClock.elapsedRealtime()

        val hasLoc = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val n = buildRecordingNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            var fType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasLoc) fType = fType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, n, fType)
        } else {
            startForeground(NOTIF_ID, n)
        }
        startSegment()
        notifyState()
    }

    fun stopRecording() {
        if (sessionId == 0L) return
        stopLoop()
        // Remove the recording notification IMMEDIATELY — not after the last segment finalizes
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyState()
        // Service will call stopSelf() once the last in-flight recording finishes in onFinalize()
    }

    @SuppressLint("MissingPermission")
    fun takePhoto() {
        val img = imageCapture ?: return
        val ts = System.currentTimeMillis()
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${ts}_photo.jpg")
        img.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            exec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    if (raw.length() > 0) {
                        val loc = lastLocation()
                        seal(applicationContext, raw, ts, 0, ts, loc?.latitude, loc?.longitude)
                        notifySealedToMainProcess()
                    } else raw.delete()
                }
                override fun onError(e: ImageCaptureException) { raw.delete() }
            }
        )
    }

    private fun notifySealedToMainProcess() {
        sendBroadcast(Intent(ACTION_SEALED).apply {
            setClassName(packageName, UploadReceiver::class.java.name)
        })
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (sessionId == 0L) return
        val videoCapture = vc ?: return
        val raw = File(File(filesDir, "raw").also { it.mkdirs() }, "${sessionId}_$seq.mp4")
        try {
            recording = videoCapture.output
                .prepareRecording(
                    this,
                    FileOutputOptions.Builder(raw).setDurationLimitMillis(SEGMENT_MS).build()
                )
                .withAudioEnabled()
                .start(exec) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val ok = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                                 event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        if (ok && raw.exists() && raw.length() > 0) {
                            onFinalize(raw)
                        } else {
                            raw.delete()
                            recording = null
                            if (sessionId != 0L) ContextCompat.getMainExecutor(this).execute { startSegment() }
                            else {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                notifyState()
                            }
                        }
                    }
                }
        } catch (_: Exception) {
            raw.delete()
        }
    }

    private fun onFinalize(raw: File) {
        recording = null
        val sid = sessionId
        val going = sid != 0L
        val loc = lastLocation()
        val s = seq++
        val finalSid = if (sid != 0L) sid else lastSessionId
        seal(applicationContext, raw, finalSid, s, System.currentTimeMillis(), loc?.latitude, loc?.longitude)
        notifySealedToMainProcess()
        if (going) {
            ContextCompat.getMainExecutor(this).execute { startSegment() }
        } else {
            // Last segment done; service can die if nothing else holds it
            if (clientMessenger == null) stopSelf()
        }
    }

    private fun buildRecordingNotification(): Notification =
        Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording")
            .setOngoing(true)
            .build()

    private fun stopLoop() {
        if (sessionId != 0L) lastSessionId = sessionId
        sessionId = 0L
        recordStartRealtime = 0L
        runCatching { recording?.stop() }
    }

    private fun bindCamera() {
        val p = provider ?: return
        val res = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val rec = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.FHD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()
        vc = VideoCapture.withOutput(rec)
        preview = Preview.Builder()
            .setResolutionSelector(res)
            .build()
            .also { pr ->
                pr.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                    val s = currentSurface
                    if (s != null && s.isValid) {
                        request.provideSurface(s, ContextCompat.getMainExecutor(this)) { }
                    } else {
                        pendingSurfaceRequest = request
                    }
                }
            }
        imageCapture = ImageCapture.Builder()
            .setResolutionSelector(res)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc, imageCapture)
        } catch (_: Exception) {
            imageCapture = null
            try {
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
            } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? = runCatching {
        val lm = getSystemService(LocationManager::class.java) ?: return null
        lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun sealLeftovers() {
        var sealed = false
        File(filesDir, "raw").listFiles()?.forEach { f ->
            if (f.length() > 0) {
                val t = System.currentTimeMillis()
                seal(applicationContext, f, t, 0, t, null, null)
                sealed = true
            } else f.delete()
        }
        if (sealed) notifySealedToMainProcess()
    }
}
