package com.metastream.quest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service that keeps the [MetaStreamServer] alive while the app runs,
 * even if the 2D panel loses focus in the headset. Holds a high-perf Wi-Fi lock
 * to maximise transfer throughput.
 */
class FileServerService : Service() {

    companion object {
        const val PORT = 8080
        private const val CHANNEL_ID = "metastream_server"
        private const val NOTIF_ID = 1

        fun storageDir(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "MetaStream").apply { mkdirs() }
        }
    }

    private var server: MetaStreamServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        try {
            val dir = storageDir(this)
            server = MetaStreamServer(PORT, dir, assets, Build.MODEL ?: "Meta Quest").apply {
                start(NanoTimeout, false)
            }
            acquireWifiLock()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MetaStream:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundCompat() {
        val launch = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Socket read timeout (ms) — generous so large transfers are not cut off. */
private const val NanoTimeout = 60_000
