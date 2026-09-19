package com.clipboardsync.android.filetransfer

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Handles the actual byte-level download from either:
 *   • A Mac LAN server (raw TCP socket, FTF protocol)
 *   • Cloudflare R2 (HTTP GET via OkHttp)
 * and the final DELETE from R2 after a successful transfer.
 */
object FileTransferClient {

    private val TAG = "FileTransferClient"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── LAN Download ─────────────────────────────────────────────────────────

    /**
     * Connect to the Mac LAN server, verify via [token], download the
     * FTF-encrypted stream into a temp file, decrypt to [destination].
     *
     * Calls [onProgress] with 0.0–1.0 during decryption.
     * Calls [onComplete] when done, [onError] on failure.
     */
    fun downloadFromLan(
        ip: String,
        port: Int,
        token: String,
        keyBytes: ByteArray,
        destination: File,
        onProgress: ((Double) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val socket = Socket(ip, port)
            socket.soTimeout = 60_000

            socket.getOutputStream().buffered().use { sockOut ->
                socket.getInputStream().buffered().use { sockIn ->
                    // Send token + newline
                    val tokenLine = "$token\n".toByteArray(Charsets.US_ASCII)
                    sockOut.write(tokenLine)
                    sockOut.flush()

                    // Stream the encrypted data into a temp file
                    val tempFile = File.createTempFile("ftf_lan_", ".ftf")
                    try {
                        FileOutputStream(tempFile).buffered().use { tmpOut ->
                            val buf = ByteArray(65536)
                            var n: Int
                            while (sockIn.read(buf).also { n = it } > 0) {
                                tmpOut.write(buf, 0, n)
                            }
                        }

                        // Decrypt temp file → destination
                        FileTransferCrypto.decryptFromFile(
                            source      = tempFile,
                            keyBytes    = keyBytes,
                            destination = destination,
                            progress    = onProgress
                        )
                        onComplete()
                    } finally {
                        tempFile.delete()
                    }
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "LAN download error", e)
            destination.delete()
            onError(e.message ?: "LAN download failed")
        }
    }

    // ── R2 Internet Download ─────────────────────────────────────────────────

    /**
     * Download the encrypted file from Cloudflare R2 via the relay Worker,
     * then decrypt to [destination].
     */
    fun downloadFromR2(
        relayBaseUrl: String,
        transferId: String,
        token: String,
        keyBytes: ByteArray,
        destination: File,
        onProgress: ((Double) -> Unit)? = null,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val url = "$relayBaseUrl/file/$transferId?token=$token"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                onError("Download failed: HTTP ${response.code}")
                return
            }

            val body = response.body ?: run { onError("Empty response body"); return }
            val contentLength = body.contentLength()

            // Stream download to temp file with progress tracking
            val tempFile = File.createTempFile("ftf_r2_", ".ftf")
            try {
                var downloaded = 0L
                body.byteStream().buffered().use { inp ->
                    FileOutputStream(tempFile).buffered().use { out ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (inp.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (contentLength > 0) {
                                // Download is 0–80% of total progress; decryption is 80–100%
                                onProgress?.invoke((downloaded.toDouble() / contentLength) * 0.8)
                            }
                        }
                    }
                }

                // Decrypt temp file → destination
                FileTransferCrypto.decryptFromFile(
                    source      = tempFile,
                    keyBytes    = keyBytes,
                    destination = destination,
                    progress    = { p -> onProgress?.invoke(0.8 + p * 0.2) }
                )
                onComplete()
            } finally {
                tempFile.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "R2 download error", e)
            destination.delete()
            onError(e.message ?: "Cloud download failed")
        }
    }

    // ── R2 Cleanup ───────────────────────────────────────────────────────────

    /**
     * Fire-and-forget DELETE of the R2 object after successful transfer.
     * Runs on the calling thread; caller should invoke from a background thread.
     */
    fun deleteFromR2(relayBaseUrl: String, transferId: String, token: String) {
        try {
            val url     = "$relayBaseUrl/file/$transferId?token=$token"
            val request = Request.Builder().url(url).delete().build()
            http.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "R2 cleanup failed (non-critical): ${e.message}")
        }
    }

    // ── R2 Upload (used by Android → Mac direction) ──────────────────────────

    /**
     * Upload an already-encrypted FTF temp file to R2.
     * Returns the HTTP status code.
     */
    fun uploadToR2(
        relayBaseUrl: String,
        transferId: String,
        token: String,
        encryptedFile: File,
        originalFileName: String
    ): Int {
        val url = "$relayBaseUrl/file/$transferId?token=$token"
        val body = RequestBody.create("application/octet-stream".toMediaType(), encryptedFile)
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("Content-Type", "application/octet-stream")
            .header("X-File-Name", originalFileName)
            .header("X-File-Size", encryptedFile.length().toString())
            .build()

        return try {
            http.newCall(request).execute().use { it.code }
        } catch (e: Exception) {
            Log.e(TAG, "R2 upload failed", e)
            -1
        }
    }
}
