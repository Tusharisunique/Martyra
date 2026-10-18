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
import android.view.Surface
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
    private var recording by mutableStateOf(false)
    private var elapsedMs by mutableLongStateOf(0L)
    private var recordStart = 0L
    private val ticker = Runnable { tick() }
    private var showProfile by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)
    private var pendingName = ""

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as CaptureService.Cap).service()
            attachPreviewSurface()
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cam = result[Manifest.permission.CAMERA] == true
        val mic = result[Manifest.permission.RECORD_AUDIO] == true
        if (cam && mic) {
            val prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_NAME, pendingName)
                .putString(KEY_USER_ID, prefs.getString(KEY_USER_ID, null) ?: UUID.randomUUID().toString())
                .apply()
            showProfile = false
            bindCapture()
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
        showProfile = prefs.getString(KEY_NAME, null) == null

        if (!showProfile) {
            bindCapture()
            enqueueUpload(this)
        }

        setContent {
            if (showProfile) {
                ProfileScreen(
                    onContinue = { name ->
                        pendingName = name
                        requestPerms()
                    },
                    permissionDenied = permissionDenied
                )
            } else {
                MainScreen(
                    rooted = isRooted(),
                    recording = recording,
                    elapsedMs = elapsedMs,
                    onBind = { pv -> attachPreviewTo(pv) },
                    onStartStop = {
                        val svc = service ?: return@MainScreen
                        if (recording) {
                            svc.stopRecording()
                            recording = false
                            stopTicker()
                        } else {
                            svc.startRecording()
                            recording = true
                            recordStart = SystemClock.elapsedRealtime()
                            startTicker()
                        }
                    },
                    onPhoto = { service?.takePhoto() }
                )
            }
        }
    }

    private var currentPreviewView: PreviewView? = null

    private fun attachPreviewTo(pv: PreviewView) {
        currentPreviewView = pv
        attachPreviewSurface()
    }

    private fun attachPreviewSurface() {
        val pv = currentPreviewView ?: return
        val svc = service ?: return
        pv.post {
            val s: Surface? = pv.holder?.surface
            svc.setPreviewSurface(s)
        }
    }

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    private fun bindCapture() {
        val i = Intent(this, CaptureService::class.java)
        startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        attachPreviewSurface()
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

    private fun startTicker() { window.decorView.postDelayed(ticker, 500) }

    private fun tick() {
        if (recording) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStart
            window.decorView.postDelayed(ticker, 500)
        }
    }

    private fun stopTicker() { window.decorView.removeCallbacks(ticker) }
}
