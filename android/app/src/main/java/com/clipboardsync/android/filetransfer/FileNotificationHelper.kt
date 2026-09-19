package com.clipboardsync.android.filetransfer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.clipboardsync.android.ClipboardSyncApp
import com.clipboardsync.android.R
import com.clipboardsync.android.ui.FileReceiveActivity

object FileNotificationHelper {

    private const val NOTIFICATION_ID = 8821

    fun showIncomingFileNotification(
        context: Context,
        transferId: String,
        fileName: String,
        fileSize: Long,
        originDevice: String
    ) {
        val intent = Intent(context, FileReceiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("transferId", transferId)
            putExtra("fileName", fileName)
            putExtra("fileSize", fileSize)
            putExtra("originDevice", originDevice)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedSize = Formatter.formatFileSize(context, fileSize)

        val notification = NotificationCompat.Builder(context, ClipboardSyncApp.FILE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Incoming File: $fileName")
            .setContentText("$formattedSize from $originDevice • Tap to view")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL) // CATEGORY_CALL triggers heads-up popover
            .setFullScreenIntent(pendingIntent, true)       // Heads-up display across Android versions
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
    }
}
