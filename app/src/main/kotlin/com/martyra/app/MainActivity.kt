package com.martyra.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import java.lang.ref.WeakReference

private const val PREFS = "profile"

class MainActivity : ComponentActivity() {
    private var svcMessenger: Messenger? = null
    private var isBound = false
    private var recording by mutableStateOf(false)
    private var elapsedMs by mutableLongStateOf(0L)
    private var recordStartRealtime = 0L
    private var lastClickTime = 0L
    private val ticker = Runnable { tick() }
    private var showProfile by mutableStateOf(false)
    private var permDenied by mutableStateOf(false)
    private var pendingName = ""
    private var activeSurface: Surface? = null

    private class ClientHandler(activity: MainActivity) : Handler(Looper.getMainLooper()) {
        private val ref = WeakReference(activity)
        override fun handleMessage(msg: Message) {
            val act = ref.get() ?: return
            if (msg.what == MSG_STATE) {
                val isRec = msg.arg1 == 1
                val startTime = msg.data?.getLong("start_time", 0L) ?: 0L
                act.recording = isRec
                if (isRec) {
                    if (act.recordStartRealtime == 0L) {
                        act.recordStartRealtime = if (startTime > 0L) startTime else SystemClock.elapsedRealtime()
                    }
                    act.startTicker()
                } else {
                    act.recordStartRealtime = 0L
                    act.elapsedMs = 0L
                    act.stopTicker()
                }
            }
        }
    }

    private val clientMessenger = Messenger(ClientHandler(this))

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svcMessenger = Messenger(b)
            isBound = true
            sendToService(MSG_REGISTER, reply = clientMessenger)
            activeSurface?.let { sendSurfaceToService(it) }
        }

        override fun onServiceDisconnected(n: ComponentName) {
            svcMessenger = null
            isBound = false
        }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true && r[Manifest.permission.RECORD_AUDIO] == true) {
            if (showProfile) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("name", pendingName).apply()
                showProfile = false
            }
            permDenied = false
            if (!isBound) bindSvc()
            enqueueUpload(this)
            reqBatteryOpt()
        } else permDenied = true
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showProfile = getSharedPreferences(PREFS, MODE_PRIVATE).getString("name", null) == null
        if (!showProfile) {
            // Re-check permissions on every launch — handles "only this time" revocation
            val camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (camOk && micOk) {
                bindSvc()
                enqueueUpload(this)
                reqBatteryOpt()
            } else {
                // Silent re-request; showProfile remains false (no name entry needed)
                reqPerms()
            }
        }

        setContent {
            if (showProfile) {
                ProfileScreen(
                    onContinue = { name ->
                        pendingName = name
                        reqPerms()
                    },
                    permissionDenied = permDenied
                )
            } else {
                MainScreen(
                    rooted = isRooted(),
                    recording = recording,
                    elapsedMs = elapsedMs,
                    onSurfaceReady = { surface ->
                        activeSurface = surface
                        sendSurfaceToService(surface)
                    },
                    onSurfaceDestroyed = {
                        activeSurface = null
                        sendSurfaceToService(null)
                    },
                    onStartStop = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastClickTime >= 500L) {
                            lastClickTime = now
                            if (recording) {
                                recording = false
                                recordStartRealtime = 0L
                                elapsedMs = 0L
                                stopTicker()
                                sendToService(MSG_STOP_RECORDING)
                            } else {
                                sendToService(MSG_START_RECORDING)
                            }
                        }
                    },
                    onPhoto = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastClickTime >= 500L) {
                            lastClickTime = now
                            sendToService(MSG_TAKE_PHOTO)
                        }
                    }
                )
            }
        }
    }

    private fun reqBatteryOpt() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @SuppressLint("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun sendToService(what: Int, reply: Messenger? = null) {
        val target = svcMessenger ?: return
        try {
            target.send(Message.obtain(null, what).apply { reply?.let { replyTo = it } })
        } catch (_: RemoteException) { svcMessenger = null }
    }

    private fun sendSurfaceToService(surface: Surface?) {
        val target = svcMessenger ?: return
        try {
            target.send(Message.obtain(null, MSG_SET_SURFACE).apply {
                obj = surface
                data = Bundle().apply { putParcelable("surface", surface) }
            })
        } catch (_: RemoteException) { svcMessenger = null }
    }

    private fun reqPerms() {
        val base = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.launch(if (Build.VERSION.SDK_INT >= 33) base + Manifest.permission.POST_NOTIFICATIONS else base)
    }

    private fun bindSvc() {
        Intent(this, CaptureService::class.java).also {
            startService(it)
            bindService(it, conn, BIND_AUTO_CREATE)
        }
    }

    override fun onStart() {
        super.onStart()
        activeSurface?.let { sendSurfaceToService(it) }
    }

    override fun onStop() {
        super.onStop()
        sendSurfaceToService(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        if (isBound) {
            sendToService(MSG_UNREGISTER)
            runCatching { unbindService(conn) }
            isBound = false
        }
    }

    private fun startTicker() {
        stopTicker()
        window.decorView.postDelayed(ticker, 500)
    }

    private fun tick() {
        if (recording) {
            val now = SystemClock.elapsedRealtime()
            elapsedMs = if (recordStartRealtime > 0L) now - recordStartRealtime else 0L
            window.decorView.postDelayed(ticker, 500)
        }
    }

    private fun stopTicker() {
        window.decorView.removeCallbacks(ticker)
    }
}
