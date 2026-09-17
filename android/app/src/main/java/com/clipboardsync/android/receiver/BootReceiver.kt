package com.clipboardsync.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.service.SyncForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            
            CryptoManager.init(context)
            if (CryptoManager.isPaired()) {
                SyncForegroundService.startService(context)
            }
        }
    }
}
