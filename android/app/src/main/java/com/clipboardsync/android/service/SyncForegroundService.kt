package com.clipboardsync.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clipboardsync.android.ClipboardSyncApp
import com.clipboardsync.android.R
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.ui.MainActivity

class SyncForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (CryptoManager.isPaired()) {
            NetworkManager.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (CryptoManager.isPaired()) {
            NetworkManager.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkManager.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ClipboardSyncApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(getString(R.string.sync_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
