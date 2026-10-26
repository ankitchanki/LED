package com.glowled.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.glowled.app.R
import com.glowled.app.view.GlowSwitch
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

/**
 * Builds the inline settings on the home screen as grouped lists, like the system settings on
 * iPhone: rounded groups of rows, each with a small coloured icon, a title and a switch or value.
 * Every control writes straight to SharedPreferences; controls can depend on a condition and dim
 * when it is false.
 */
class SettingsUi(private val context: Context, private val sp: SharedPreferences) {

    data class Toggle(val title: String, val key: String, val def: Boolean, val dependsOn: (() -> Boolean)? = null)
    data class Range(val title: String, val key: String, val min: Int, val max: Int, val def: Int, val unit: String)
    /** A colour setting; with [allowAuto], 0 means "automatic". */
    data class ColorItem(val title: String, val key: String, val def: Int, val allowAuto: Boolean = false)
    data class GridItem(val icon: Int, val title: String, val sub: String, val color: Int = 0, val onClick: () -> Unit)
    /** A setup step: [done] (tick), still needed, or [optional] (chevron). */
    data class StatusItem(
        val title: String, val done: Boolean, val optional: Boolean, val icon: Int = 0, val color: Int = 0,
        val onClick: () -> Unit,
    )

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val dependents = mutableListOf<Pair<View, () -> Boolean>>()
    private val refreshers = mutableListOf<() -> Unit>()

    /** Re-reads every control from prefs and updates dependencies. */
    fun refresh() {
        refreshers.forEach { it() }
        dependents.forEach { (v, enabled) -> setEnabledDeep(v, enabled()) }
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.alpha = if (on) 1f else 0.38f
        enableTree(v, on)
    }

