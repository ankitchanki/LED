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
import android.widget.SeekBar
import com.glowled.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Small colour picker: AMOLED-friendly presets, a hue slider and a hex field. */
object ColorPickerDialog {

    private val presets = longArrayOf(
        0xFF00E5FF, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFFF4081, 0xFFFF1744,
        0xFFFF6D00, 0xFFFFC400, 0xFFFFEA00, 0xFFC6FF00, 0xFF00E676, 0xFF1DE9B6,
        0xFF25D366, 0xFF0088CC, 0xFFE1306C, 0xFF1877F2, 0xFFEA4335, 0xFFFFFFFF,
    ).map { it.toInt() }.toIntArray()

    /** [onPick] receives the chosen colour, or 0 when "Automatic" is chosen (if [allowAuto]). */
    fun show(context: Context, initial: Int, allowAuto: Boolean, onPick: (Int) -> Unit) {
        val dp = context.resources.displayMetrics.density
        var current = if (initial == 0) presets[0] else initial

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val preview = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = 16 * dp }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, (40 * dp).toInt()))

        val hex = EditText(context).apply {
            hint = context.getString(R.string.color_hex)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
        }
        var updating = false
        fun apply(c: Int, fromHex: Boolean = false) {
            current = c or 0xFF000000.toInt()
            (preview.background as GradientDrawable).setColor(current)
            if (!fromHex) {
                updating = true
                hex.setText(String.format("#%06X", current and 0xFFFFFF))
                updating = false
            }
        }

        val grid = GridLayout(context).apply { columnCount = 6 }
        val size = (40 * dp).toInt()
        presets.forEach { c ->
            val v = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((1 * dp).toInt(), 0x33FFFFFF)
                }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { apply(c) }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * dp).toInt()
        })

        val hue = SeekBar(context).apply {
            max = 360
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) apply(Color.HSVToColor(floatArrayOf(p.toFloat(), 1f, 1f)))
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        root.addView(hue, LinearLayout.LayoutParams(-1, (48 * dp).toInt()))

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
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ -> onPick(current) }
            .setNegativeButton(R.string.cancel, null)
        if (allowAuto) b.setNeutralButton(R.string.color_auto) { _, _ -> onPick(0) }
        b.show()
    }
}
