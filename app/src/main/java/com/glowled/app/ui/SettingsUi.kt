package com.glowled.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Builds the inline settings cards on the home screen. Every row writes straight to
 * SharedPreferences; rows can depend on a boolean key and dim when it is off.
 */
class SettingsUi(private val context: Context, private val sp: SharedPreferences) {

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val dependents = mutableListOf<Pair<View, () -> Boolean>>()
    private val refreshers = mutableListOf<() -> Unit>()

    /** Re-reads every row from prefs (after external changes) and updates dependencies. */
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

    // ---- containers ----------------------------------------------------------------------------

    fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(context, null, 0, R.style.GlowSectionTitle).apply { text = title },
            LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(20); topMargin = dp(24); bottomMargin = dp(10)
            })
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(context.getColor(R.color.surface))
            radius = 24 * d
            strokeWidth = 0
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(col)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
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
        minimumHeight = dp(64)
        setPadding(dp(20), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val dr = it.getDrawable(0); it.recycle(); dr
        }
    }

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

    // ---- rows ----------------------------------------------------------------------------------

    fun switch(
        parent: LinearLayout, title: String, sub: String?, key: String, def: Boolean,
        dependsOn: (() -> Boolean)? = null,
    ): View {
        val r = row()
        val sw = MaterialSwitch(context)
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(sw)
        val read = { sw.isChecked = sp.getBoolean(key, def) }
        read()
        sw.setOnCheckedChangeListener { v, on ->
            if (sp.getBoolean(key, def) != on) {
                haptic(v)
                sp.edit().putBoolean(key, on).apply()
                refresh()
            }
        }
        r.setOnClickListener { if (it.isEnabled) sw.toggle() }
        refreshers += read
        parent.addView(r)
        dependsOn?.let { dependents += r to it }
        return r
    }

    fun slider(
        parent: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, unit: String,
        dependsOn: (() -> Boolean)? = null,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(2))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val value = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(context.getColor(R.color.accent))
            setPadding(0, 0, dp(8), 0)
        }
        head.addView(texts(title, null), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        val s = Slider(context).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            trackActiveTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            trackInactiveTintList = ColorStateList.valueOf(context.getColor(R.color.surface_high))
            thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            haloRadius = dp(14)
            isTickVisible = false
            trackHeight = dp(6)
        }
        val read = {
            val v = sp.getInt(key, def).coerceIn(min, max)
            s.value = v.toFloat()
            value.text = "$v$unit"
        }
        read()
        s.addOnChangeListener { sl, v, fromUser ->
            value.text = "${v.toInt()}$unit"
            if (fromUser) {
                if (v.toInt() % 5 == 0) sl.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                sp.edit().putInt(key, v.toInt()).apply()
            }
        }
        box.addView(head)
        box.addView(s, LinearLayout.LayoutParams(-1, dp(44)))
        refreshers += read
        parent.addView(box)
        dependsOn?.let { dependents += box to it }
        return box
    }

    /** Single-choice chips. Values are stored as strings (compatible with the old list settings). */
    fun chips(
        parent: LinearLayout, title: String, key: String, labels: List<String>, values: List<String>, def: String,
        dependsOn: (() -> Boolean)? = null, onPick: ((String) -> Unit)? = null,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        box.addView(texts(title, null).apply { setPadding(dp(20), 0, dp(20), dp(6)) })
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
                onPick?.invoke(v)
                refresh()
            }
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(20), 0, dp(20), 0)
            addView(group)
        }
        box.addView(scroll)
        refreshers += read
        parent.addView(box)
        dependsOn?.let { dependents += box to it }
        return box
    }

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
            LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(20); marginEnd = dp(20); topMargin = dp(4); bottomMargin = dp(4) })
    }
}
