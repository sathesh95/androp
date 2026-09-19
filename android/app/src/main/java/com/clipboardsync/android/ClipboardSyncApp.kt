package com.clipboardsync.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.clipboardsync.android.crypto.CryptoManager

class ClipboardSyncApp : Application() {

    companion object {
        const val CHANNEL_ID = "clipboard_sync_channel"
        const val FILE_CHANNEL_ID = "file_transfer_channel"
    }

    override fun onCreate() {
        super.onCreate()
        CryptoManager.init(this)
        com.clipboardsync.android.network.NetworkManager.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps clipboard synchronization active in the background"
                setShowBadge(false)
            }

            val fileChannel = NotificationChannel(
                FILE_CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when an incoming file transfer arrives from Mac"
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(fileChannel)
        }
    }
}
