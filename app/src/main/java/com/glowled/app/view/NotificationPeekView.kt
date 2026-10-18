package com.glowled.app.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.core.NotificationContent
import com.glowled.app.core.NotificationStore

/**
 * Card shown under the app strip when an app icon is tapped: app + time, who it's from, the latest
 * messages, and a media label (Photo / Video / Voice message / Document). Tap it to open the chat.
 */
class NotificationPeekView(context: Context) : LinearLayout(context) {

    var onOpen: ((NotificationStore.Entry) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private var current: NotificationStore.Entry? = null
    private var shown = false

    private val appIcon = ImageView(context)
    private val appName = TextView(context).apply {
        setTextColor(Color.argb(170, 255, 255, 255))
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val body = TextView(context).apply {
        setTextColor(Color.argb(215, 255, 255, 255))
        textSize = 14f
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.1f)
    }
    private val media = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
    }
    private val more = TextView(context).apply {
        setTextColor(Color.argb(150, 255, 255, 255))
        textSize = 12f
    }
    private val hint = TextView(context).apply {
        setTextColor(Color.argb(120, 255, 255, 255))
        textSize = 11f
        text = "Tap to open"
    }

    init {
        orientation = VERTICAL
        setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        background = GradientDrawable().apply {
            cornerRadius = 22 * d
            setColor(Color.rgb(16, 16, 20))
            setStroke(dp(1f), Color.argb(40, 255, 255, 255))
        }
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon, LayoutParams(dp(18f), dp(18f)))
            addView(appName, LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
            addView(hint)
        }
        addView(head)
        addView(title, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        addView(body, LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        addView(media, LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        addView(more, LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        alpha = 0f
        visibility = INVISIBLE
        isClickable = true
        setOnClickListener { current?.let { onOpen?.invoke(it) } }
    }

    /** Fills the card with the newest notification of an app (+ how many more). */
    fun bind(entries: List<NotificationStore.Entry>) {
        val e = entries.firstOrNull() ?: return
        current = e
        val pm = context.packageManager
        val icon = AppInfoView.iconFor(pm, e.pkg)
        appIcon.setImageDrawable(icon)
        appIcon.visibility = if (icon == null) GONE else VISIBLE
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        appName.text = "${AppInfoView.labelFor(pm, e.pkg)} · $ago"
        if (e.hidden) {
            title.text = "New notification"
            body.text = "Content hidden on the lock screen"
            media.visibility = GONE
        } else {
            title.text = e.title ?: AppInfoView.labelFor(pm, e.pkg)
            title.visibility = VISIBLE
            val text = e.lines.joinToString("\n")
            body.text = text
            body.visibility = if (text.isBlank()) GONE else VISIBLE
            val label = NotificationContent.label(e.kind)
            media.text = label
            media.visibility = if (label.isEmpty()) GONE else VISIBLE
            media.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(e.color and 0x00FFFFFF or 0x44000000)
            }
        }
        val extra = entries.size - 1
        more.text = if (extra > 0) "+$extra more from this app" else ""
        more.visibility = if (extra > 0) VISIBLE else GONE
    }

    fun isShowing() = shown

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            alpha = 0f
            translationY = -dp(16f).toFloat()
            scaleX = 0.94f
            scaleY = 0.94f
        }
        shown = true
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(360).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-dp(10f).toFloat()).setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!shown) visibility = INVISIBLE }.start()
    }
}
