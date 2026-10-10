package com.ownerlink.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ownerlink.agent.MainActivity
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.R
import com.ownerlink.agent.util.NetworkMonitor

class ConnectionService : Service() {
    private var networkMonitor: NetworkMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val app = application as OwnerLinkApp
        networkMonitor = NetworkMonitor(this) {
            app.socket.retryNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val app = application as OwnerLinkApp
            app.store.serviceWanted = false
            app.store.remoteAccessConsent = false
            app.socket.disconnect(user = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as OwnerLinkApp
        if (!app.store.remoteAccessConsent || !app.store.serviceWanted || app.store.serverUrl.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            app.socket.reportServiceFailure(e)
            stopSelf()
            return START_NOT_STICKY
        }
        app.socket.connect()
        networkMonitor?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        networkMonitor?.stop()
        (application as OwnerLinkApp).socket.disconnect(user = false)
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Affiliate connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_privatchat_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, "Stop remote access", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ownerlink.agent.STOP"
        private const val CHANNEL = "ownerlink_remote"
        private const val NOTIF_ID = 42

        fun start(context: Context): Boolean {
            val app = context.applicationContext as OwnerLinkApp
            if (!app.store.remoteAccessConsent || !app.store.serviceWanted || app.store.serverUrl.isBlank()) return false
            val intent = Intent(context, ConnectionService::class.java)
            try {
                context.startForegroundService(intent)
                return true
            } catch (e: Exception) {
                android.util.Log.e("OwnerLink", "foreground service start failed", e)
                app.socket.reportServiceFailure(e)
                return false
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext as OwnerLinkApp
            app.store.serviceWanted = false
            app.store.remoteAccessConsent = false
            app.socket.disconnect(user = true)
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_STOP)
            context.stopService(intent)
        }


    }
}
