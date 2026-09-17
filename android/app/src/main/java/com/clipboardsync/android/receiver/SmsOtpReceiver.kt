package com.clipboardsync.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.clipboardsync.android.crypto.CryptoManager
import com.clipboardsync.android.network.NetworkManager
import com.clipboardsync.android.ui.ClipboardGhostActivity

class SmsOtpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsOtpReceiver"
        private var lastProcessedTime = 0L
        private const val MIN_PROCESSING_INTERVAL_MS = 1500L

        // Multilingual keywords for OTP identification across 22 languages
        private val OTP_KEYWORDS = listOf(
            // English
            "otp", "verification", "verify", "code", "passcode", "one time", "one-time",
            "authentication", "confirm", "security code", "pin", "2fa", "two-factor", "two factor",
            // Indian regional languages
            "ओटीपी", "सत्यापन", "सुरक्षा कोड", // Hindi
            "যাচাইকরণ কোড", "ওটিপি", // Bengali
            "ధృవీకరణ", "ఓటీపీ", // Telugu
            "சரிபார்ப்பு", "ஒருமுறை கடவுச்சொல்", // Tamil
            "ઓટીપી", "ચકાસણી", // Gujarati
            "ಪರಿಶೀಲನೆ", "ಓಟಿಪಿ", // Kannada
            "സ്ഥിരീകരണ കോഡ്", // Malayalam
            "सत्यापन कोड", // Marathi
            "ਪੁਸ਼ਟੀਕਰਨ ਕੋਡ", // Punjabi
            // Global languages
            "code de confirmation", "mot de passe temporaire", // French
            "bestätigungscode", "sicherheitscode", // German
            "código de verificación", "código de confirmación", // Spanish
            "رمز التحقق", "رمز التأكيد", // Arabic
            "doğrulama kodu", "onay kodu", // Turkish
            "код подтверждения", "одноразовый пароль", // Russian
            "kode verifikasi", "kode keamanan" // Indonesian
        )

        // Standalone 4-8 digit OTP regex
        private val STANDALONE_OTP_REGEX = Regex("""\b(\d{4,8})\b""")
        // Split/hyphenated OTP regex (e.g. 123-456 or 123 456)
        private val SPLIT_OTP_REGEX = Regex("""(\d{3,4})[\s\-](\d{3,4})""")

        // Date patterns to avoid false-positives
        private val DATE_REGEX = Regex("""\b(19|20)\d{2}[-/.]\d{1,2}[-/.]\d{1,2}\b""")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!CryptoManager.isOtpSyncEnabled()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_PROCESSING_INTERVAL_MS) {
            return // Debounce multi-part SMS fragments
        }

        try {
            val messages = extractSmsMessages(intent)
            for (message in messages) {
                val body = message.messageBody ?: continue
                val sender = message.displayOriginatingAddress ?: "SMS"

                if (containsOtpKeyword(body)) {
                    val otpCode = extractOtpCode(body)
                    if (otpCode != null) {
                        lastProcessedTime = currentTime
                        Log.d(TAG, "Extracted OTP code: $otpCode from sender: $sender")

                        // 1. Copy locally on Android
                        ClipboardGhostActivity.copyToClipboard(context.applicationContext, otpCode)

                        // 2. Broadcast encrypted OTP payload to Mac
                        NetworkManager.broadcastOtp(code = otpCode, sender = sender, originalText = body)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming SMS OTP", e)
        }
    }

    private fun extractSmsMessages(intent: Intent): List<SmsMessage> {
        return try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PDUs from intent", e)
            emptyList()
        }
    }

    private fun containsOtpKeyword(body: String): Boolean {
        val lower = body.lowercase()
        return OTP_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    private fun extractOtpCode(body: String): String? {
        // First try standalone pattern
        val matches = STANDALONE_OTP_REGEX.findAll(body)
        for (match in matches) {
            val code = match.groupValues[1]
            if (isValidOtpCandidate(code, body, match.range)) {
                return code
            }
        }

        // Fallback: try split/hyphenated pattern
        val splitMatch = SPLIT_OTP_REGEX.find(body)
        if (splitMatch != null) {
            val combined = splitMatch.groupValues[1] + splitMatch.groupValues[2]
            if (combined.length in 4..8) {
                return combined
            }
        }

        return null
    }

    private fun isValidOtpCandidate(code: String, fullText: String, range: IntRange): Boolean {
        // Exclude dates like 2026, 2024
        if (code.length == 4 && (code.startsWith("19") || code.startsWith("20"))) {
            if (DATE_REGEX.containsMatchIn(fullText)) {
                return false
            }
        }

        // Exclude currency indicators immediately preceding or trailing the number
        val prefix = fullText.substring(0, range.first).takeLast(4).trim()
        val suffix = fullText.substring(range.last + 1).take(4).trim()
        val currencySymbols = listOf("$", "₹", "rs", "inr", "usd", "eur", "gbp", "€", "£")

        if (currencySymbols.any { prefix.endsWith(it, ignoreCase = true) }) return false
        if (currencySymbols.any { suffix.startsWith(it, ignoreCase = true) }) return false

        return true
    }
}
