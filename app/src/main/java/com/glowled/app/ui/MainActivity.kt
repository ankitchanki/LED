package com.glowled.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glowled.app.R
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.LedStyle
import com.glowled.app.core.NotificationStore
import com.glowled.app.core.PowerGuard
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService
import com.glowled.app.util.OemHelper
import com.glowled.app.view.LedView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var master: MaterialSwitch
    private lateinit var masterCard: MaterialCardView
    private lateinit var masterLabel: TextView
    private lateinit var status: TextView
    private lateinit var hero: LedView
    private lateinit var heroLens: View
    private lateinit var setupList: LinearLayout
    private lateinit var swatches: LinearLayout
    private lateinit var styleAdapter: StyleAdapter
    private val prefs get() = LedController.prefs
    private val storeListener: () -> Unit = { runOnUiThread { refreshLive() } }
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent status and navigation bars; the black header and dock blend into them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_main)

        master = findViewById(R.id.master)
        masterCard = findViewById(R.id.masterCard)
        masterLabel = findViewById(R.id.masterLabel)
        status = findViewById(R.id.status)
        hero = findViewById(R.id.hero)
        heroLens = findViewById(R.id.heroLens)
        setupList = findViewById(R.id.setupList)
        swatches = findViewById(R.id.swatches)
        hero.useScreenCoords = false
        findViewById<TextView>(R.id.heroHint).setText(R.string.hero_hint)

        applyInsets()

        master.isChecked = prefs.enabled
        master.setOnCheckedChangeListener { v, on ->
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.enabled = on
            refreshLive()
        }
        masterCard.setOnClickListener { master.toggle() }

        findViewById<View>(R.id.btnPreview).setOnClickListener { LedController.startAod(pulse = false, preview = true) }
        findViewById<View>(R.id.btnTest).setOnClickListener { sendTest() }
        findViewById<View>(R.id.settingsRow).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.restrictedHint).visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE

        setupStyles()
        setupSwatches()
        hero.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> configureHero() }
    }

    private fun applyInsets() {
        val dock = findViewById<View>(R.id.dock)
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        val dockPadBottom = dock.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(dock) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left + (16 * density).toInt(), right = bars.right + (16 * density).toInt(),
                bottom = bars.bottom + dockPadBottom)
            // Content can scroll fully above the dock.
            v.post { scroll.updatePadding(bottom = v.height + (12 * density).toInt()) }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        master.isChecked = prefs.enabled
        buildSetup()
        refreshLive()
        LedController.addListener(storeListener)
    }

    override fun onPause() {
        super.onPause()
        LedController.removeListener(storeListener)
    }

    private fun previewColors(): IntArray =
        intArrayOf(prefs.defaultColor, 0xFF25D366.toInt(), 0xFFFF4081.toInt())

    /** Hero preview: ring around the stand-in lens, in view-local coordinates (never drifts on scroll). */
    private fun configureHero() {
        if (hero.width == 0) return
        val cx = heroLens.left + heroLens.width / 2f - hero.left
        val cy = heroLens.top + heroLens.height / 2f - hero.top
        val d = heroLens.width.toFloat()
        val g = Geometry(CutoutShape.CIRCLE, cx, cy, d, d)
        hero.configure(prefs, g, previewColors(), gapDp = 4, thicknessDp = 4)
    }

    private fun refreshLive() {
        val on = prefs.enabled
        masterLabel.setText(if (on) R.string.master_on else R.string.master_off)
        masterCard.setCardBackgroundColor(getColor(if (on) R.color.accent_dim else R.color.surface))
        val count = NotificationStore.colors().size
        val block = PowerGuard.blockReason(this, prefs)
        status.text = when {
            !NotifyListenerService.isEnabled(this) -> getString(R.string.status_need_listener)
            !on -> getString(R.string.status_off_hint)
            block != null -> getString(R.string.status_blocked, block)
            !NotificationStore.isEmpty() -> getString(R.string.status_active, count)
            else -> getString(R.string.status_waiting)
        }
        hero.animate().alpha(if (on) 1f else 0.25f).setDuration(200).start()
        configureHero()
        bindTiles()
        styleAdapter.notifyItemRangeChanged(0, styleAdapter.itemCount)
        updateSwatchSelection()
    }

    // ---- animation carousel ------------------------------------------------------------------

    private fun setupStyles() {
        val names = resources.getStringArray(R.array.style_names).map { it.substringBefore(" (") }
        val keys = resources.getStringArray(R.array.style_values)
        val styles = keys.map { LedStyle.from(it) }
        styleAdapter = StyleAdapter(styles, names)
        findViewById<RecyclerView>(R.id.styles).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = styleAdapter
            setHasFixedSize(true)
            post { scrollToPosition(styles.indexOf(prefs.style).coerceAtLeast(0)) }
        }
    }

    private inner class StyleHolder(v: View) : RecyclerView.ViewHolder(v) {
        val card = v as MaterialCardView
        val led: LedView = v.findViewById(R.id.styleLed)
        val lens: View = v.findViewById(R.id.styleLens)
        val name: TextView = v.findViewById(R.id.styleName)
    }

    private inner class StyleAdapter(val styles: List<LedStyle>, val names: List<String>) :
        RecyclerView.Adapter<StyleHolder>() {

        override fun getItemCount() = styles.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StyleHolder {
            val h = StyleHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_style, parent, false))
            h.led.useScreenCoords = false
            return h
        }

        override fun onBindViewHolder(h: StyleHolder, position: Int) {
            val style = styles[position]
            val selected = style == prefs.style
            h.name.text = names[position]
            h.card.strokeWidth = if (selected) (2 * density).toInt() else 0
            h.card.setCardBackgroundColor(getColor(if (selected) R.color.surface_high else R.color.surface))
            h.lens.visibility = if (style.isEdge) View.INVISIBLE else View.VISIBLE
            h.led.post {
                val cx = h.led.width / 2f
                val cy = h.led.height / 2f
                val d = 22 * density
                h.led.configure(prefs, Geometry(CutoutShape.CIRCLE, cx, cy, d, d), previewColors(), style, 3, 3)
            }
            h.card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                prefs.sp.edit().putString("style", style.key).apply()
                notifyItemRangeChanged(0, itemCount)
                configureHero()
            }
        }
    }

    // ---- colour row --------------------------------------------------------------------------

    private val presetColors = longArrayOf(
        0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFE040FB, 0xFFFF4081,
        0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF76FF03, 0xFF00E676, 0xFFFFFFFF,
    ).map { it.toInt() }

    private fun setupSwatches() {
        val size = (44 * density).toInt()
        val m = (5 * density).toInt()
        presetColors.forEach { c ->
            swatches.addView(swatchView(c, size), LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
        }
        // Custom colour button.
        val custom = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.surface_high))
            }
            contentDescription = getString(R.string.custom_colour)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            }, FrameLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt(), android.view.Gravity.CENTER))
            setOnClickListener {
                ColorPickerDialog.show(this@MainActivity, prefs.defaultColor, allowAuto = false) { col ->
                    prefs.defaultColor = col
                    refreshLive()
                }
            }
        }
        swatches.addView(custom, LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
        updateSwatchSelection()
    }

    private fun swatchView(color: Int, size: Int) = FrameLayout(this).apply {
        tag = color
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        contentDescription = String.format("#%06X", color and 0xFFFFFF)
        val ring = View(context).apply { setBackgroundResource(R.drawable.swatch_ring); visibility = View.GONE }
        val inset = (-4 * density).toInt()
        addView(ring, FrameLayout.LayoutParams(size, size))
        clipChildren = false
        ring.setPadding(inset, inset, inset, inset)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.defaultColor = color
            refreshLive()
        }
    }

    private fun updateSwatchSelection() {
        val current = prefs.defaultColor or 0xFF000000.toInt()
        for (i in 0 until swatches.childCount) {
            val v = swatches.getChildAt(i) as? FrameLayout ?: continue
            val c = v.tag as? Int ?: continue
            val sel = c == current
            v.getChildAt(0)?.visibility = if (sel) View.VISIBLE else View.GONE
            v.animate().scaleX(if (sel) 0.82f else 1f).scaleY(if (sel) 0.82f else 1f).setDuration(150).start()
        }
    }

    // ---- quick tiles -------------------------------------------------------------------------

    private fun bindTiles() {
        tile(R.id.tileCutout, R.drawable.ic_camera_ring, getString(R.string.action_cutout), getString(R.string.tile_cutout_sub), null) {
            startActivity(Intent(this, CutoutActivity::class.java))
        }
        tile(R.id.tileApps, R.drawable.ic_apps, getString(R.string.action_apps), getString(R.string.tile_apps_sub), null) {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        tile(R.id.tileAod, R.drawable.ic_moon, getString(R.string.tile_aod), onOff(prefs.aodEnabled), prefs.aodEnabled) {
            prefs.sp.edit().putBoolean("aod_enabled", !prefs.aodEnabled).apply()
            bindTiles()
        }
        tile(R.id.tileOverlay, R.drawable.ic_phone_on, getString(R.string.tile_overlay), onOff(prefs.overlayEnabled), prefs.overlayEnabled) {
            prefs.sp.edit().putBoolean("overlay_enabled", !prefs.overlayEnabled).apply()
            bindTiles()
        }
    }

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)

    /** [active] null = navigation tile; true/false = toggle tile with highlighted state. */
    private fun tile(id: Int, icon: Int, title: String, sub: String, active: Boolean?, onClick: () -> Unit) {
        val card = findViewById<MaterialCardView>(id)
        card.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
        card.findViewById<TextView>(R.id.tileTitle).text = title
        card.findViewById<TextView>(R.id.tileSub).text = sub
        val on = active == true
        card.setCardBackgroundColor(getColor(if (on) R.color.accent_dim else R.color.surface))
        card.findViewById<View>(R.id.tileIconBg).backgroundTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.surface_high))
        card.findViewById<ImageView>(R.id.tileIcon).imageTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.black else R.color.accent))
        card.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }

    // ---- setup checklist ---------------------------------------------------------------------

    private data class Step(
        val title: String,
        val desc: String,
        val done: Boolean,
        val required: Boolean,
        val isLink: Boolean = false,
        val action: () -> Unit,
    )

    private fun buildSetup() {
        val steps = mutableListOf<Step>()
        steps += Step(getString(R.string.perm_listener), getString(R.string.perm_listener_desc),
            NotifyListenerService.isEnabled(this), true) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        steps += Step(getString(R.string.perm_a11y), getString(R.string.perm_a11y_desc),
            LedAccessibilityService.instance != null || isA11yEnabled(), false) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        steps += Step(getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc),
            Settings.canDrawOverlays(this), false) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            steps += Step(getString(R.string.perm_post), getString(R.string.perm_post_desc), granted, false) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            steps += Step(getString(R.string.perm_fsi), getString(R.string.perm_fsi_desc), nm.canUseFullScreenIntent(), false) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        steps += Step(getString(R.string.perm_battery), getString(R.string.perm_battery_desc),
            pm.isIgnoringBatteryOptimizations(packageName), false) { requestBatteryExemption() }
        OemHelper.brand?.let { b ->
            steps += Step(getString(R.string.perm_oem, b), getString(R.string.perm_oem_desc), false, false, isLink = true) {
                OemHelper.openAutostart(this)
            }
        }

        val countable = steps.filter { !it.isLink }
        val done = countable.count { it.done }
        findViewById<TextView>(R.id.setupProgress).text =
            if (done == countable.size) getString(R.string.setup_all_done) else getString(R.string.setup_progress, done, countable.size)
        findViewById<LinearProgressIndicator>(R.id.setupBar).apply {
            max = countable.size
            setProgressCompat(done, true)
        }

        setupList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // Things still to do first; finished ones collapse to a quiet row at the bottom.
        steps.sortedBy { if (it.done && !it.isLink) 1 else 0 }.forEach { s ->
            val row = inflater.inflate(R.layout.item_setup, setupList, false) as MaterialCardView
            row.findViewById<TextView>(R.id.title).text = s.title
            val desc = row.findViewById<TextView>(R.id.desc)
            desc.text = s.desc
            val icon = row.findViewById<ImageView>(R.id.stateIcon)
            val bg = row.findViewById<View>(R.id.stateBg)
            val chevron = row.findViewById<View>(R.id.chevron)
            when {
                s.done && !s.isLink -> {
                    icon.setImageResource(R.drawable.ic_check)
                    icon.imageTintList = ColorStateList.valueOf(getColor(R.color.black))
                    bg.backgroundTintList = ColorStateList.valueOf(getColor(R.color.ok))
                    desc.visibility = View.GONE
                    chevron.visibility = View.INVISIBLE
                    row.alpha = 0.7f
                    row.isClickable = false
                }
                else -> {
                    icon.setImageResource(if (s.isLink) R.drawable.ic_apps else R.drawable.ic_add)
                    val tint = if (s.required) R.color.bad else R.color.warn
                    icon.imageTintList = ColorStateList.valueOf(getColor(R.color.black))
                    bg.backgroundTintList = ColorStateList.valueOf(getColor(tint))
                    row.setOnClickListener { s.action() }
                }
            }
            setupList.addView(row)
        }
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!OemHelper.tryStart(this, direct)) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun open(intent: Intent) {
        if (!OemHelper.tryStart(this, intent)) OemHelper.openAppInfo(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        buildSetup()
    }

    // ---- test notification -------------------------------------------------------------------

    private fun sendTest() {
        if (!NotifyListenerService.isEnabled(this)) {
            Toast.makeText(this, R.string.need_listener_first, Toast.LENGTH_SHORT).show()
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(TEST_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(TEST_CHANNEL, getString(R.string.channel_test), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val n = NotificationCompat.Builder(this, TEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.test_title))
            .setContentText(getString(R.string.test_text))
            .setColor(prefs.defaultColor)
            .setAutoCancel(true)
            .build()
        nm.notify(NotifyListenerService.TEST_TAG, 1, n)
        Toast.makeText(this, R.string.test_sent, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TEST_CHANNEL = "test"
    }
}
