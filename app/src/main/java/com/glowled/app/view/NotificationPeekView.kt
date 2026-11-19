package com.glowled.app.view

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.glowled.app.R
import com.glowled.app.core.NotificationContent
import com.glowled.app.core.NotificationStore

/**
 * Samsung AOD-style notification peek under the app icons: pitch black (no box, so AMOLED pixels
 * stay off), soft dimmed text, everything left-aligned on one edge like a notification.
 *
 * Supports expanding messages with individual replies and mark-as-read actions, full text preview,
 * and mark-all-as-read batch action.
 */
class NotificationPeekView(context: Context) : LinearLayout(context) {

    var onOpen: ((NotificationStore.Entry) -> Unit)? = null
    var onReply: ((NotificationStore.Entry) -> Unit)? = null
    var onMarkRead: ((NotificationStore.Entry) -> Unit)? = null
    var onMarkAllRead: ((List<NotificationStore.Entry>) -> Unit)? = null
    var onInteraction: (() -> Unit)? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    /** Show the Reply / Mark as read buttons. */
    var actionsEnabled = true
    /** Allow expanding to show all messages in the conversation/app. */
    var expandableEnabled = true

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private var entries: List<NotificationStore.Entry> = emptyList()
    private var current: NotificationStore.Entry? = null
    private var shown = false
    private var isExpanded = false

    private var baseFontSize = 14f
    private var bodyMaxLines = 4
    private var customMaxHeightDp = 380

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

    // Top Header
    private val appIcon = ImageView(context).apply { alpha = 0.6f }
    private val appName = line(12f, 0.42f, 1)
    private val headerChevron = ImageView(context).apply {
        setImageResource(R.drawable.ic_expand_more)
        imageTintList = ColorStateList.valueOf(grey(0.42f))
        visibility = GONE
    }

