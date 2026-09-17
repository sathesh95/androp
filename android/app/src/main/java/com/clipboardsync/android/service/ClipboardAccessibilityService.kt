package com.clipboardsync.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import com.clipboardsync.android.network.NetworkManager

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ClipboardAccessibilityService? = null
            private set

        fun setClipboardContent(context: Context, text: String) {
            val hash = HashUtil.sha256(text)
            HashUtil.markHashAsHandled(hash)

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ClipboardSync", text)
            clipboard?.setPrimaryClip(clip)
        }
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastLocalHash: String = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkAndSyncClipboard()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // Wire incoming network clipboard updates
        NetworkManager.onRemoteClipboardReceived = { remoteText ->
            setClipboardContent(this, remoteText)
        }

        // Start Foreground Service if not already running
        SyncForegroundService.startService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility event triggered on user interaction across any app
        // Check clipboard in background without needing app focus
        checkAndSyncClipboard()
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        instance = null
    }

    private fun checkAndSyncClipboard() {
        if (!CryptoManager.isPaired()) return

        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    val hash = HashUtil.sha256(text)
                    if (hash != lastLocalHash && !HashUtil.isHashKnown(hash)) {
                        lastLocalHash = hash
                        HashUtil.markHashAsHandled(hash)
                        NetworkManager.broadcastClipboard(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
