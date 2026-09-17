package com.clipboardsync.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import com.clipboardsync.android.network.NetworkManager

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardService"
        var instance: ClipboardAccessibilityService? = null
            private set

        fun setClipboardContent(context: Context, text: String) {
            val hash = HashUtil.sha256(text)
            HashUtil.markHashAsHandled(hash)

            Handler(Looper.getMainLooper()).post {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("ClipboardSync", text)
                    clipboard?.setPrimaryClip(clip)
                    Log.d(TAG, "Successfully wrote remote text to Android clipboard")
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to clipboard", e)
                }
            }
        }
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastLocalHash: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d(TAG, "Primary clip changed listener triggered")
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
        Log.d(TAG, "Accessibility Service connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Immediate check
        checkAndSyncClipboard()

        // Debounced delayed checks because Android apps write to clipboard a few milliseconds after click/copy
        mainHandler.postDelayed({ checkAndSyncClipboard() }, 150)
        mainHandler.postDelayed({ checkAndSyncClipboard() }, 350)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        instance = null
    }

    private fun checkAndSyncClipboard() {
        if (!CryptoManager.isPaired()) return

        mainHandler.post {
            try {
                val clip = clipboardManager?.primaryClip ?: return@post
                if (clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    val text = item.text?.toString() ?: item.coerceToText(this).toString()
                    if (text.isNotEmpty()) {
                        val hash = HashUtil.sha256(text)
                        if (hash != lastLocalHash && !HashUtil.isHashKnown(hash)) {
                            lastLocalHash = hash
                            HashUtil.markHashAsHandled(hash)
                            Log.d(TAG, "New local copy detected on Android: ${text.take(30)}... Broadcasting to Mac!")
                            NetworkManager.broadcastClipboard(text)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking clipboard", e)
            }
        }
    }
}
