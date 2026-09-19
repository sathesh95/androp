package com.clipboardsync.android.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.crypto.HashUtil
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
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
    private val networkExecutor = Executors.newCachedThreadPool()
    private var isIntentionalClose = false

    // LAN / NSD Properties
    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false
    private val lanSockets = Collections.synchronizedList(mutableListOf<Socket>())
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    var currentStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set(value) {
            field = value
            statusListener?.invoke(value)
        }

    var statusListener: ((ConnectionStatus) -> Unit)? = null
    var onRemoteClipboardReceived: ((String) -> Unit)? = null

    fun init(context: Context) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("ClipboardSyncMulticast")?.apply {
            setReferenceCounted(true)
        }
    }

    fun start() {
        isIntentionalClose = false
        startLanServer()
        startNsd()
        connectRelay()
    }

    fun stop() {
        isIntentionalClose = true
        disconnectRelay()
        stopNsd()
        stopLanServer()
    }

    fun restart() {
        stop()
        start()
    }

    // MARK: - Local LAN TCP Server & NSD

    private fun startLanServer() {
        if (isServerRunning) return
        networkExecutor.execute {
            try {
                serverSocket = ServerSocket(0)
                isServerRunning = true
                val port = serverSocket?.localPort ?: return@execute

                handler.post {
                    registerNsdService(port)
                }

                while (isServerRunning && !serverSocket!!.isClosed) {
                    val socket = serverSocket?.accept() ?: break
                    lanSockets.add(socket)
                    updateLanStatus()
                    listenToLanSocket(socket)
                }
            } catch (e: Exception) {
                // Server socket closed
            }
        }
    }

    private fun stopLanServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null

        synchronized(lanSockets) {
            for (s in lanSockets) {
                try { s.close() } catch (e: Exception) {}
            }
            lanSockets.clear()
        }
    }

    private fun startNsd() {
        try {
            multicastLock?.acquire()
        } catch (e: Exception) {}

        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val serviceName = serviceInfo?.serviceName ?: return
                if (serviceName.startsWith("ClipboardSync-mac") || (serviceName.startsWith("ClipboardSync-") && !serviceName.contains(deviceId))) {
                    try {
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                                val host = resolvedInfo?.host ?: return
                                val port = resolvedInfo.port
                                connectToLanPeer(host.hostAddress ?: "", port)
                            }
                        })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        try {
            nsdManager?.discoverServices("_clipboardsync._tcp.", NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopNsd() {
        try {
            if (nsdDiscoveryListener != null) {
                nsdManager?.stopServiceDiscovery(nsdDiscoveryListener)
                nsdDiscoveryListener = null
            }
            if (nsdRegistrationListener != null) {
                nsdManager?.unregisterService(nsdRegistrationListener)
                nsdRegistrationListener = null
            }
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
    }

    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ClipboardSync-$deviceId"
            serviceType = "_clipboardsync._tcp."
            setPort(port)
        }

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo?) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun connectToLanPeer(host: String, port: Int) {
        networkExecutor.execute {
            try {
                val socket = Socket(host, port)
                lanSockets.add(socket)
                updateLanStatus()
                listenToLanSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun listenToLanSocket(socket: Socket) {
        networkExecutor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        handleIncomingMessage(line)
                    }
                }
            } catch (e: Exception) {
                // Connection dropped
            } finally {
                lanSockets.remove(socket)
                try { socket.close() } catch (e: Exception) {}
                updateLanStatus()
            }
        }
    }

    private fun updateLanStatus() {
        handler.post {
            if (lanSockets.isNotEmpty()) {
                currentStatus = ConnectionStatus.CONNECTED_LAN
            } else if (webSocket != null && currentStatus == ConnectionStatus.CONNECTED_LAN) {
                currentStatus = ConnectionStatus.DISCONNECTED
            }
        }
    }

    // MARK: - Cloudflare WebSocket Relay

    private fun connectRelay() {
        val roomId = CryptoManager.getRoomId() ?: run {
            currentStatus = ConnectionStatus.DISCONNECTED
            return
        }
        val relayBase = CryptoManager.getRelayUrl()
        if (relayBase.contains("your-subdomain")) {
            // Placeholder URL, operate in LAN direct mode
            if (lanSockets.isEmpty()) {
                currentStatus = ConnectionStatus.DISCONNECTED
            }
            return
        }

        val url = if (relayBase.contains("?")) {
            "$relayBase&room=$roomId"
        } else {
            "$relayBase?room=$roomId"
        }

        if (lanSockets.isEmpty()) {
            currentStatus = ConnectionStatus.CONNECTING
        }
        webSocket?.cancel()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (lanSockets.isEmpty()) {
                        currentStatus = ConnectionStatus.CONNECTED_RELAY
                    }
                }
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
                    if (lanSockets.isEmpty() && !isIntentionalClose) {
                        currentStatus = ConnectionStatus.DISCONNECTED
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (!isIntentionalClose && lanSockets.isEmpty()) {
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
        if (lanSockets.isEmpty()) {
            currentStatus = ConnectionStatus.DISCONNECTED
        }
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

            if (type == "CONFIG_UPDATE") {
                val newRelayUrl = json.optString("relayUrl")
                if (newRelayUrl.isNotEmpty() && !newRelayUrl.contains("your-subdomain")) {
                    android.util.Log.d("NetworkManager", "Received updated Cloudflare relay URL: $newRelayUrl")
                    CryptoManager.updateRelayUrl(newRelayUrl)
                    restart()
                }
                return
            }

            if (type == "SYNC" || type == "LATEST_DATA") {
                val originDeviceId = json.optString("originDeviceId")
                if (originDeviceId == deviceId) {
                    return // Ignore our own echo
                }

                val ciphertext = json.optString("ciphertext")
                val iv = json.optString("iv")
                val hash = json.optString("hash")

                if (ciphertext.isEmpty() || iv.isEmpty() || hash.isEmpty()) return

                val decrypted = CryptoManager.decrypt(ciphertext, iv) ?: return
                val (plainText, decryptedHash) = decrypted

                HashUtil.markRemoteInjected(decryptedHash)

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

        val json = JSONObject().apply {
            put("type", "SYNC")
            put("roomId", roomId)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("hash", hash)
            put("originDeviceId", deviceId)
            put("timestamp", System.currentTimeMillis())
        }

        val jsonString = json.toString()
        val payloadBytes = (jsonString + "\n").toByteArray(Charsets.UTF_8)

        // 1. Send via Cloudflare WebSocket (if connected)
        webSocket?.send(jsonString)

        // 2. Send via Direct LAN Sockets (Immediate byte write + flush)
        networkExecutor.execute {
            synchronized(lanSockets) {
                for (socket in lanSockets) {
                    try {
                        val outputStream = socket.getOutputStream()
                        outputStream.write(payloadBytes)
                        outputStream.flush()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun broadcastOtp(code: String, sender: String, originalText: String) {
        val payloadJson = JSONObject().apply {
            put("code", code)
            put("sender", sender)
            put("originalText", originalText)
            put("timestamp", System.currentTimeMillis())
        }
        val encrypted = CryptoManager.encrypt(payloadJson.toString()) ?: return
        val (ciphertext, iv, hash) = encrypted
        val roomId = CryptoManager.getRoomId() ?: return

        val json = JSONObject().apply {
            put("type", "OTP")
            put("roomId", roomId)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("hash", hash)
            put("originDeviceId", deviceId)
            put("timestamp", System.currentTimeMillis())
        }

        val jsonString = json.toString()
        val payloadBytes = (jsonString + "\n").toByteArray(Charsets.UTF_8)

        // 1. Send via Cloudflare WebSocket
        webSocket?.send(jsonString)

        // 2. Send via Direct LAN Sockets
        networkExecutor.execute {
            synchronized(lanSockets) {
                for (socket in lanSockets) {
                    try {
                        val outputStream = socket.getOutputStream()
                        outputStream.write(payloadBytes)
                        outputStream.flush()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
