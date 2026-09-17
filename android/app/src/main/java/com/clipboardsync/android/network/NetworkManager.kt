package com.clipboardsync.android.network

import android.os.Handler
import android.os.Looper
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ConnectionStatus(val displayText: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    CONNECTED_RELAY("Connected (Cloudflare Relay)"),
    CONNECTED_LAN("Connected (Direct LAN)")
}

object NetworkManager {
    val deviceId = "android-${UUID.randomUUID().toString().substring(0, 8)}"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isIntentionalClose = false

    var currentStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set(value) {
            field = value
            statusListener?.invoke(value)
        }

    var statusListener: ((ConnectionStatus) -> Unit)? = null
    var onRemoteClipboardReceived: ((String) -> Unit)? = null

    fun start() {
        isIntentionalClose = false
        connectRelay()
    }

    fun stop() {
        isIntentionalClose = true
        disconnectRelay()
    }

    fun restart() {
        stop()
        start()
    }

    private fun connectRelay() {
        val roomId = CryptoManager.getRoomId() ?: run {
            currentStatus = ConnectionStatus.DISCONNECTED
            return
        }
        val relayBase = CryptoManager.getRelayUrl()
        val url = if (relayBase.contains("?")) {
            "$relayBase&room=$roomId"
        } else {
            "$relayBase?room=$roomId"
        }

        currentStatus = ConnectionStatus.CONNECTING
        webSocket?.cancel()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    currentStatus = ConnectionStatus.CONNECTED_RELAY
                }
                // Request latest catchup buffer upon connection
                val catchupJson = JSONObject().apply {
                    put("type", "GET_LATEST")
                    put("roomId", roomId)
                }
                webSocket.send(catchupJson.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (!isIntentionalClose) {
                        currentStatus = ConnectionStatus.DISCONNECTED
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (!isIntentionalClose) {
                        currentStatus = ConnectionStatus.DISCONNECTED
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    private fun disconnectRelay() {
        handler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        currentStatus = ConnectionStatus.DISCONNECTED
    }

    private fun scheduleReconnect() {
        handler.postDelayed({
            if (!isIntentionalClose && CryptoManager.isPaired()) {
                connectRelay()
            }
        }, 5000)
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "SYNC" || type == "LATEST_DATA") {
                val originDeviceId = json.optString("originDeviceId")
                if (originDeviceId == deviceId) {
                    return // Ignore our own echo
                }

                val ciphertext = json.optString("ciphertext")
                val iv = json.optString("iv")
                val hash = json.optString("hash")

                if (ciphertext.isEmpty() || iv.isEmpty() || hash.isEmpty()) return

                if (HashUtil.isHashKnown(hash)) {
                    return
                }

                val decrypted = CryptoManager.decrypt(ciphertext, iv) ?: return
                val (plainText, decryptedHash) = decrypted

                HashUtil.markHashAsHandled(decryptedHash)

                handler.post {
                    onRemoteClipboardReceived?.invoke(plainText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun broadcastClipboard(text: String) {
        val encrypted = CryptoManager.encrypt(text) ?: return
        val (ciphertext, iv, hash) = encrypted
        val roomId = CryptoManager.getRoomId() ?: return

        HashUtil.markHashAsHandled(hash)

        val json = JSONObject().apply {
            put("type", "SYNC")
            put("roomId", roomId)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("hash", hash)
            put("originDeviceId", deviceId)
            put("timestamp", System.currentTimeMillis())
        }

        webSocket?.send(json.toString())
    }
}
