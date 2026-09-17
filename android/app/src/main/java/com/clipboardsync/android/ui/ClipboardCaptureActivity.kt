package com.clipboardsync.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import com.clipboardsync.android.network.NetworkManager

/**
 * Invisible 1-millisecond trampoline activity to guarantee clipboard access
 * on Android 10, 11, 12, 13, 14, and 15 by holding momentary window focus.
 */
class ClipboardCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (CryptoManager.isPaired()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    val text = item.text?.toString() ?: item.coerceToText(this).toString()
                    if (text.isNotEmpty()) {
                        val hash = HashUtil.sha256(text)
                        if (!HashUtil.isHashKnown(hash)) {
                            HashUtil.markHashAsHandled(hash)
                            Log.d("ClipboardCapture", "Captured clipboard text via focus: ${text.take(30)}... Broadcasting to Mac!")
                            NetworkManager.broadcastClipboard(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ClipboardCapture", "Error reading clipboard in capture activity", e)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
