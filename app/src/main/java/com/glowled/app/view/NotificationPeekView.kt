package com.glowled.app.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.R
import com.glowled.app.core.NotificationContent
import com.glowled.app.core.NotificationStore

/**
 * Samsung AOD-style notification peek under the app icons: pitch black (no box, so AMOLED pixels
 * stay off), soft dimmed text, everything left-aligned on one edge like a notification:
 *
 *     [icon] WhatsApp · 2 min
 *     Ana
 *     See you at 8?
 *     [📷 Photo]   +2 more
 *     [↩ Reply] [✓ Mark as read]
 *
 * Tap the text to open the chat; the buttons use the app's own Reply / Mark as read actions.
 */
class NotificationPeekView(context: Context) : LinearLayout(context) {

    var onOpen: ((NotificationStore.Entry) -> Unit)? = null
    var onReply: ((NotificationStore.Entry) -> Unit)? = null
    var onMarkRead: ((NotificationStore.Entry) -> Unit)? = null

    /** Show the Reply / Mark as read buttons. */
    var actionsEnabled = true

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private var current: NotificationStore.Entry? = null
    private var shown = false

    // Dim greys on black, like an always-on display: readable without lighting the panel up.
    private fun grey(alpha: Float) = Color.argb((255 * alpha).toInt(), 255, 255, 255)

    private fun line(size: Float, alpha: Float, lines: Int) = TextView(context).apply {
        setTextColor(grey(alpha))
        textSize = size
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private val appIcon = ImageView(context).apply { alpha = 0.6f }
    private val appName = line(12f, 0.42f, 1)
    private val title = line(15f, 0.75f, 1).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val body = line(14f, 0.55f, 4).apply { setLineSpacing(0f, 1.12f) }
    private val media = line(12f, 0.6f, 1).apply { setPadding(dp(10f), dp(3f), dp(10f), dp(3f)) }
    private val more = line(12f, 0.35f, 1)
    private val reply = actionButton(R.drawable.ic_reply, R.string.peek_reply) { current?.let { onReply?.invoke(it) } }
    private val markRead = actionButton(R.drawable.ic_done_all, R.string.peek_mark_read) { current?.let { onMarkRead?.invoke(it) } }
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(reply, LayoutParams(-2, dp(36f)).apply { marginEnd = dp(8f) })
        addView(markRead, LayoutParams(-2, dp(36f)))
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        setBackgroundColor(Color.BLACK)
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(appIcon, LayoutParams(dp(16f), dp(16f)).apply { marginEnd = dp(8f) })
            addView(appName, LayoutParams(0, -2, 1f))
        }
        addView(head, LayoutParams(-1, -2))
        addView(title, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        addView(body, LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        val tags = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(media, LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
            addView(more, LayoutParams(-2, -2))
        }
        addView(tags, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        alpha = 0f
        visibility = INVISIBLE
        isClickable = true
        setOnClickListener { current?.let { onOpen?.invoke(it) } }
    }

    /** Outlined pill on black (no fill, so it costs almost no AMOLED power). */
    private fun actionButton(icon: Int, label: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(label)
        textSize = 13f
        setTextColor(grey(0.72f))
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12f), 0, dp(14f), 0)
        compoundDrawablePadding = dp(6f)
        context.getDrawable(icon)?.mutate()?.let {
            it.setBounds(0, 0, dp(16f), dp(16f))
            it.setTintList(ColorStateList.valueOf(grey(0.62f)))
            setCompoundDrawablesRelative(it, null, null, null)
        }
        background = GradientDrawable().apply {
            cornerRadius = 18 * d
            setColor(Color.BLACK)
            setStroke(dp(1f), grey(0.22f))
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Fills the peek with the newest notification of an app (+ how many more). */
    fun bind(entries: List<NotificationStore.Entry>) {
        val e = entries.firstOrNull() ?: return
        current = e
        val pm = context.packageManager
        val icon = AppInfoView.iconFor(pm, e.pkg)
        appIcon.setImageDrawable(icon)
        appIcon.visibility = if (icon == null) GONE else VISIBLE
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        appName.text = context.getString(R.string.peek_app_time, AppInfoView.labelFor(pm, e.pkg), ago)
        if (e.hidden) {
            title.setText(R.string.peek_new)
            body.setText(R.string.peek_hidden)
            body.visibility = VISIBLE
            media.visibility = GONE
        } else {
            title.text = e.title ?: AppInfoView.labelFor(pm, e.pkg)
            val text = e.lines.joinToString("\n")
            body.text = text
            body.visibility = if (text.isBlank()) GONE else VISIBLE
            val label = NotificationContent.label(context, e.kind)
            media.text = label
            media.visibility = if (label.isEmpty()) GONE else VISIBLE
            // Thin outline in the app's colour (no fill, keeps pixels off).
            media.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(Color.BLACK)
                setStroke(dp(1f), (e.color and 0x00FFFFFF) or 0x66000000)
            }
        }
        val extra = entries.size - 1
        more.text = if (extra > 0) context.getString(R.string.peek_more, extra) else ""
        more.visibility = if (extra > 0) VISIBLE else GONE
        (media.parent as View).visibility = if (media.visibility == GONE && more.visibility == GONE) GONE else VISIBLE

        reply.visibility = if (actionsEnabled && e.replyAction != null) VISIBLE else GONE
        // Without the app's own action, "Dismiss" clears the notification instead.
        markRead.setText(if (e.readAction != null) R.string.peek_mark_read else R.string.peek_dismiss)
        markRead.visibility = if (actionsEnabled && (e.readAction != null || e.clearable)) VISIBLE else GONE
        actions.visibility = if (reply.visibility == GONE && markRead.visibility == GONE) GONE else VISIBLE
    }

    fun isShowing() = shown

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            alpha = 0f
            translationY = -dp(12f).toFloat()
        }
        shown = true
        animate().alpha(1f).translationY(0f)
            .setDuration(320).setInterpolator(OvershootInterpolator(1f)).start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-dp(8f).toFloat()).setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!shown) visibility = INVISIBLE }.start()
    }
}
