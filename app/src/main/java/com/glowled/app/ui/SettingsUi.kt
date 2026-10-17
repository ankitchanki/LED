package com.glowled.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.glowled.app.R
import com.glowled.app.view.NestedScrollableHost
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

/**
 * Builds the inline settings on the home screen. Every control writes straight to
 * SharedPreferences; controls can depend on a condition and dim when it is false.
 */
class SettingsUi(private val context: Context, private val sp: SharedPreferences) {

    data class Toggle(val title: String, val key: String, val def: Boolean, val dependsOn: (() -> Boolean)? = null)
    data class Range(val title: String, val key: String, val min: Int, val max: Int, val def: Int, val unit: String)

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

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

    // ---- containers ----------------------------------------------------------------------------

    /** A pager page: scrolls only if its content is taller than the space available. */
    fun page(): Pair<View, LinearLayout> {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        return scroll to col
    }

    /** Rounded group for related rows. */
    fun card(parent: LinearLayout): LinearLayout {
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(context.getColor(R.color.surface))
            radius = 22 * d
            strokeWidth = 0
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(col)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        return col
    }

    private fun texts(title: String, sub: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(context.getColor(R.color.text_primary))
        })
        if (sub != null) addView(TextView(context).apply {
            text = sub
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(18), dp(8), dp(14), dp(8))
        isClickable = true
        isFocusable = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val dr = it.getDrawable(0); it.recycle(); dr
        }
    }

    // ---- toggles: 2-column grid of tiles -------------------------------------------------------

    fun toggles(parent: LinearLayout, items: List<Toggle>) {
        items.chunked(2).forEach { pair ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            pair.forEach {
                line.addView(toggleTile(line, it), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            // Keep a lone last tile at half width.
            if (pair.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun toggleTile(parent: ViewGroup, t: Toggle): View {
        val tile = LayoutInflater.from(context).inflate(R.layout.item_toggle, parent, false) as MaterialCardView
        val title = tile.findViewById<TextView>(R.id.toggleTitle)
        val state = tile.findViewById<ImageView>(R.id.toggleState)
        title.text = t.title
        val read = {
            val on = sp.getBoolean(t.key, t.def)
            tile.setCardBackgroundColor(context.getColor(if (on) R.color.accent_dim else R.color.surface))
            state.backgroundTintList = ColorStateList.valueOf(context.getColor(if (on) R.color.accent else R.color.surface_high))
            state.setImageResource(if (on) R.drawable.ic_check else 0)
            state.imageTintList = ColorStateList.valueOf(context.getColor(R.color.black))
            tile.isChecked = on
            tile.contentDescription = "${t.title}: ${if (on) "on" else "off"}"
        }
        read()
        tile.setOnClickListener {
            haptic(it)
            sp.edit().putBoolean(t.key, !sp.getBoolean(t.key, t.def)).apply()
            refresh()
        }
        refreshers += read
        t.dependsOn?.let { dependents += tile to it }
        return tile
    }

    // ---- sliders -------------------------------------------------------------------------------

    private fun sliderBox(r: Range): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(6), 0)
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val value = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(context.getColor(R.color.accent))
            setPadding(dp(4), 0, dp(8), 0)
        }
        head.addView(TextView(context).apply {
            text = r.title
            maxLines = 1
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(context.getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        val s = Slider(context).apply {
            valueFrom = r.min.toFloat()
            valueTo = r.max.toFloat()
            stepSize = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            trackActiveTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            trackInactiveTintList = ColorStateList.valueOf(context.getColor(R.color.surface_high))
            thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            haloRadius = dp(14)
            isTickVisible = false
            trackHeight = dp(6)
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
                if (v.toInt() % 5 == 0) sl.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                sp.edit().putInt(r.key, v.toInt()).apply()
            }
        }
        box.addView(head)
        box.addView(s, LinearLayout.LayoutParams(-1, dp(40)))
        refreshers += read
        return box
    }

    /** One or two sliders side by side. */
    fun sliders(parent: LinearLayout, a: Range, b: Range? = null, dependsOn: (() -> Boolean)? = null): View {
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(sliderBox(a), LinearLayout.LayoutParams(0, -2, 1f))
        if (b != null) line.addView(sliderBox(b), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(line)
        dependsOn?.let { dependents += line to it }
        return line
    }

    // ---- chips ---------------------------------------------------------------------------------

    /** Single-choice chips. Values are stored as strings (compatible with older versions). */
    fun chips(
        parent: LinearLayout, title: String, key: String, labels: List<String>, values: List<String>, def: String,
        dependsOn: (() -> Boolean)? = null,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        box.addView(TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(dp(18), 0, dp(18), dp(4))
        })
        val group = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            chipSpacingHorizontal = dp(8)
        }
        val chips = labels.mapIndexed { i, l ->
            Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                text = l
                isCheckable = true
                isCheckedIconVisible = false
                id = View.generateViewId()
                tag = values[i]
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.accent), context.getColor(R.color.surface_high)),
                )
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(context.getColor(R.color.black), context.getColor(R.color.text_primary)),
                ))
                chipStrokeWidth = 0f
                group.addView(this)
            }
        }
        val read = {
            val cur = sp.getString(key, def) ?: def
            (chips.firstOrNull { it.tag == cur } ?: chips.first { it.tag == def }).isChecked = true
        }
        read()
        group.setOnCheckedStateChangeListener { g, ids ->
            val v = g.findViewById<Chip>(ids.firstOrNull() ?: return@setOnCheckedStateChangeListener)?.tag as? String
                ?: return@setOnCheckedStateChangeListener
            if (sp.getString(key, def) != v) {
                haptic(g)
                sp.edit().putString(key, v).apply()
                refresh()
            }
        }
        box.addView(NestedScrollableHost(context).apply {
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(dp(18), 0, dp(18), 0)
                addView(group)
            })
        })
        refreshers += read
        parent.addView(box)
        dependsOn?.let { dependents += box to it }
        return box
    }

    // ---- links ---------------------------------------------------------------------------------

    /** Tappable row; returns the subtitle view so callers can update it. */
    fun link(parent: LinearLayout, icon: Int, title: String, sub: String?, onClick: () -> Unit): TextView? {
        val r = row()
        r.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        val t = texts(title, sub)
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        r.setOnClickListener { haptic(it); onClick() }
        parent.addView(r)
        return t.getChildAt(1) as? TextView
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.outline)) },
            LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(18); marginEnd = dp(18); topMargin = dp(2); bottomMargin = dp(2) })
    }
}