    // Collapsed Views
    private val collapsedContent = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.START
    }
    private val title = line(15f, 0.75f, 1).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val body = line(14f, 0.55f, 4).apply { setLineSpacing(0f, 1.12f) }
    private val media = line(12f, 0.6f, 1).apply { setPadding(dp(10f), dp(3f), dp(10f), dp(3f)) }
    private val expandButton = actionButton(R.drawable.ic_expand_more, R.string.peek_expand_single) {
        toggleExpanded()
    }

    fun applyCustomization(fontSizeSp: Float, maxLines: Int, maxHeightDp: Int) {
        baseFontSize = fontSizeSp
        bodyMaxLines = maxLines
        customMaxHeightDp = maxHeightDp

        appName.textSize = (baseFontSize - 2f).coerceAtLeast(10f)
        title.textSize = baseFontSize + 1f
        body.textSize = baseFontSize
        body.maxLines = bodyMaxLines
        media.textSize = (baseFontSize - 2.5f).coerceAtLeast(10f)
        expandButton.textSize = (baseFontSize - 1f).coerceAtLeast(11f)
        reply.textSize = (baseFontSize - 1f).coerceAtLeast(11f)
        markRead.textSize = (baseFontSize - 1f).coerceAtLeast(11f)
        collapseButton.textSize = (baseFontSize - 1f).coerceAtLeast(11f)
        markAllButton.textSize = (baseFontSize - 1f).coerceAtLeast(11f)

        if (isExpanded) {
            rebuildExpandedCards()
        }
    }
    private val reply = actionButton(R.drawable.ic_reply, R.string.peek_reply) {
        onInteraction?.invoke()
        current?.let { onReply?.invoke(it) }
    }
    private val markRead = actionButton(R.drawable.ic_done_all, R.string.peek_mark_read) {
        onInteraction?.invoke()
        current?.let { onMarkRead?.invoke(it) }
    }
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(reply, LayoutParams(-2, dp(36f)).apply { marginEnd = dp(8f) })
        addView(markRead, LayoutParams(-2, dp(36f)))
    }

    // Expanded Views
    private val expandedScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        visibility = GONE
    }
    private val expandedList = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.START
    }
    private val collapseButton = actionButton(R.drawable.ic_expand_less, R.string.peek_collapse) {
        toggleExpanded()
    }
    private val markAllButton = actionButton(R.drawable.ic_done_all, R.string.peek_mark_all_read) {
        onInteraction?.invoke()
        onMarkAllRead?.invoke(entries)
    }
    private val expandedFooter = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(collapseButton, LayoutParams(-2, dp(36f)).apply { marginEnd = dp(8f) })
        addView(markAllButton, LayoutParams(-2, dp(36f)))
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        setBackgroundColor(Color.BLACK)

        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(180)
        }

        // Header
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(appIcon, LayoutParams(dp(16f), dp(16f)).apply { marginEnd = dp(8f) })
            addView(appName, LayoutParams(0, -2, 1f))
            addView(headerChevron, LayoutParams(dp(20f), dp(20f)))
            isClickable = true
            setOnClickListener {
                if (expandableEnabled && entries.isNotEmpty()) {
                    toggleExpanded()
                } else {
                    current?.let { onOpen?.invoke(it) }
                }
            }
        }
        addView(head, LayoutParams(-1, -2))

        // Collapsed layout
        collapsedContent.addView(title, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        collapsedContent.addView(body, LayoutParams(-1, -2).apply { topMargin = dp(2f) })

        val tags = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            addView(media, LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
            addView(expandButton, LayoutParams(-2, dp(32f)))
        }
        collapsedContent.addView(tags, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        collapsedContent.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        collapsedContent.isClickable = true
        collapsedContent.setOnClickListener {
            onInteraction?.invoke()
            if (expandableEnabled && entries.isNotEmpty()) {
                toggleExpanded()
            } else {
                current?.let { onOpen?.invoke(it) }
            }
        }

        addView(collapsedContent, LayoutParams(-1, -2))

        // Expanded layout
        expandedScroll.addView(expandedList, ViewGroup.LayoutParams(-1, -2))
        addView(expandedScroll, LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        alpha = 0f
        visibility = INVISIBLE
    }

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
        setOnClickListener {
            onInteraction?.invoke()
            onClick()
        }
    }

    private fun toggleExpanded() {
        onInteraction?.invoke()
        isExpanded = !isExpanded
        TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(220))
        updateExpandedState()
        onExpandedChanged?.invoke(isExpanded)
    }

    private fun updateExpandedState() {
        val count = entries.size
        headerChevron.visibility = if (expandableEnabled && count > 0) VISIBLE else GONE
        headerChevron.setImageResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)

        if (isExpanded) {
            collapsedContent.visibility = GONE
            expandedScroll.visibility = VISIBLE
            rebuildExpandedCards()
        } else {
            collapsedContent.visibility = VISIBLE
            expandedScroll.visibility = GONE
        }
    }

    private fun rebuildExpandedCards() {
        expandedList.removeAllViews()
        for (e in entries) {
            val card = buildMessageCard(e)
            expandedList.addView(card, LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        }
        // Footer: collapse + mark all read
        markAllButton.visibility = if (entries.size > 1 && actionsEnabled) VISIBLE else GONE
        expandedList.addView(expandedFooter, LayoutParams(-1, -2).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
    }

    private fun buildMessageCard(e: NotificationStore.Entry): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.START
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.BLACK)
                setStroke(dp(1f), (e.color and 0x00FFFFFF) or 0x44000000)
            }
            isClickable = true
            setOnClickListener {
                onInteraction?.invoke()
                onOpen?.invoke(e)
            }
        }

        // Card header line: Sender/title + time ago
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cardTitle = line(baseFontSize, 0.85f, 1).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = e.title ?: AppInfoView.labelFor(context.packageManager, e.pkg)
        }
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val cardTime = line((baseFontSize - 2.5f).coerceAtLeast(10f), 0.45f, 1).apply {
            text = ago
            gravity = Gravity.END
        }
        topRow.addView(cardTitle, LayoutParams(0, -2, 1f))
        topRow.addView(cardTime, LayoutParams(-2, -2))
        card.addView(topRow, LayoutParams(-1, -2))

        // Message text
        val text = e.lines.joinToString("\n")
        if (text.isNotBlank()) {
            val cardBody = line((baseFontSize - 0.5f).coerceAtLeast(11f), 0.65f, 100).apply {
                this.text = text
                setLineSpacing(0f, 1.15f)
            }
            card.addView(cardBody, LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        }

        // Media tag if any
        val label = NotificationContent.label(context, e.kind)
        if (label.isNotEmpty()) {
            val cardMedia = line((baseFontSize - 2.5f).coerceAtLeast(10f), 0.7f, 1).apply {
                this.text = label
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(Color.BLACK)
                    setStroke(dp(1f), (e.color and 0x00FFFFFF) or 0x66000000)
                }
            }
            card.addView(cardMedia, LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        }

        // Action buttons row for this message
        if (actionsEnabled && (e.replyAction != null || e.readAction != null || e.clearable)) {
            val cardActions = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            if (e.replyAction != null) {
                val btnReply = actionButton(R.drawable.ic_reply, R.string.peek_reply) {
                    onInteraction?.invoke()
                    onReply?.invoke(e)
                }
                cardActions.addView(btnReply, LayoutParams(-2, dp(32f)).apply { marginEnd = dp(8f) })
            }
            val btnDismiss = actionButton(
                if (e.readAction != null) R.drawable.ic_done_all else R.drawable.ic_check,
                if (e.readAction != null) R.string.peek_mark_read else R.string.peek_dismiss,
            ) {
                onInteraction?.invoke()
                onMarkRead?.invoke(e)
            }
            cardActions.addView(btnDismiss, LayoutParams(-2, dp(32f)))
            card.addView(cardActions, LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        }

        return card
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenH = resources.displayMetrics.heightPixels
        val maxH = (customMaxHeightDp * resources.displayMetrics.density).toInt()
            .coerceIn((120 * resources.displayMetrics.density).toInt(), (screenH * 0.85f).toInt())
        val hSpec = if (MeasureSpec.getSize(heightMeasureSpec) > maxH || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, hSpec)
    }

    /** Fills the peek with the notifications of an app. */
    fun bind(entries: List<NotificationStore.Entry>) {
        this.entries = entries
        val e = entries.firstOrNull() ?: return
        current = e

        val p = com.glowled.app.core.LedController.prefs
        applyCustomization(p.peekFontSizeSp.toFloat(), p.peekLines, p.peekHeightDp)

        val pm = context.packageManager
        val icon = AppInfoView.iconFor(pm, e.pkg)
        appIcon.setImageDrawable(icon)
        appIcon.visibility = if (icon == null) GONE else VISIBLE

        val appLabel = AppInfoView.labelFor(pm, e.pkg)
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        if (entries.size > 1) {
            appName.text = context.getString(R.string.peek_messages_count, appLabel, entries.size)
        } else {
            appName.text = context.getString(R.string.peek_app_time, appLabel, ago)
        }

        if (e.hidden) {
            title.setText(R.string.peek_new)
            body.setText(R.string.peek_hidden)
            body.visibility = VISIBLE
            media.visibility = GONE
        } else {
            title.text = e.title ?: appLabel
            val text = e.lines.joinToString("\n")
            body.text = text
            body.visibility = if (text.isBlank()) GONE else VISIBLE
            val label = NotificationContent.label(context, e.kind)
            media.text = label
            media.visibility = if (label.isEmpty()) GONE else VISIBLE
            media.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(Color.BLACK)
                setStroke(dp(1f), (e.color and 0x00FFFFFF) or 0x66000000)
            }
        }

        val extra = entries.size - 1
        if (expandableEnabled && entries.isNotEmpty()) {
            if (extra > 0) {
                expandButton.text = String.format(context.getString(R.string.peek_expand), entries.size)
            } else {
                expandButton.setText(R.string.peek_expand_single)
            }
            expandButton.visibility = VISIBLE
        } else {
            expandButton.visibility = GONE
        }

        (media.parent as? View)?.visibility = if (media.visibility == GONE && expandButton.visibility == GONE) GONE else VISIBLE

        reply.visibility = if (actionsEnabled && e.replyAction != null) VISIBLE else GONE
        markRead.setText(if (e.readAction != null) R.string.peek_mark_read else R.string.peek_dismiss)
        markRead.visibility = if (actionsEnabled && (e.readAction != null || e.clearable)) VISIBLE else GONE
        actions.visibility = if (reply.visibility == GONE && markRead.visibility == GONE) GONE else VISIBLE

        updateExpandedState()
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
            .withEndAction {
                if (!shown) {
                    visibility = INVISIBLE
                    isExpanded = false
                    updateExpandedState()
                }
            }.start()
    }
}
