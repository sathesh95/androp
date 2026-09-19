package com.clipboardsync.android.filetransfer

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlin.math.ceil

/**
 * Chunked AES-256-GCM encryption / decryption for file transfers.
 *
 * Wire format (identical to macOS FileTransferCrypto):
 *   [4 bytes magic "FTF!" = 0x46544621]
 *   [4 bytes chunk_count, big-endian]
 *   For each chunk:
 *     [4 bytes chunk_data_len, big-endian]   (= 12 nonce + ciphertext + 16 tag)
 *     [12 bytes nonce]
 *     [chunk_data_len - 12 bytes: ciphertext + GCM tag]
 */
object FileTransferCrypto {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_LEN = 12       // 96-bit GCM nonce
    private const val TAG_LEN   = 128      // GCM tag length in bits
    const val CHUNK_SIZE        = 1 * 1024 * 1024  // 1 MB
    private const val MAGIC     = 0x46544621.toInt()

    private val rng = SecureRandom()

    // ── Per-transfer key helpers ─────────────────────────────────────────────

    /** Generate a fresh random 256-bit AES key for one file transfer. */
    fun generateFileKey(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    /**
     * Wrap [fileKeyBytes] inside an AES-GCM envelope keyed by [roomKeyBytes].
     * Returns (ciphertextBase64, ivBase64) for embedding in FILE_OFFER.
     */
    fun encryptFileKey(fileKeyBytes: ByteArray, roomKeyBytes: ByteArray): Pair<String, String> {
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        val cipher = aesCipher(Cipher.ENCRYPT_MODE, roomKeyBytes, nonce)
        val ct = cipher.doFinal(fileKeyBytes)
        return Base64.encodeToString(ct, Base64.NO_WRAP) to
               Base64.encodeToString(nonce, Base64.NO_WRAP)
    }

    /**
     * Unwrap and return the raw file key bytes from FILE_OFFER signal fields.
     */
    fun decryptFileKey(ciphertextBase64: String, ivBase64: String, roomKeyBytes: ByteArray): ByteArray {
        val ct    = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val nonce = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipher = aesCipher(Cipher.DECRYPT_MODE, roomKeyBytes, nonce)
        return cipher.doFinal(ct)
    }

    // ── File Encryption → temp file (for R2 upload) ──────────────────────────

    /**
     * Encrypt [source] chunk-by-chunk into a new temp file.
     * Returns the temp File (caller must delete it).
     * [progress] callback receives 0.0..1.0.
     */
    fun encryptToTempFile(
        source: File,
        keyBytes: ByteArray,
        progress: ((Double) -> Unit)? = null
    ): File {
        val totalChunks = calcChunks(source.length())
        val temp = File.createTempFile("ftf_enc_", ".ftf")

        source.inputStream().buffered().use { inp ->
            FileOutputStream(temp).buffered().use { out ->
                out.writeInt32BE(MAGIC)
                out.writeInt32BE(totalChunks)

                var done = 0
                val buf = ByteArray(CHUNK_SIZE)
                var n: Int
                while (inp.read(buf).also { n = it } > 0) {
                    val plain = buf.copyOf(n)
                    val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
                    val cipher = aesCipher(Cipher.ENCRYPT_MODE, keyBytes, nonce)
                    val ct = cipher.doFinal(plain)               // ciphertext + 16-byte tag

                    out.writeInt32BE(NONCE_LEN + ct.size)
                    out.write(nonce)
                    out.write(ct)

                    done++
                    progress?.invoke(done.toDouble() / totalChunks)
                }
            }
        }
        return temp
    }

    // ── File Decryption ← temp file (from R2 / LAN) ─────────────────────────

    /**
     * Decrypt the FTF-format [source] file into [destination].
     * [progress] callback receives 0.0..1.0.
     */
    fun decryptFromFile(
        source: File,
        keyBytes: ByteArray,
        destination: File,
        progress: ((Double) -> Unit)? = null
    ) {
        FileInputStream(source).buffered().use { inp ->
            val magic = inp.readInt32BE()
            require(magic == MAGIC) { "Invalid FTF magic (got 0x${magic.toString(16)})" }
            val totalChunks = inp.readInt32BE()

            FileOutputStream(destination).buffered().use { out ->
                for (i in 0 until totalChunks) {
                    val chunkLen = inp.readInt32BE()
                    require(chunkLen > NONCE_LEN + 16) { "Chunk too small at index $i" }

                    val nonce = inp.readNBytes(NONCE_LEN)
                    val ct    = inp.readNBytes(chunkLen - NONCE_LEN)

                    val cipher = aesCipher(Cipher.DECRYPT_MODE, keyBytes, nonce)
                    out.write(cipher.doFinal(ct))

                    progress?.invoke((i + 1).toDouble() / totalChunks)
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun toSecretKey(keyBytes: ByteArray): SecretKey = SecretKeySpec(keyBytes, "AES")

    private fun aesCipher(mode: Int, keyBytes: ByteArray, nonce: ByteArray): Cipher {
        val key  = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(TAG_LEN, nonce)
        return Cipher.getInstance(ALGORITHM).apply { init(mode, key, spec) }
    }

    private fun calcChunks(fileSize: Long) =
        maxOf(1, ceil(fileSize.toDouble() / CHUNK_SIZE).toInt())

    private fun java.io.OutputStream.writeInt32BE(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr  8) and 0xFF)
        write( v          and 0xFF)
    }

    private fun java.io.InputStream.readInt32BE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        if (b3 < 0) throw java.io.EOFException()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun java.io.InputStream.readNBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("Unexpected EOF at offset $off (need $n bytes)")
            off += r
        }
        return buf
    }
}
