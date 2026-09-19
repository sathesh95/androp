package com.clipboardsync.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.service.ClipboardAccessibilityService

/**
 * Invisible, zero-UI Activity that acquires momentary window focus on Android 10+
 * to read/write system clipboard without permission denials or keyboard flickering.
 */
class ClipboardGhostActivity : Activity() {

    private var hasReadClipboard = false
    private var hasFinished = false
    private val safetyHandler = Handler(Looper.getMainLooper())

    private val safetyTimeout = Runnable {
        if (!hasFinished) {
            finishSafely()
        }
    }

    companion object {
        private const val TAG = "ClipboardGhost"
        private const val SAFETY_TIMEOUT_MS = 1500L

        const val EXTRA_CLIP_TEXT = "extra_clip_text"
        const val ACTION_READ = "action_read"
        const val ACTION_WRITE = "action_write"

        fun readFromClipboard(context: Context) {
            try {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_READ
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch ghost activity for read", e)
            }
        }

        fun copyToClipboard(context: Context, text: String) {
            try {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_WRITE
                    putExtra(EXTRA_CLIP_TEXT, text)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch ghost activity for write", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prevent soft keyboard flickering
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)

        super.onCreate(savedInstanceState)
        disableOpenAnimation()

        safetyHandler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasReadClipboard = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasFinished) {
            when (intent.action) {
                ACTION_READ -> {
                    if (!hasReadClipboard) {
                        hasReadClipboard = true
                        readClipboardAndFinish()
                    }
                }
                ACTION_WRITE -> {
                    val text = intent.getStringExtra(EXTRA_CLIP_TEXT)
                    if (!text.isNullOrEmpty()) {
                        writeTextToClipboard(text)
                    }
                    finishSafely()
                }
                else -> {
                    finishSafely()
                }
            }
        }
    }

    private fun readClipboardAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                val text = item.text?.toString() ?: item.coerceToText(this).toString()
                if (text.isNotEmpty()) {
                    val hash = HashUtil.sha256(text)
                    if (!HashUtil.consumeRemoteInjectedIfPresent(hash)) {
                        Log.d(TAG, "Successfully read clipboard: ${text.take(30)}... Broadcasting to Mac!")
                        NetworkManager.broadcastClipboard(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard in onWindowFocusChanged", e)
        } finally {
            finishSafely()
        }
    }

    private fun writeTextToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val hash = HashUtil.sha256(text)
            HashUtil.markRemoteInjected(hash)
            clipboard?.setPrimaryClip(ClipData.newPlainText("ClipboardSync", text))
            Log.d(TAG, "Successfully wrote to Android clipboard via ghost activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
        }
    }

    private fun finishSafely() {
        if (!hasFinished) {
            hasFinished = true
            safetyHandler.removeCallbacks(safetyTimeout)
            finishAndRemoveTask()
            disableCloseAnimation()
        }
    }

    override fun finish() {
        super.finish()
        disableCloseAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        safetyHandler.removeCallbacks(safetyTimeout)
    }

    private fun disableOpenAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun disableCloseAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
