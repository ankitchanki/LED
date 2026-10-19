package com.glowled.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.Gravity
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

    companion object {
        private const val GRID_TILE_DP = 104
    }

    data class Toggle(val title: String, val key: String, val def: Boolean, val dependsOn: (() -> Boolean)? = null)
    data class Range(val title: String, val key: String, val min: Int, val max: Int, val def: Int, val unit: String)
    /** A colour setting; with [allowAuto], 0 means "automatic". */
    data class ColorItem(val title: String, val key: String, val def: Int, val allowAuto: Boolean = false)
    data class GridItem(val icon: Int, val title: String, val sub: String, val onClick: () -> Unit)

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    /** Every toggle and colour tile has this height, so the grid lines up. */
    private val tileHeight = context.resources.getDimensionPixelSize(R.dimen.tile_height)
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
            strokeWidth = dp(1)
            strokeColor = context.getColor(R.color.glass_stroke)
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
                line.addView(toggleTile(line, it), LinearLayout.LayoutParams(0, tileHeight, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
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
            tile.contentDescription = t.title
            androidx.core.view.ViewCompat.setStateDescription(tile, context.getString(if (on) R.string.state_on else R.string.state_off))
        }
        read()
        tile.setOnClickListener {
            val on = !sp.getBoolean(t.key, t.def)
            Haptics.toggle(it, on)
            sp.edit().putBoolean(t.key, on).apply()
            refresh()
        }
        refreshers += read
        t.dependsOn?.let { dependents += tile to it }
        return tile
    }

    // ---- colour tiles: same size as the toggle tiles, a swatch and a one-line label ---------------

    fun colorTiles(parent: LinearLayout, items: List<ColorItem>, dependsOn: (() -> Boolean)? = null) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        items.forEach { line.addView(colorTile(it), LinearLayout.LayoutParams(0, tileHeight, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        dependsOn?.let { dependents += line to it }
    }

    private fun colorTile(item: ColorItem): View {
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(context.getColor(R.color.surface))
            radius = 18 * d
            strokeWidth = dp(1)
            strokeColor = context.getColor(R.color.glass_stroke)
            isClickable = true
            isFocusable = true
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        val swatch = View(context)
        row.addView(swatch, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        row.addView(oneLine(item.title, 13f), LinearLayout.LayoutParams(0, -1, 1f))
        card.addView(row, ViewGroup.LayoutParams(-1, -1))
        val read = {
            val c = sp.getInt(item.key, item.def)
            swatch.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                if (c == 0) {
                    setColor(context.getColor(R.color.surface_high))
                    setStroke(dp(2), context.getColor(R.color.accent))
                } else {
                    setColor(c)
                }
            }
        }
        read()
        card.setOnClickListener {
            haptic(it)
            ColorPickerDialog.show(context, sp.getInt(item.key, item.def), item.allowAuto) { picked ->
                sp.edit().putInt(item.key, picked).apply()
                refresh()
            }
        }
        refreshers += read
        return card
    }

    /** Single-line label that shrinks a little to fit instead of wrapping. */
    private fun oneLine(text: String, maxSp: Float, secondary: Boolean = false): TextView =
        androidx.appcompat.widget.AppCompatTextView(context).apply {
        this.text = text
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextAppearance(
            if (secondary) com.google.android.material.R.style.TextAppearance_Material3_BodySmall
            else com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
        )
        setTextColor(context.getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 9, maxSp.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP,
        )
    }

    // ---- grid of action tiles (no scrolling, everything side by side) --------------------------

    /** Square-ish tiles, [columns] per row. Returns each tile's subtitle so callers can update it. */
    fun grid(parent: LinearLayout, items: List<GridItem>, columns: Int = 3): List<TextView> {
        val subs = mutableListOf<TextView>()
        items.chunked(columns).forEach { chunk ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            chunk.forEach { item ->
                val card = MaterialCardView(context).apply {
                    setCardBackgroundColor(context.getColor(R.color.surface))
                    radius = 20 * d
                    strokeWidth = dp(1)
                    strokeColor = context.getColor(R.color.glass_stroke)
                    isClickable = true
                    isFocusable = true
                    contentDescription = item.title
                    setOnClickListener { haptic(it); item.onClick() }
                }
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(12), dp(8), dp(10))
                }
                col.addView(ImageView(context).apply {
                    setImageResource(item.icon)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                col.addView(oneLine(item.title, 14f).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(8) })
                val sub = oneLine(item.sub, 11f, secondary = true).apply { gravity = Gravity.CENTER }
                col.addView(sub, LinearLayout.LayoutParams(-1, dp(18)))
                subs += sub
                card.addView(col, ViewGroup.LayoutParams(-1, -1))
                line.addView(card, LinearLayout.LayoutParams(0, dp(GRID_TILE_DP), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            repeat(columns - chunk.size) { line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
            parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        return subs
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
                // Stronger ticks as the value goes up, softer as it goes down.
                Haptics.level(sl, (v - r.min) / (r.max - r.min).coerceAtLeast(1).toFloat())
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
