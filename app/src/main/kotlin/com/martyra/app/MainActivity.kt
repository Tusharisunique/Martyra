package com.martyra.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.UUID

private const val PREFS_PROFILE = "profile"
private const val KEY_NAME = "name"
private const val KEY_USER_ID = "user_id"

class MainActivity : ComponentActivity() {

    private var service: CaptureService? = null
    private var isRecording by mutableStateOf(false)
    private var elapsedMs by mutableLongStateOf(0L)
    private var recordStart = 0L
    private val ticker = Runnable { tick() }
    private var previewView: PreviewView? = null
    private var permissionDenied by mutableStateOf(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as CaptureService.Cap).service()
            previewView?.let { service?.setPreviewSurface(it.holder?.surface) }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val hasCamera = result[Manifest.permission.CAMERA] == true
        val hasMic = result[Manifest.permission.RECORD_AUDIO] == true
        if (hasCamera && hasMic) {
            saveProfile()
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enqueueUpload(this)

        val prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
        var showProfile by mutableStateOf(prefs.getString(KEY_NAME, null) == null)

        if (!showProfile) bindCapture()

        setContent {
            if (showProfile) {
                ProfileScreen(
                    onContinue = { _ -> requestPerms() },
                    permissionDenied = permissionDenied
                )
            } else {
                MainScreen(
                    rooted = isRooted(),
                    recording = isRecording,
                    elapsedMs = elapsedMs,
                    onBind = { pv ->
                        previewView = pv
                        service?.setPreviewSurface(pv.holder?.surface)
                    },
                    onStartStop = {
                        val svc = service ?: return@MainScreen
                        if (isRecording) {
                            svc.stopRecording()
                            isRecording = false
                            stopTicker()
                        } else {
                            svc.startRecording()
                            isRecording = true
                            recordStart = SystemClock.elapsedRealtime()
                            startTicker()
                        }
                    },
                    onPhoto = { service?.takePhoto() }
                )
            }

            if (showProfile.not()) {
                // already handled
            }
        }

        if (!showProfile) {
            // already bound above
        }

        fun onProfileSaved() {
            showProfile = false
            bindCapture()
        }
    }

    private fun requestPerms() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        required += Manifest.permission.ACCESS_FINE_LOCATION
        required += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(required.toTypedArray())
    }

    private fun saveProfile() {
        val prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
        if (prefs.getString(KEY_USER_ID, null) == null) {
            prefs.edit()
                .putString(KEY_USER_ID, UUID.randomUUID().toString())
                .apply()
        }
        recreate()
    }

    private fun bindCapture() {
        val i = Intent(this, CaptureService::class.java)
        startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        previewView?.let { service?.setPreviewSurface(it.holder?.surface) }
    }

    override fun onStop() {
        super.onStop()
        service?.setPreviewSurface(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        runCatching { unbindService(conn) }
    }

    private fun startTicker() {
        window.decorView.postDelayed(ticker, 500)
    }

    private fun tick() {
        if (isRecording) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStart
            window.decorView.postDelayed(ticker, 500)
        }
    }

    private fun stopTicker() {
        window.decorView.removeCallbacks(ticker)
    }
}
