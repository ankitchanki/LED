package com.glowled.app.core

import java.util.Locale

/** Presentation metadata only: never keep transfer titles, file names or message text. */
enum class TransferDirection(val arrow: String) {
    UPLOAD("↑"), DOWNLOAD("↓"), UNKNOWN("↕");

    companion object {
        private val uploadWords = Regex("upload|sending|enviando|enviar|अपलोड|भेज रहा")
        private val downloadWords = Regex("download|receiving|baixando|baixar|recebendo|डाउनलोड")
        // A progress bar alone cannot tell which direction bytes are moving.
        fun fromText(text: String): TransferDirection {
            val words = text.lowercase(Locale.ROOT)
            val upload = uploadWords.containsMatchIn(words)
            val download = downloadWords.containsMatchIn(words)
            return when {
                upload && !download -> UPLOAD
                download && !upload -> DOWNLOAD
                else -> UNKNOWN
            }
        }
    }
}

object TransferInfo {
    fun initial(label: String): String = label.trim().firstOrNull { it.isLetterOrDigit() }
        ?.toString()?.uppercase(Locale.ROOT) ?: "↕"

    /** Stable brand colours; other apps use the selected per-app/notification/icon colour. */
    fun appColor(pkg: String, fallback: Int): Int = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> 0xFF25D366.toInt()
        "com.instagram.android" -> 0xFFE1306C.toInt()
        "org.telegram.messenger", "org.telegram.messenger.web" -> 0xFF229ED9.toInt()
        else -> fallback
    }
}
