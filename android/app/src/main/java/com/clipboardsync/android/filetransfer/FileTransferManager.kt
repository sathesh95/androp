package com.clipboardsync.android.filetransfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.ui.FileReceiveActivity
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Orchestrates the full file transfer lifecycle on Android.
 *
 * Signal flow (identical to macOS FileTransferManager):
 *   FILE_OFFER → show accept/reject UI (FileReceiveActivity)
 *   FILE_ACCEPT → sender starts LAN server or uploads to R2
 *   FILE_LAN_READY / FILE_INTERNET_READY → receiver downloads + decrypts
 *   FILE_COMPLETE → sender cleans up
 *   FILE_ERROR → both sides reset
 */
object FileTransferManager {

    private const val TAG = "FileTransferManager"

    private val executor = Executors.newCachedThreadPool()

    // Incoming transfer state
    private var incomingTransferId: String? = null
    private var incomingKeyBytes: ByteArray? = null
    private var incomingFileName: String? = null

    // Outgoing transfer state
    private var outgoingTransferId: String? = null
    private var outgoingToken: String? = null
    private var outgoingStagedFile: File? = null
    private var outgoingFileName: String? = null
    private var outgoingKeyBytes: ByteArray? = null

    // Progress/status callback — used by FileReceiveActivity to update UI
    var onProgress: ((Double) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null
    var onTransferComplete: ((File) -> Unit)? = null
    var onTransferError: ((String) -> Unit)? = null

    // ── Public API: Send (Android → Mac) ─────────────────────────────────────

    fun sendFile(context: Context, stagedFile: File, fileName: String, mimeType: String = "application/octet-stream") {
        val roomKey = CryptoManager.getRoomKeyBytes() ?: run {
            Log.w(TAG, "No room key available"); return
        }
        val roomId = CryptoManager.getRoomId() ?: return
        val deviceId = NetworkManager.deviceId

        val transferId = UUID.randomUUID().toString()
        val token      = generateToken()
        val fileKey    = FileTransferCrypto.generateFileKey()

        outgoingTransferId = transferId
        outgoingToken      = token
        outgoingStagedFile = stagedFile
        outgoingFileName   = fileName
        outgoingKeyBytes   = fileKey

        val (encKeyB64, keyIvB64) = FileTransferCrypto.encryptFileKey(fileKey, roomKey)
        val fileSize = stagedFile.length()

        val offer = JSONObject().apply {
            put("type",             "FILE_OFFER")
            put("roomId",           roomId)
            put("transferId",       transferId)
            put("fileName",         fileName)
            put("fileSize",         fileSize)
            put("mimeType",         mimeType)
            put("encryptedFileKey", encKeyB64)
            put("fileKeyIv",        keyIvB64)
            put("originDeviceId",   deviceId)
            put("timestamp",        System.currentTimeMillis())
        }
        NetworkManager.broadcastSignal(offer)
        onStatusChange?.invoke("Waiting for file to be accepted…")
    }

    // ── Signal Router ─────────────────────────────────────────────────────────

    fun handleSignal(context: Context, json: JSONObject) {
        when (val type = json.optString("type")) {
            "FILE_OFFER"          -> handleIncomingOffer(context, json)
            "FILE_ACCEPT"         -> handleSenderGotAccept(context, json)
            "FILE_REJECT"         -> handleSenderGotReject()
            "FILE_LAN_READY"      -> handleReceiverStartLan(context, json)
            "FILE_INTERNET_READY" -> handleReceiverStartInternet(context, json)
            "FILE_PROGRESS"       -> { /* sender can track if needed */ }
            "FILE_COMPLETE"       -> handleSenderComplete()
            "FILE_ERROR"          -> handleRemoteError(json)
            else                  -> Log.d(TAG, "Unknown file signal: $type")
        }
    }

    // ── Incoming: FILE_OFFER ──────────────────────────────────────────────────

    private fun handleIncomingOffer(context: Context, json: JSONObject) {
        val roomKey      = CryptoManager.getRoomKeyBytes() ?: return
        val transferId   = json.optString("transferId").takeIf { it.isNotBlank() } ?: return
        val fileName     = json.optString("fileName").takeIf { it.isNotBlank() } ?: return
        val fileSize     = json.optLong("fileSize", 0L)
        val mimeType     = json.optString("mimeType", "application/octet-stream")
        val encKeyB64    = json.optString("encryptedFileKey").takeIf { it.isNotBlank() } ?: return
        val keyIvB64     = json.optString("fileKeyIv").takeIf { it.isNotBlank() } ?: return
        val originDevice = json.optString("originDeviceId")

        // Ignore echo of our own offers
        if (originDevice == NetworkManager.deviceId) return

        val fileKey = try {
            FileTransferCrypto.decryptFileKey(encKeyB64, keyIvB64, roomKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt file key", e); return
        }

        incomingTransferId = transferId
        incomingKeyBytes   = fileKey
        incomingFileName   = fileName

        Log.d(TAG, "Incoming file offer: $fileName ($fileSize bytes) from $originDevice")

        // 1. Show High-Priority Heads-Up Notification (reliable on Android 10+ background)
        FileNotificationHelper.showIncomingFileNotification(
            context = context,
            transferId = transferId,
            fileName = fileName,
            fileSize = fileSize,
            originDevice = originDevice
        )

        // 2. Also try starting activity directly (succeeds if app is foreground or has overlay perms)
        try {
            val intent = Intent(context, FileReceiveActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("transferId",   transferId)
                putExtra("fileName",     fileName)
                putExtra("fileSize",     fileSize)
                putExtra("mimeType",     mimeType)
                putExtra("originDevice", originDevice)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.d(TAG, "Direct activity start suppressed by OS (notification will handle it): ${e.message}")
        }
    }

    fun acceptOffer(context: Context) {
        val transferId = incomingTransferId ?: return
        val roomId     = CryptoManager.getRoomId() ?: return

        val accept = JSONObject().apply {
            put("type",           "FILE_ACCEPT")
            put("roomId",         roomId)
            put("transferId",     transferId)
            put("originDeviceId", NetworkManager.deviceId)
        }
        NetworkManager.broadcastSignal(accept)
        onStatusChange?.invoke("Waiting for transfer to start…")
    }

    fun rejectOffer(context: Context) {
        val transferId = incomingTransferId ?: return
        val roomId     = CryptoManager.getRoomId() ?: return

        val reject = JSONObject().apply {
            put("type",           "FILE_REJECT")
            put("roomId",         roomId)
            put("transferId",     transferId)
            put("originDeviceId", NetworkManager.deviceId)
        }
        NetworkManager.broadcastSignal(reject)
        resetIncoming()
    }

    // ── Sender: FILE_ACCEPT ───────────────────────────────────────────────────

    private fun handleSenderGotAccept(context: Context, json: JSONObject) {
        val tid = json.optString("transferId")
        if (tid != outgoingTransferId) return

        val stagedFile = outgoingStagedFile ?: return
        val keyBytes   = outgoingKeyBytes   ?: return
        val token      = outgoingToken      ?: return
        val tid2       = outgoingTransferId ?: return

        // Android → Mac always uses R2 (LAN server from Android not yet implemented)
        executor.submit {
            uploadToR2(context, stagedFile, keyBytes, token, tid2)
        }
    }

    private fun handleSenderGotReject() {
        resetOutgoing()
        onStatusChange?.invoke("Transfer declined.")
    }

    private fun uploadToR2(context: Context, stagedFile: File, keyBytes: ByteArray, token: String, transferId: String) {
        val roomId = CryptoManager.getRoomId() ?: return
        onStatusChange?.invoke("Encrypting file…")

        try {
            val encTemp = FileTransferCrypto.encryptToTempFile(
                source   = stagedFile,
                keyBytes = keyBytes,
                progress = { p -> onProgress?.invoke(p * 0.5) }
            )

            try {
                onStatusChange?.invoke("Uploading to cloud…")
                val relayBase = wsToHttpUrl(CryptoManager.getRelayUrl())
                val status = FileTransferClient.uploadToR2(
                    relayBaseUrl     = relayBase,
                    transferId       = transferId,
                    token            = token,
                    encryptedFile    = encTemp,
                    originalFileName = outgoingFileName ?: "file"
                )

                if (status == 200) {
                    val ready = JSONObject().apply {
                        put("type",           "FILE_INTERNET_READY")
                        put("roomId",         roomId)
                        put("transferId",     transferId)
                        put("token",          token)
                        put("fileName",       outgoingFileName ?: "file")
                        put("originDeviceId", NetworkManager.deviceId)
                    }
                    NetworkManager.broadcastSignal(ready)
                    onProgress?.invoke(0.5)
                    onStatusChange?.invoke("Waiting for receiver to download…")
                } else {
                    signalError(context, "Upload failed (HTTP $status)")
                }
            } finally {
                encTemp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            signalError(context, e.message ?: "Upload failed")
        }
    }


    // ── Receiver: FILE_LAN_READY ─────────────────────────────────────────────

    private fun handleReceiverStartLan(context: Context, json: JSONObject) {
        val keyBytes  = incomingKeyBytes ?: return
        val ip        = json.optString("ip").takeIf { it.isNotBlank() } ?: return
        val port      = json.optInt("port", 0).takeIf { it > 0 } ?: return
        val token     = json.optString("token").takeIf { it.isNotBlank() } ?: return
        val fileName  = json.optString("fileName").ifBlank { incomingFileName ?: "file" }
        val transferId = incomingTransferId ?: return

        val destination = downloadsFile(fileName)
        onStatusChange?.invoke("Downloading via LAN…")

        executor.submit {
            FileTransferClient.downloadFromLan(
                ip          = ip,
                port        = port,
                token       = token,
                keyBytes    = keyBytes,
                destination = destination,
                onProgress  = { p -> onProgress?.invoke(p) },
                onComplete  = {
                    signalComplete(context, transferId)
                    onTransferComplete?.invoke(destination)
                    onStatusChange?.invoke("Saved to Downloads ✓")
                },
                onError     = { err -> signalError(context, err) }
            )
        }
    }

    // ── Receiver: FILE_INTERNET_READY ────────────────────────────────────────

    private fun handleReceiverStartInternet(context: Context, json: JSONObject) {
        val keyBytes   = incomingKeyBytes ?: return
        val transferId = json.optString("transferId").takeIf { it.isNotBlank() } ?: return
        val token      = json.optString("token").takeIf { it.isNotBlank() } ?: return
        val fileName   = json.optString("fileName").ifBlank { incomingFileName ?: "file" }

        val relayBase   = wsToHttpUrl(CryptoManager.getRelayUrl())
        val destination = downloadsFile(fileName)
        onStatusChange?.invoke("Downloading from cloud…")

        executor.submit {
            FileTransferClient.downloadFromR2(
                relayBaseUrl = relayBase,
                transferId   = transferId,
                token        = token,
                keyBytes     = keyBytes,
                destination  = destination,
                onProgress   = { p -> onProgress?.invoke(p) },
                onComplete   = {
                    FileTransferClient.deleteFromR2(relayBase, transferId, token)
                    signalComplete(context, transferId)
                    onTransferComplete?.invoke(destination)
                    onStatusChange?.invoke("Saved to Downloads ✓")
                },
                onError      = { err -> signalError(context, err) }
            )
        }
    }

    // ── Sender: FILE_COMPLETE ─────────────────────────────────────────────────

    private fun handleSenderComplete() {
        onStatusChange?.invoke("File delivered ✓")
        resetOutgoing()
    }

    private fun handleRemoteError(json: JSONObject) {
        val reason = json.optString("reason", "Unknown error")
        onTransferError?.invoke(reason)
        resetIncoming(); resetOutgoing()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun signalComplete(context: Context, transferId: String) {
        val roomId = CryptoManager.getRoomId() ?: return
        val complete = JSONObject().apply {
            put("type",           "FILE_COMPLETE")
            put("roomId",         roomId)
            put("transferId",     transferId)
            put("originDeviceId", NetworkManager.deviceId)
        }
        NetworkManager.broadcastSignal(complete)
        resetIncoming()
    }

    private fun signalError(context: Context, reason: String) {
        val roomId = CryptoManager.getRoomId() ?: return
        val err = JSONObject().apply {
            put("type",           "FILE_ERROR")
            put("roomId",         roomId)
            put("transferId",     incomingTransferId ?: outgoingTransferId ?: "")
            put("reason",         reason)
            put("originDeviceId", NetworkManager.deviceId)
        }
        NetworkManager.broadcastSignal(err)
        onTransferError?.invoke(reason)
        resetIncoming(); resetOutgoing()
    }


    private fun resetIncoming() {
        incomingTransferId = null; incomingKeyBytes = null; incomingFileName = null
    }

    private fun resetOutgoing() {
        outgoingTransferId = null; outgoingToken = null
        try { outgoingStagedFile?.delete() } catch (e: Exception) {}
        outgoingStagedFile = null; outgoingFileName = null; outgoingKeyBytes = null
    }


    private fun generateToken() =
        (0 until 32).joinToString("") { "%02x".format(SecureRandom().nextInt(256)) }

    private fun downloadsFile(name: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        return File(dir, sanitize(name))
    }

    private fun sanitize(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    private fun wsToHttpUrl(wsUrl: String): String {
        var u = wsUrl
        if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
        else if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
        if (u.endsWith("/ws")) u = u.removeSuffix("/ws")
        return u
    }
}
