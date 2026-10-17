package com.glowled.app.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glowled.app.R
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.LedStyle
import com.glowled.app.view.LedView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.max
import kotlin.math.roundToInt

/** Lets the user line the ring up with the camera exactly; drag, sliders or 1 px nudges. */
class CutoutActivity : AppCompatActivity() {

    private lateinit var led: LedView
    private lateinit var shapes: ChipGroup
    private lateinit var animate: MaterialSwitch
    private val prefs get() = LedController.prefs

    private var shape = CutoutShape.CIRCLE
    private var cx = 0f
    private var cy = 0f
    private var w = 0f
    private var h = 0f
    private var gapDp = 2
    private var thicknessDp = 3
    private var screenW = 1
    private var screenH = 1

    private val sliders = mutableMapOf<String, Pair<SeekBar, TextView>>()
    private var syncing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedController.init(this)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_cutout)
        hideBars()

        led = findViewById(R.id.led)
        shapes = findViewById(R.id.shapes)
        animate = findViewById(R.id.animate)

        val size = CutoutGeometry.naturalSize(this)
        screenW = size.x
        screenH = size.y
        val g = CutoutGeometry.resolve(this, prefs)
        load(g)
        gapDp = prefs.gapDp
        thicknessDp = prefs.thicknessDp

        val box = findViewById<LinearLayout>(R.id.sliders)
        addSlider(box, "x", R.string.label_x, screenW) { cx = it.toFloat() }
        addSlider(box, "y", R.string.label_y, screenH / 3) { cy = it.toFloat() }
        addSlider(box, "w", R.string.label_w, screenW / 2) { w = max(2, it).toFloat(); if (shape.isRound()) h = w }
        addSlider(box, "h", R.string.label_h, screenH / 8) { h = max(2, it).toFloat(); if (shape.isRound()) w = h }
        addSlider(box, "gap", R.string.label_gap, 20) { gapDp = it }
        addSlider(box, "thick", R.string.label_thickness, 16) { thicknessDp = max(1, it) }

        shapes.setOnCheckedStateChangeListener { _, ids ->
            if (syncing) return@setOnCheckedStateChangeListener
            shape = when (ids.firstOrNull()) {
                R.id.shapePill -> CutoutShape.PILL
                R.id.shapeNotch -> CutoutShape.NOTCH
                R.id.shapeDot -> CutoutShape.DOT
                else -> CutoutShape.CIRCLE
            }
            if (shape.isRound()) h = w
            if (shape == CutoutShape.PILL && w <= h) w = h * 2.2f
            sync()
        }
        animate.setOnCheckedChangeListener { _, _ -> redraw() }

        findViewById<View>(R.id.left).setOnClickListener { cx -= 1; sync() }
        findViewById<View>(R.id.right).setOnClickListener { cx += 1; sync() }
        findViewById<View>(R.id.up).setOnClickListener { cy -= 1; sync() }
        findViewById<View>(R.id.down).setOnClickListener { cy += 1; sync() }

        findViewById<View>(R.id.btnAuto).setOnClickListener { autoDetect(showToast = true) }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            gapDp = 2; thicknessDp = 3
            autoDetect(showToast = true)
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }

        // Drag anywhere above the panel to move the ring.
        var lastX = 0f
        var lastY = 0f
        led.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    // Move at 1/3 speed for precision.
                    cx += (e.rawX - lastX) / 3f
                    cy += (e.rawY - lastY) / 3f
                    lastX = e.rawX; lastY = e.rawY
                    sync()
                }
            }
            true
        }
        sync()
    }

    private fun CutoutShape.isRound() = this == CutoutShape.CIRCLE || this == CutoutShape.DOT

    private fun load(g: Geometry) {
        shape = g.shape; cx = g.cx; cy = g.cy; w = g.w; h = g.h
    }

    private fun autoDetect(showToast: Boolean) {
        if (!CutoutGeometry.isPortrait(this)) {
            Toast.makeText(this, R.string.cut_rotate, Toast.LENGTH_SHORT).show()
            return
        }
        val g = CutoutGeometry.detect(this)
        if (g == null) {
            load(CutoutGeometry.fallback(this))
            if (showToast) Toast.makeText(this, R.string.cut_none, Toast.LENGTH_LONG).show()
        } else {
            load(g)
            if (showToast) Toast.makeText(this, getString(R.string.cut_detected, shapeName(g.shape)), Toast.LENGTH_SHORT).show()
        }
        sync()
    }

    private fun shapeName(s: CutoutShape) = getString(
        when (s) {
            CutoutShape.CIRCLE -> R.string.shape_circle
            CutoutShape.PILL -> R.string.shape_pill
            CutoutShape.NOTCH -> R.string.shape_notch
            CutoutShape.DOT -> R.string.shape_dot
        },
    )

    private fun addSlider(box: LinearLayout, key: String, label: Int, maxV: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val t = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            minWidth = (110 * resources.displayMetrics.density).toInt()
        }
        val s = SeekBar(this).apply {
            max = maxV
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && !syncing) {
                        onChange(p)
                        sync()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        t.tag = getString(label)
        row.addView(t)
        row.addView(s, LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f))
        box.addView(row)
        sliders[key] = s to t
    }

    /** Pushes the current values into the controls and the preview. */
    private fun sync() {
        syncing = true
        cx = cx.coerceIn(0f, screenW.toFloat())
        cy = cy.coerceIn(0f, screenH / 3f)
        fun set(key: String, v: Int, text: String) {
            val (s, t) = sliders[key] ?: return
            s.progress = v
            t.text = "${t.tag}  $text"
        }
        set("x", cx.roundToInt(), "${cx.roundToInt()} px")
        set("y", cy.roundToInt(), "${cy.roundToInt()} px")
        set("w", w.roundToInt(), "${w.roundToInt()} px")
        set("h", h.roundToInt(), "${h.roundToInt()} px")
        set("gap", gapDp, "$gapDp dp")
        set("thick", thicknessDp, "$thicknessDp dp")
        sliders["h"]?.first?.isEnabled = !shape.isRound()
        shapes.check(
            when (shape) {
                CutoutShape.CIRCLE -> R.id.shapeCircle
                CutoutShape.PILL -> R.id.shapePill
                CutoutShape.NOTCH -> R.id.shapeNotch
                CutoutShape.DOT -> R.id.shapeDot
            },
        )
        syncing = false
        redraw()
    }

    private fun redraw() {
        // Preview uses the unsaved gap/thickness.
        val style = if (animate.isChecked) prefs.style.takeIf { !it.isEdge } ?: LedStyle.PULSE else LedStyle.STATIC
        led.configure(prefs, Geometry(shape, cx, cy, w, h), intArrayOf(prefs.defaultColor), style, gapDp, thicknessDp)
    }

    private fun save() {
        prefs.saveCutout(shape, cx, cy, w, h, screenW)
        prefs.sp.edit().putInt("gap", gapDp).putInt("thickness", thicknessDp).apply()
        Toast.makeText(this, R.string.cut_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
