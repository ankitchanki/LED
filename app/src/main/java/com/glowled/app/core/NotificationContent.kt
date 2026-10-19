package com.glowled.app.core

import android.app.Notification
import androidx.core.app.NotificationCompat
import com.glowled.app.core.NotificationStore.Kind

/**
 * Pulls "who sent what" out of a notification for the tap-to-peek card: the conversation/sender,
 * up to the last few messages, and whether it's a photo, video, voice note or file.
 */
object NotificationContent {

    data class Content(val title: String?, val lines: List<String>, val kind: Kind)

    private const val MAX_LINES = 3

    fun read(n: Notification): Content {
        val ex = n.extras ?: return Content(null, emptyList(), Kind.OTHER)
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim()

        // Chat apps (WhatsApp, Telegram, Signal, Messages...) use MessagingStyle.
        val messages = messagingLines(n)
        if (messages.isNotEmpty()) {
            val kind = messages.last().second
            return Content(title, messages.map { it.first }.takeLast(MAX_LINES), kind)
        }

        val lines = mutableListOf<String>()
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { lines += it.toString() }
        if (lines.isEmpty()) {
            (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.takeIf { it.isNotBlank() }?.let { lines += it }
        }
        val pictureStyle = ex.getString(Notification.EXTRA_TEMPLATE)?.endsWith("BigPictureStyle") == true ||
            ex.containsKey(Notification.EXTRA_PICTURE)
        val kind = if (pictureStyle) Kind.IMAGE else guessKind(lines.lastOrNull(), null)
        return Content(title, lines.map { it.trim() }.takeLast(MAX_LINES), kind)
    }

    /** Uses the AndroidX reader, which works on every Android version (the platform one is 30+). */
    private fun messagingLines(n: Notification): List<Pair<String, Kind>> = try {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val group = style?.isGroupConversation == true
        style?.messages.orEmpty().map { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            val kind = guessKind(text, m.dataMimeType)
            // Media without a caption: a language-neutral emoji (the peek adds the full label).
            val body = if (text.isEmpty()) emoji(kind) else text
            val who = m.person?.name?.toString()
            (if (group && !who.isNullOrBlank()) "$who: $body" else body) to kind
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Media type from a MIME type, or from the text apps put in place of media ("📷 Photo"). */
    fun guessKind(text: String?, mime: String?): Kind {
        if (mime != null) return when {
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("video/") -> Kind.VIDEO
            mime.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.FILE
        }
        val t = text?.trim()?.lowercase() ?: return Kind.TEXT
        return when {
            t.startsWith("📷") || t.startsWith("🖼") || t == "photo" || t.startsWith("photo") ||
                t.startsWith("sticker") || t.startsWith("gif") || t.startsWith("image") -> Kind.IMAGE
            t.startsWith("🎥") || t.startsWith("📹") || t.startsWith("video") -> Kind.VIDEO
            t.startsWith("🎤") || t.startsWith("🎙") || t.startsWith("🎵") || t.startsWith("voice") ||
                t.startsWith("audio") -> Kind.AUDIO
            t.startsWith("📄") || t.startsWith("📎") || t.startsWith("document") || t.startsWith("file") -> Kind.FILE
            else -> Kind.TEXT
        }
    }

    private fun emoji(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "📷"
        Kind.VIDEO -> "🎥"
        Kind.AUDIO -> "🎤"
        Kind.FILE -> "📄"
        Kind.TEXT, Kind.OTHER -> ""
    }

    fun label(context: android.content.Context, kind: Kind): String = when (kind) {
        Kind.IMAGE -> context.getString(com.glowled.app.R.string.kind_photo)
        Kind.VIDEO -> context.getString(com.glowled.app.R.string.kind_video)
        Kind.AUDIO -> context.getString(com.glowled.app.R.string.kind_voice)
        Kind.FILE -> context.getString(com.glowled.app.R.string.kind_document)
        Kind.TEXT, Kind.OTHER -> ""
    }
}
