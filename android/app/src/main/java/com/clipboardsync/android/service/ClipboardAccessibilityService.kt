package com.clipboardsync.android.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clipboardsync.android.R
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.ui.ClipboardGhostActivity
import java.util.concurrent.Executors

class ClipboardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var lastEventTime = 0L
    private var lastGhostLaunchTime = 0L

    private var copyWordsCache = emptySet<String>()
    private var copiedWordsCache = emptySet<String>()

    companion object {
        private const val TAG = "ClipboardService"
        private const val EVENT_DEBOUNCE_MS = 600L
        private const val GHOST_LAUNCH_DEBOUNCE_MS = 500L

        var instance: ClipboardAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            copyWordsCache = resources.getStringArray(R.array.copy_words).toSet()
            copiedWordsCache = resources.getStringArray(R.array.copied_words).toSet()
        } catch (e: Exception) {
            copyWordsCache = setOf("copy", "कॉपी")
            copiedWordsCache = setOf("copied", "कॉपी किया")
        }

        // Wire incoming network clipboard updates from Mac
        NetworkManager.onRemoteClipboardReceived = { remoteText ->
            ClipboardGhostActivity.copyToClipboard(this, remoteText)
        }

        // Start Foreground Service if not already running
        SyncForegroundService.startService(this)
        Log.d(TAG, "Accessibility Service connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CryptoManager.isPaired()) return
        if (event.packageName == packageName) return

        try {
            val eventTime = event.eventTime
            if (eventTime - lastEventTime < EVENT_DEBOUNCE_MS) return

            when (event.eventType) {
                // Strategy 1: "Copied" confirmation toast
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (event.className == "android.widget.Toast") {
                        val text = event.text.joinToString(" ")
                        if (containsCopiedWord(text)) {
                            lastEventTime = eventTime
                            triggerClipboardGhost("Toast ($text)")
                        }
                    }
                }

                // Strategy 2: Clicks and Window State Changes
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val contentDesc = event.contentDescription?.toString() ?: ""
                    val eventText = event.text.joinToString(" ")
                    val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    var triggerType: String? = null

                    // Action ID check: ACTION_COPY is language independent
                    val source = event.source
                    if (isClick && source?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_COPY } == true) {
                        triggerType = "ACTION_COPY"
                    }

                    // Word heuristics check
                    if (triggerType == null) {
                        val hasCopy = containsCopyWord(contentDesc) || containsCopyWord(eventText)
                        val hasCopied = containsCopiedWord(contentDesc) || containsCopiedWord(eventText)
                        if (isClick && hasCopy) triggerType = "Click (Copy Button)"
                        else if (hasCopied) triggerType = "Passive (Copied)"
                    }

                    // Deep Node Search as fallback
                    if (triggerType == null && source != null) {
                        backgroundExecutor.execute {
                            try {
                                if (dfsFindCopy(source, isClick = isClick)) {
                                    val finalType = if (isClick) "Deep Search (Click)" else "Deep Search (Window)"
                                    handler.post {
                                        lastEventTime = eventTime
                                        triggerClipboardGhost(finalType)
                                    }
                                }
                            } finally {
                                if (Build.VERSION.SDK_INT < 34) {
                                    @Suppress("DEPRECATION")
                                    source.recycle()
                                }
                            }
                        }
                    } else {
                        if (Build.VERSION.SDK_INT < 34) {
                            @Suppress("DEPRECATION")
                            source?.recycle()
                        }
                        if (triggerType != null) {
                            lastEventTime = eventTime
                            triggerClipboardGhost(triggerType)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    private fun triggerClipboardGhost(trigger: String) {
        val now = System.currentTimeMillis()
        if (now - lastGhostLaunchTime < GHOST_LAUNCH_DEBOUNCE_MS) return
        lastGhostLaunchTime = now
        Log.d(TAG, "Copy action detected by [$trigger]. Launching ClipboardGhostActivity to read...")
        ClipboardGhostActivity.readFromClipboard(this)
    }

    private fun dfsFindCopy(node: AccessibilityNodeInfo?, depth: Int = 0, isClick: Boolean = true): Boolean {
        if (node == null || depth > 5 || !node.isVisibleToUser) return false

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val combined = "$text $contentDesc $viewId".trim()
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY }) return true
        if (isClick && containsCopyWord(combined)) return true
        if (!isClick && (containsCopiedWord(combined) || viewId.contains("copy", ignoreCase = true))) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = dfsFindCopy(child, depth + 1, isClick)
            if (Build.VERSION.SDK_INT < 34) {
                @Suppress("DEPRECATION")
                child.recycle()
            }
            if (found) return true
        }
        return false
    }

    private fun containsCopyWord(text: String): Boolean {
        val lower = text.lowercase()
        return copyWordsCache.any { lower.contains(it.lowercase()) }
    }

    private fun containsCopiedWord(text: String): Boolean {
        val lower = text.lowercase()
        return copiedWordsCache.any { lower.contains(it.lowercase()) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
