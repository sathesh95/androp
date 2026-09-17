package com.clipboardsync.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val PREFS_FILE = "clipboard_sync_secure_prefs"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_AES_KEY = "aes_key"
    private const val KEY_RELAY_URL = "relay_url"
    private const val DEFAULT_RELAY = "wss://clipboard-sync-relay.your-subdomain.workers.dev/ws"

    private lateinit var prefs: SharedPreferences
    private var secretKey: SecretKey? = null

    fun init(context: Context) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for older devices/custom ROMs
            prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
        loadKey()
    }

    fun isPaired(): Boolean {
        return getRoomId() != null && secretKey != null
    }

    fun getRoomId(): String? {
        return prefs.getString(KEY_ROOM_ID, null)
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY) ?: DEFAULT_RELAY
    }

    fun savePairingCredentials(roomId: String, keyBase64: String, relayUrl: String?) {
        prefs.edit()
            .putString(KEY_ROOM_ID, roomId)
            .putString(KEY_AES_KEY, keyBase64)
            .putString(KEY_RELAY_URL, relayUrl ?: DEFAULT_RELAY)
            .apply()
        loadKey()
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
        secretKey = null
    }

    private fun loadKey() {
        val keyBase64 = prefs.getString(KEY_AES_KEY, null) ?: return
        try {
            val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
            secretKey = SecretKeySpec(keyBytes, "AES")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // MARK: - Encryption (AES-256-GCM)

    fun encrypt(plainText: String): Triple<String, String, String>? {
        val key = secretKey ?: return null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12) // 96-bit nonce
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val cipherBytes = cipher.doFinal(plainBytes)

            val ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val hash = HashUtil.sha256(plainText)

            return Triple(ciphertextBase64, ivBase64, hash)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String): Pair<String, String>? {
        val key = secretKey ?: return null
        try {
            val cipherBytes = Base64.decode(ciphertextBase64, Base64.DEFAULT)
            val ivBytes = Base64.decode(ivBase64, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
            val hash = HashUtil.sha256(decryptedText)

            return Pair(decryptedText, hash)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
