package com.glowled.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.glowled.app.R
import com.glowled.app.view.ColorWheelView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

/**
 * Colour picker: a ring of every hue you turn with a finger, saturation and brightness sliders,
 * AMOLED-friendly presets and a hex field.
 */
object ColorPickerDialog {

    private val presets = longArrayOf(
        0xFF00E5FF, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFFF4081, 0xFFFF1744,
        0xFFFF6D00, 0xFFFFC400, 0xFFFFEA00, 0xFFC6FF00, 0xFF00E676, 0xFF1DE9B6,
        0xFF25D366, 0xFF0088CC, 0xFFE1306C, 0xFF1877F2, 0xFFEA4335, 0xFFFFFFFF,
    ).map { it.toInt() }.toIntArray()

    /** [onPick] receives the chosen colour, or 0 when "Automatic" is chosen (if [allowAuto]). */
    fun show(context: Context, initial: Int, allowAuto: Boolean, onPick: (Int) -> Unit) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        var current = if (initial == 0) presets[0] else initial or 0xFF000000.toInt()
        val hsv = FloatArray(3).also { Color.colorToHSV(current, it) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }

        val wheel = ColorWheelView(context)
        root.addView(wheel, LinearLayout.LayoutParams(px(220), px(220)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })

        fun slider(label: Int): Pair<View, Slider> {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(context).apply {
                setText(label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(context.getColor(R.color.text_secondary))
            })
            val s = Slider(context).apply {
                valueFrom = 0f
                valueTo = 100f
                labelBehavior = LabelFormatter.LABEL_GONE
                trackActiveTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
                trackInactiveTintList = ColorStateList.valueOf(context.getColor(R.color.surface_high))
                thumbTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
                contentDescription = context.getString(label)
            }
            box.addView(s, LinearLayout.LayoutParams(-1, px(36)))
            return box to s
        }
        val (satBox, sat) = slider(R.string.color_saturation)
        val (valBox, value) = slider(R.string.color_brightness)
        root.addView(satBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        root.addView(valBox, LinearLayout.LayoutParams(-1, -2))

        val hex = EditText(context).apply {
            hint = context.getString(R.string.color_hex)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
        }
        var updating = false
        fun apply(c: Int, fromHex: Boolean = false, fromControls: Boolean = false) {
            current = c or 0xFF000000.toInt()
            if (!fromControls) {
                Color.colorToHSV(current, hsv)
                updating = true
                wheel.hue = hsv[0]
                sat.value = (hsv[1] * 100f).coerceIn(0f, 100f)
                value.value = (hsv[2] * 100f).coerceIn(0f, 100f)
                updating = false
            }
            wheel.centerColor = current
            if (!fromHex) {
                updating = true
                hex.setText(String.format("#%06X", current and 0xFFFFFF))
                updating = false
            }
        }
        fun fromControls() {
            if (updating) return
            apply(Color.HSVToColor(floatArrayOf(wheel.hue, sat.value / 100f, value.value / 100f)), fromControls = true)
        }
        wheel.onHueChanged = { fromControls() }
        sat.addOnChangeListener { sl, v, fromUser ->
            if (fromUser) {
                Haptics.level(sl, v / 100f)
                fromControls()
            }
        }
        value.addOnChangeListener { sl, v, fromUser ->
            if (fromUser) {
                Haptics.level(sl, v / 100f)
                fromControls()
            }
        }

        // Presets: two rows of nine.
        val grid = GridLayout(context).apply { columnCount = 9 }
        val size = px(28)
        presets.forEach { c ->
            val v = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(px(1), 0x33FFFFFF)
                }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { apply(c) }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins(px(3), px(3), px(3), px(3))
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })

        hex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) t.toLongOrNull(16)?.let { apply(it.toInt(), fromHex = true) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        root.addView(hex, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        apply(current)

        val b = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.color_title)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.ok) { _, _ -> onPick(current) }
            .setNegativeButton(R.string.cancel, null)
        if (allowAuto) b.setNeutralButton(R.string.color_auto) { _, _ -> onPick(0) }
        b.show()
    }
}