    private fun enableTree(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) enableTree(v.getChildAt(i), on)
    }

    private fun haptic(v: View) = Haptics.click(v)

    /** Dims [v] (and makes it untappable) while [enabled] is false; re-checked on every refresh. */
    fun dependOn(v: View, enabled: () -> Boolean) {
        dependents += v to enabled
        setEnabledDeep(v, enabled())
    }

    // ---- containers ----------------------------------------------------------------------------

    private val pageCols = mutableListOf<LinearLayout>()
    private var bottomClearance = 0

    /**
     * Extra space at the bottom of every page, so its last row can scroll clear of whatever floats
     * over it (the Test pill and the navigation bar).
     */
    fun setBottomClearance(px: Int) {
        bottomClearance = px
        pageCols.forEach { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, dp(12) + px) }
    }

    /** A pager page: scrolls only if its content is taller than the space available. */
    fun page(): Pair<View, LinearLayout> {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12) + bottomClearance)
        }
        pageCols += col
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        return scroll to col
    }

    /** A new rounded group; returns the column its rows go into. */
    fun card(parent: LinearLayout): LinearLayout = newGroup(parent)

    private fun newGroup(parent: LinearLayout): LinearLayout {
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(context.getColor(R.color.surface))
            radius = 16 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = context.getColor(R.color.glass_stroke)
            tag = GROUP
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = ROWS
        }
        card.addView(col)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
        return col
    }

    /**
     * Where the next row goes: [parent] itself when it is a group, else the group just above it in
     * the page (rows added one after another share a group), else a new group.
     */
    private fun rows(parent: LinearLayout): LinearLayout {
        if (parent.tag == ROWS) return parent
        val last = if (parent.childCount > 0) parent.getChildAt(parent.childCount - 1) else null
        if (last?.tag == GROUP) return (last as ViewGroup).getChildAt(0) as LinearLayout
        return newGroup(parent)
    }

    /** Adds [row] to [group], with a hairline above it (indented past the icon) if it isn't the first. */
    private fun addRow(group: LinearLayout, row: View, hasIcon: Boolean = true) {
        if (group.childCount > 0) {
            group.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.outline)) },
                LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(if (hasIcon) 58 else 16) })
        }
        group.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(14), dp(4), dp(14), dp(4))
        isClickable = true
        isFocusable = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val dr = it.getDrawable(0); it.recycle(); dr
        }
    }

    /** The small rounded square with a white glyph at the start of a row. */
    private fun iconSquare(icon: Int, color: Int): View = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { setColor(color); cornerRadius = 8 * d }
        setPadding(dp(6), dp(6), dp(6), dp(6))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun addIcon(r: LinearLayout, icon: Pair<Int, Int>?) {
        if (icon == null) return
        r.addView(iconSquare(icon.first, icon.second), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
    }

    private fun title(text: CharSequence) = TextView(context).apply {
        this.text = text
        textSize = 16f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(context.getColor(R.color.text_primary))
    }

    private fun texts(title: String, sub: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(title(title))
        if (sub != null) addView(TextView(context).apply {
            text = sub
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(1), 0, 0)
        })
    }

    private fun value(text: String = "") = TextView(context).apply {
        this.text = text
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(context.getColor(R.color.text_secondary))
        setPadding(dp(8), 0, dp(2), 0)
    }

    private fun chevron() = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
        alpha = 0.7f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun addChevron(r: LinearLayout) = r.addView(chevron(), LinearLayout.LayoutParams(dp(20), dp(20)))

    // ---- switches ------------------------------------------------------------------------------

    fun toggles(parent: LinearLayout, items: List<Toggle>) {
        val g = rows(parent)
        items.forEach { t -> addRow(g, toggleRow(t), RowIcons.of(t.key) != null) }
    }

    private fun toggleRow(t: Toggle): View {
        val r = row()
        addIcon(r, RowIcons.of(t.key))
        r.addView(texts(t.title, null), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = GlowSwitch(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        r.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        val read = {
            val on = sp.getBoolean(t.key, t.def)
            sw.isChecked = on
            r.contentDescription = t.title
            ViewCompat.setStateDescription(r, context.getString(if (on) R.string.state_on else R.string.state_off))
        }
        read()
        ViewCompat.setAccessibilityDelegate(r, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = sp.getBoolean(t.key, t.def)
            }
        })
        r.setOnClickListener {
            val on = !sp.getBoolean(t.key, t.def)
            Haptics.toggle(it, on)
            sp.edit().putBoolean(t.key, on).apply()
            refresh()
        }
        refreshers += read
        t.dependsOn?.let { dependents += r to it }
        return r
    }

    // ---- setup steps: a row each, with a tick, an orange "Open" pill or a chevron -----------------

    fun statusTiles(parent: LinearLayout, items: List<StatusItem>) {
        val g = rows(parent)
        items.forEach { item ->
            val r = row()
            addIcon(r, if (item.icon != 0) item.icon to item.color else null)
            r.addView(texts(item.title, null), LinearLayout.LayoutParams(0, -2, 1f))
            when {
                item.done -> r.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(GREEN)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                item.optional -> {
                    r.addView(value(context.getString(R.string.setup_optional)))
                    addChevron(r)
                }
                else -> r.addView(TextView(context).apply {
                    setText(R.string.setup_allow)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF000000.toInt())
                    background = GradientDrawable().apply { setColor(context.getColor(R.color.warn)); cornerRadius = 14 * d }
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                })
            }
            r.contentDescription = item.title
            ViewCompat.setStateDescription(r, context.getString(if (item.done) R.string.setup_all_done else R.string.setup_open))
            r.setOnClickListener { haptic(it); item.onClick() }
            addRow(g, r, item.icon != 0)
        }
    }

    // ---- colours -------------------------------------------------------------------------------

    /** One colour: a row with a swatch. Several: one row of swatches with a label under each. */
    fun colorTiles(parent: LinearLayout, items: List<ColorItem>, dependsOn: (() -> Boolean)? = null) {
        val g = rows(parent)
        val v: View = if (items.size == 1) {
            val item = items[0]
            val r = row()
            addIcon(r, RowIcons.of(item.key))
            r.addView(texts(item.title, null), LinearLayout.LayoutParams(0, -2, 1f))
            val sw = View(context)
            r.addView(sw, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) })
            addChevron(r)
            bindSwatch(sw, item)
            r.contentDescription = item.title
            r.setOnClickListener { pick(it, item) }
            r
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                items.forEach { item ->
                    val cell = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, dp(6))
                        isClickable = true
                        isFocusable = true
                        contentDescription = item.title
                        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
                            val dr = it.getDrawable(0); it.recycle(); dr
                        }
                        setOnClickListener { pick(it, item) }
                    }
                    val sw = View(context)
                    cell.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)))
                    cell.addView(TextView(context).apply {
                        text = item.title
                        textSize = 12f
                        maxLines = 1
                        setTextColor(context.getColor(R.color.text_secondary))
                        setPadding(0, dp(6), 0, 0)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    bindSwatch(sw, item)
                    addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
        }
        addRow(g, v, items.size == 1 && RowIcons.of(items[0].key) != null)
        dependsOn?.let { dependents += v to it }
    }

    private fun bindSwatch(swatch: View, item: ColorItem) {
        val read = {
            val c = sp.getInt(item.key, item.def)
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (c == 0) {
                    setColor(context.getColor(R.color.surface_high))
                    setStroke(dp(2), context.getColor(R.color.accent))
                } else {
                    setColor(c)
                }
            }
        }
        read()
        refreshers += read
    }

    private fun pick(v: View, item: ColorItem) {
        haptic(v)
        ColorPickerDialog.show(context, sp.getInt(item.key, item.def), item.allowAuto) { picked ->
            sp.edit().putInt(item.key, picked).apply()
            refresh()
        }
    }

    // ---- action rows (the Settings tab's tools) -------------------------------------------------

    /** A row per action. Returns each row's subtitle so callers can update it. */
    fun grid(parent: LinearLayout, items: List<GridItem>): List<TextView> {
        val g = rows(parent)
        return items.mapIndexed { i, item ->
            val r = row()
            addIcon(r, item.icon to (item.color.takeIf { it != 0 } ?: RowIcons.palette(i)))
            val t = texts(item.title, item.sub)
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            addChevron(r)
            r.contentDescription = item.title
            r.setOnClickListener { haptic(it); item.onClick() }
            addRow(g, r)
            t.getChildAt(1) as TextView
        }
    }

    // ---- sliders -------------------------------------------------------------------------------

    private fun sliderBox(r: Range): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(2))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val value = value()
        head.addView(title(r.title).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        val s = Slider(context).apply {
            valueFrom = r.min.toFloat()
            valueTo = r.max.toFloat()
            stepSize = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            trackActiveTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            trackInactiveTintList = ColorStateList.valueOf(context.getColor(R.color.switch_off))
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            thumbElevation = 3 * d
            thumbRadius = dp(12)
            haloRadius = dp(16)
            haloTintList = ColorStateList.valueOf(context.getColor(R.color.accent_dim))
            isTickVisible = false
            trackHeight = dp(4)
            contentDescription = r.title
        }
        val read = {
            val v = sp.getInt(r.key, r.def).coerceIn(r.min, r.max)
            s.value = v.toFloat()
            value.text = "$v${r.unit}"
        }
        read()
        s.addOnChangeListener { sl, v, fromUser ->
            value.text = "${v.toInt()}${r.unit}"
            if (fromUser) {
                // Stronger ticks as the value goes up, softer as it goes down.
                Haptics.level(sl, (v - r.min) / (r.max - r.min).coerceAtLeast(1).toFloat())
                sp.edit().putInt(r.key, v.toInt()).apply()
            }
        }
        box.addView(head)
        box.addView(s, LinearLayout.LayoutParams(-1, dp(44)))
        refreshers += read
        return box
    }

    /** One or two sliders, each on its own row of the group. */
    fun sliders(parent: LinearLayout, a: Range, b: Range? = null, dependsOn: (() -> Boolean)? = null): View {
        val g = rows(parent)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sliderBox(a))
        if (b != null) {
            box.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.outline)) },
                LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16) })
            box.addView(sliderBox(b))
        }
        addRow(g, box, hasIcon = false)
        dependsOn?.let { dependents += box to it }
        return box
    }

    // ---- choices -------------------------------------------------------------------------------

    /**
     * A single choice, stored as a string (compatible with older versions). Up to four options
     * show as a segmented control; more open a list from a row that shows the current value.
     */
    fun chips(
        parent: LinearLayout, title: String, key: String, labels: List<String>, values: List<String>, def: String,
        dependsOn: (() -> Boolean)? = null,
    ): View {
        val g = rows(parent)
        val icon = RowIcons.of(key)
        fun current() = (sp.getString(key, def) ?: def).let { if (it in values) it else def }
        fun choose(v: String, from: View) {
            if (current() == v) return
            haptic(from)
            sp.edit().putString(key, v).apply()
            refresh()
        }
        val v: View = if (labels.size <= SEGMENTS_MAX) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addIcon(head, icon)
            head.addView(title(title), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(head)
            val seg = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(context.getColor(R.color.switch_off)); cornerRadius = 9 * d }
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val cells = labels.mapIndexed { i, l ->
                TextView(context).apply {
                    text = l
                    gravity = Gravity.CENTER
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), 0, dp(4), 0)
                    setTextColor(context.getColor(R.color.text_primary))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { choose(values[i], it) }
                    seg.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f))
                }
            }
            box.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val read = {
                val cur = current()
                cells.forEachIndexed { i, c ->
                    val on = values[i] == cur
                    c.isSelected = on
                    c.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    c.background = if (on) GradientDrawable().apply {
                        setColor(context.getColor(R.color.segment_selected)); cornerRadius = 7 * d
                    } else null
                    ViewCompat.setStateDescription(c, if (on) context.getString(R.string.selected) else null)
                }
            }
            read()
            refreshers += read
            box
        } else {
            val r = row()
            addIcon(r, icon)
            r.addView(texts(title, null), LinearLayout.LayoutParams(0, -2, 1f))
            val shown = value()
            r.addView(shown)
            addChevron(r)
            r.contentDescription = title
            val read = { shown.text = labels[values.indexOf(current())] }
            read()
            refreshers += read
            r.setOnClickListener {
                haptic(it)
                MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current())) { dlg, i ->
                        dlg.dismiss()
                        choose(values[i], r)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            r
        }
        addRow(g, v, icon != null)
        dependsOn?.let { dependents += v to it }
        return v
    }

    // ---- links ---------------------------------------------------------------------------------

    /** Tappable row; returns the subtitle view so callers can update it. */
    fun link(parent: LinearLayout, icon: Int, title: String, sub: String?, color: Int = BLUE, onClick: () -> Unit): TextView? {
        val g = rows(parent)
        val r = row()
        addIcon(r, icon to color)
        val t = texts(title, sub)
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        addChevron(r)
        r.setOnClickListener { haptic(it); onClick() }
        addRow(g, r)
        return t.getChildAt(1) as? TextView
    }

    /** Rows in a group already get a hairline between them. */
    @Suppress("UNUSED_PARAMETER")
    fun divider(parent: LinearLayout) = Unit

    companion object {
        private const val GROUP = "glow_group"
        private const val ROWS = "glow_rows"
        private const val SEGMENTS_MAX = 4
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
    }
}
