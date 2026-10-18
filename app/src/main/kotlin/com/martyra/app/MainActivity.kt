package com.martyra.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.compose.runtime.*
import java.util.UUID

private const val PREFS = "profile"

class MainActivity : ComponentActivity() {
    private var svc: CaptureService? = null
    private var recording by mutableStateOf(false)
    private var elapsedMs by mutableLongStateOf(0L)
    private var recordStart = 0L
    private val ticker = Runnable { tick() }
    private var showProfile by mutableStateOf(false)
    private var permDenied by mutableStateOf(false)
    private var pendingName = ""
    private var previewView: PreviewView? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as CaptureService.Cap).svc()
            previewView?.let { svc?.setSurfaceProvider(it.surfaceProvider) }
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true && r[Manifest.permission.RECORD_AUDIO] == true) {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            p.edit().putString("name", pendingName)
                .putString("user_id", p.getString("user_id", null) ?: UUID.randomUUID().toString())
                .apply()
            showProfile = false; bindSvc(); enqueueUpload(this)
        } else permDenied = true
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s); enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showProfile = getSharedPreferences(PREFS, MODE_PRIVATE).getString("name", null) == null
        if (!showProfile) { bindSvc(); enqueueUpload(this) }
        setContent {
            if (showProfile) {
                ProfileScreen(onContinue = { name -> pendingName = name; reqPerms() }, permissionDenied = permDenied)
            } else {
                MainScreen(rooted = isRooted(), recording = recording, elapsedMs = elapsedMs,
                    onBind = { pv -> previewView = pv; svc?.setSurfaceProvider(pv.surfaceProvider) },
                    onStartStop = {
                        val sv = svc ?: return@MainScreen
                        if (recording) { sv.stopRecording(); recording = false; stopTicker() }
                        else { sv.startRecording(); recording = true; recordStart = SystemClock.elapsedRealtime(); startTicker() }
                    },
                    onPhoto = { svc?.takePhoto() })
            }
        }
    }

    private fun reqPerms() {
        val l = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(l.toTypedArray())
    }

    private fun bindSvc() {
        val i = Intent(this, CaptureService::class.java)
        startService(i); bindService(i, conn, BIND_AUTO_CREATE)
    }

    override fun onStart() { super.onStart(); previewView?.let { svc?.setSurfaceProvider(it.surfaceProvider) } }
    override fun onStop() { super.onStop(); svc?.setSurfaceProvider(null) }
    override fun onDestroy() { super.onDestroy(); stopTicker(); runCatching { unbindService(conn) } }

    private fun startTicker() { window.decorView.postDelayed(ticker, 500) }
    private fun tick() { if (recording) { elapsedMs = SystemClock.elapsedRealtime() - recordStart; window.decorView.postDelayed(ticker, 500) } }
    private fun stopTicker() { window.decorView.removeCallbacks(ticker) }
}
