package com.glowled.app.core

import java.util.Locale

/** Small sign drawn on the gauge ring at its bottom middle. */
enum class MeterIcon { NONE, BOLT, UP, DOWN, BOTH }

/** Presentation metadata only: never keep transfer titles, file names or message text. */
enum class TransferDirection(val icon: MeterIcon) {
    UPLOAD(MeterIcon.UP), DOWNLOAD(MeterIcon.DOWN), UNKNOWN(MeterIcon.BOTH);

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

/** The user's arrow for one app ([AUTO] = text, then the known app, then the "If unknown" setting). */
enum class ArrowChoice(val key: String) {
    AUTO("auto"), DOWN("down"), UP("up"), NONE("none");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** What the ring shows when nothing tells the direction. */
enum class UnknownArrow(val key: String) {
    NONE("none"), BOTH("both"), DOWN("down"), UP("up");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** How a transfer is drawn: [direction] picks the upload/download colour, [icon] the sign. */
data class TransferLook(val direction: TransferDirection, val icon: MeterIcon)

object TransferInfo {
    /** Apps whose transfers almost always go one way, used when the notification text says nothing. */
    private val known = mapOf(
        "com.android.vending" to TransferDirection.DOWNLOAD,
        "com.android.providers.downloads" to TransferDirection.DOWNLOAD,
        "com.android.chrome" to TransferDirection.DOWNLOAD,
        "org.mozilla.firefox" to TransferDirection.DOWNLOAD,
        "com.brave.browser" to TransferDirection.DOWNLOAD,
        "com.microsoft.emmx" to TransferDirection.DOWNLOAD,
        "com.opera.browser" to TransferDirection.DOWNLOAD,
        "com.sec.android.app.sbrowser" to TransferDirection.DOWNLOAD,
        "com.mi.globalbrowser" to TransferDirection.DOWNLOAD,
        "com.android.browser" to TransferDirection.DOWNLOAD,
        "com.xiaomi.market" to TransferDirection.DOWNLOAD,
        "com.xiaomi.mipicks" to TransferDirection.DOWNLOAD,
        "com.android.updater" to TransferDirection.DOWNLOAD,
        "com.google.android.apps.photos" to TransferDirection.UPLOAD,
        "com.amazon.clouddrive.photos" to TransferDirection.UPLOAD,
        "com.samsung.android.scloud" to TransferDirection.UPLOAD,
    )

    fun knownDirection(pkg: String): TransferDirection? = known[pkg]

    val knownPackages: Set<String> get() = known.keys

    /**
     * Order: the user's choice for the app, words in the notification, the known app, then the
     * "If unknown" setting. A choice of NONE hides the sign but keeps the direction for the colour.
     */
    fun look(pkg: String, detected: TransferDirection, choice: ArrowChoice, unknown: UnknownArrow): TransferLook {
        when (choice) {
            ArrowChoice.DOWN -> return TransferLook(TransferDirection.DOWNLOAD, MeterIcon.DOWN)
            ArrowChoice.UP -> return TransferLook(TransferDirection.UPLOAD, MeterIcon.UP)
            else -> Unit
        }
        val auto = when {
            detected != TransferDirection.UNKNOWN -> TransferLook(detected, detected.icon)
            known[pkg] != null -> known.getValue(pkg).let { TransferLook(it, it.icon) }
            else -> when (unknown) {
                UnknownArrow.NONE -> TransferLook(TransferDirection.UNKNOWN, MeterIcon.NONE)
                UnknownArrow.BOTH -> TransferLook(TransferDirection.UNKNOWN, MeterIcon.BOTH)
                UnknownArrow.DOWN -> TransferLook(TransferDirection.DOWNLOAD, MeterIcon.DOWN)
                UnknownArrow.UP -> TransferLook(TransferDirection.UPLOAD, MeterIcon.UP)
            }
        }
        return if (choice == ArrowChoice.NONE) auto.copy(icon = MeterIcon.NONE) else auto
    }

    /** Stable brand colours; other apps use the selected per-app/notification/icon colour. */
    fun appColor(pkg: String, fallback: Int): Int = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> 0xFF25D366.toInt()
        "com.instagram.android" -> 0xFFE1306C.toInt()
        "org.telegram.messenger", "org.telegram.messenger.web" -> 0xFF229ED9.toInt()
        else -> fallback
    }
}
