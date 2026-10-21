package com.glowled.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glowled.app.BuildConfig
import com.glowled.app.R
import com.glowled.app.applyAppTheme
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.DebugLog
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.LedStyle
import com.glowled.app.core.NotificationStore
import com.glowled.app.core.PowerGuard
import com.glowled.app.core.Prefs
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService
import com.glowled.app.ui.SettingsUi.Range
import com.glowled.app.ui.SettingsUi.Toggle
import com.glowled.app.ui.SettingsUi.ColorItem
import com.glowled.app.ui.SettingsUi.GridItem
import com.glowled.app.view.ReachLayout
import com.glowled.app.view.AppInfoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import com.glowled.app.util.AppSignature
import com.glowled.app.util.OemHelper
import com.glowled.app.util.SettingsBackup
import com.glowled.app.util.Privileged
import rikka.shizuku.Shizuku
import com.glowled.app.view.LedView
import com.glowled.app.view.NestedScrollableHost
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Single-screen home: header with master switch, live preview, swipeable setting pages, and a
 * thumb dock. Every setting lives here; nothing hides behind a menu.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var master: MaterialSwitch
    private lateinit var status: TextView
    /** Full-screen, touch-through layer that draws the ring on the phone's real camera cutout. */
    private lateinit var cameraLed: LedView
    private var introAnim: ValueAnimator? = null
    private lateinit var pager: ViewPager2
    private lateinit var tabs: TabLayout
    private lateinit var ui: SettingsUi
    private lateinit var styleAdapter: StyleAdapter
    private lateinit var swatches: LinearLayout
    private lateinit var setupList: LinearLayout
    private lateinit var setupProgress: TextView
    private lateinit var setupBar: LinearProgressIndicator
    private lateinit var reach: ReachLayout
    private var pageViews: List<View> = emptyList()
    private val meterPreviewEnd = Runnable { setMeters(null, 0) }
    /** Live preview of the screen-off light in the one-handed space. */
    private var previewLed: LedView? = null
    private var previewIcons: AppInfoView? = null
    private val previewApps by lazy { AodSession.sampleApps(this) }
    private var quietSub: TextView? = null
    private var setupIncomplete = false

    private val prefs get() = LedController.prefs
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()
    private val storeListener: () -> Unit = { runOnUiThread { refreshLive() } }

    // Any setting change re-renders the preview immediately.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "style") styleAdapter.notifyItemRangeChanged(0, styleAdapter.itemCount)
        // A new method choice gets a fresh chance to use the flicker-free lock-screen window.
        if (key == "aod_method") prefs.sp.edit().remove(LedController.PREF_WINDOW_BLOCKED).apply()
        configureCameraLed()
        configurePreviewRing()
        if (key in PREVIEW_ICON_KEYS) bindPreviewIcons()
        if (key == "droplet" && prefs.droplet) {
            // Show what the setting does: on the real camera, and in the one-handed preview.
            cameraLed.playDropletBelow(prefs.defaultColor, DROP_PREVIEW_DP * density, retract = true)
            playPreviewDroplet()
        }
        if (key == "app_theme") applyAppTheme(prefs.appTheme)
        // Visual feedback right on the camera when the look changes.
        if (key in REPLAY_KEYS) playCameraIntro()
        if (key != null && (key.startsWith("charge_") || key.startsWith("progress_") || key == "upload_color" || key == "download_color")) previewMeter(key)
        if (key == "style" || key == Prefs.K_ENABLED) ui.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        goEdgeToEdge()
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_main)
        preferMaxRefreshRate()
        // Draw into the camera cutout area so the ring can surround the real camera.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        cameraLed = LedView(this).apply {
            useScreenCoords = true
            uncapped = true
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        findViewById<FrameLayout>(android.R.id.content).addView(cameraLed, FrameLayout.LayoutParams(-1, -1))
        cameraLed.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> configureCameraLed() }

        master = findViewById(R.id.master)
        status = findViewById(R.id.status)
        pager = findViewById(R.id.pager)
        tabs = findViewById(R.id.tabs)
        ui = SettingsUi(this, prefs.sp)

        applyInsets()

        master.isChecked = prefs.enabled
        master.setOnCheckedChangeListener { v, on ->
            Haptics.toggle(v, on)
            prefs.enabled = on
            refreshLive()
        }
        findViewById<View>(R.id.btnTest).setOnClickListener {
            Haptics.click(it)
            sendTest()
        }

        buildPages()
        setupReach()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        // Shizuku's binder arrives asynchronously; refresh the setup card when it does.
        Shizuku.addBinderReceivedListenerSticky(shizukuListener)
        buildSetup()
        if (savedInstanceState == null && !getSharedPreferences(UI_STATE, MODE_PRIVATE).getBoolean("tutorial_seen", false)) {
            window.decorView.post { showTutorial(firstRun = true) }
        }
        // First run / something missing: open on Setup so the user sees what to do.
        if (savedInstanceState == null && setupIncomplete) pager.setCurrentItem(0, false)
        else if (savedInstanceState == null) pager.setCurrentItem(1, false)
    }

    /** One-handed mode: pull the settings down into thumb reach; back or a swipe up returns. */
    private fun setupReach() {
        reach = findViewById(R.id.root)
        reach.spacer = findViewById(R.id.reachSpace)
        reach.canContentScrollUp = { pageViews.getOrNull(pager.currentItem)?.canScrollVertically(-1) == true }
        reach.dragZoneBottom = { findViewById<View>(R.id.dock).top }
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = reach.close()
        }
        onBackPressedDispatcher.addCallback(this, back)
        val state = getSharedPreferences(UI_STATE, MODE_PRIVATE)
        buildReachPreview(findViewById(R.id.reachPreview))
        // Only animate the preview while it can be seen.
        reach.movable = listOf(tabs, pager)
        reach.onReachHeight = { h ->
            previewLed?.visibility = if (h > 0) View.VISIBLE else View.INVISIBLE
            // Just opened: show the droplet falling into the app icon once it's laid out.
            if (h > 0) previewLed?.postDelayed({ playPreviewDroplet() }, PREVIEW_DROP_DELAY_MS)
        }
        reach.onReachChanged = { open ->
            back.isEnabled = open
            state.edit().putBoolean(KEY_REACH_OPEN, open).apply()
        }
        // Stay in one-handed mode if that's how the app was left.
        if (state.getBoolean(KEY_REACH_OPEN, false)) reach.post { reach.open() }
    }

    /**
     * The one-handed space shows a small black "screen" with the ring around a camera dot and the
     * app icons, drawn by the same code as the real always-on light and updated with every change.
     */
    private fun buildReachPreview(host: FrameLayout) {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.BLACK)
            radius = 28 * density
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.glass_stroke)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val inner = FrameLayout(this)
        inner.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF101014.toInt()) }
        }, FrameLayout.LayoutParams(dp(PREVIEW_HOLE_DP), dp(PREVIEW_HOLE_DP), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(PREVIEW_CAM_DP - PREVIEW_HOLE_DP / 2)
        })
        val led = LedView(this).apply {
            useScreenCoords = false
            uncapped = true
            visibility = View.INVISIBLE
        }
        inner.addView(led, FrameLayout.LayoutParams(-1, -1))
        val icons = AppInfoView(this)
        inner.addView(icons, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(PREVIEW_CAM_DP + 30)
        })
        card.addView(inner, ViewGroup.LayoutParams(-1, -1))
        host.addView(card, FrameLayout.LayoutParams(-1, -1))
        previewLed = led
        previewIcons = icons
        bindPreviewIcons()
        // The ring is centred on the card's width: re-centre when that changes, after the layout
        // pass (configuring inside it would only take effect a frame later).
        led.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) led.post { configurePreviewRing() } }
    }

    private fun configurePreviewRing() {
        val led = previewLed ?: return
        if (led.width == 0) return
        val hole = dp(PREVIEW_HOLE_DP).toFloat()
        led.configure(prefs, Geometry(CutoutShape.CIRCLE, led.width / 2f, dp(PREVIEW_CAM_DP).toFloat(), hole, hole), previewColors())
    }

    /** Sample app icons in the AOD style chosen; only re-bound when a setting changes. */
    private fun bindPreviewIcons() {
        previewIcons?.apply {
            monochrome = prefs.aodIcons
            // One app, like a single new notification: the droplet falls into it.
            bind(previewApps.take(1))
            alpha = 1f
            visibility = if (prefs.aodEnabled && prefs.showAppInfo) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Preview of the droplet: a drop stretches out of the camera and turns into the app icon. */
    private fun playPreviewDroplet() {
        val led = previewLed ?: return
        val icons = previewIcons ?: return
        if (led.visibility != View.VISIBLE || led.width == 0 || icons.width == 0) return
        if (!prefs.droplet || !prefs.aodEnabled || !prefs.showAppInfo) return
        icons.prepareDrop()
        led.playDroplet(
            previewApps.firstOrNull()?.color ?: prefs.defaultColor,
            targetY = icons.top + icons.iconCenterY(),
            targetX = icons.left + icons.newestCenterX(),
        ) { icons.popNewest() }
    }

    private fun setMeters(fraction: Float?, color: Int) {
        if (::cameraLed.isInitialized) cameraLed.setMeter(fraction, color)
        previewLed?.setMeter(fraction, color)
    }

    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val dock = findViewById<View>(R.id.dock)
        val headerTop = header.paddingTop
        val dockBottom = dock.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.updatePadding(top = bars.top + headerTop, left = bars.left + dp(20), right = bars.right + dp(20))
            dock.updatePadding(bottom = bars.bottom + dockBottom, left = bars.left + dp(16), right = bars.right + dp(16))
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        LedController.appInForeground = true
        LedController.updateOverlay() // our own preview replaces the screen-on ring meanwhile
        master.isChecked = prefs.enabled
        buildSetup()
        refreshLive()
        LedController.addListener(storeListener)
        cameraLed.post { playCameraIntro() }
    }

    override fun onPause() {
        super.onPause()
        LedController.appInForeground = false
        LedController.updateOverlay()
        LedController.removeListener(storeListener)
        introAnim?.cancel()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        configureCameraLed()
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        Shizuku.removeBinderReceivedListener(shizukuListener)
        super.onDestroy()
    }

    private fun previewColors(): IntArray = intArrayOf(prefs.defaultColor, 0xFF25D366.toInt(), 0xFFFF4081.toInt())

    /** Preview colours: real notification colours when there are any, else a sample set. */
    private fun cameraColors(): IntArray = NotificationStore.colors().takeIf { it.isNotEmpty() } ?: previewColors()

    /**
     * Puts the live preview on the phone's real camera: auto-detected cutout (or the saved camera
     * position), in screen coordinates. Hidden in landscape and when Glow LED is switched off.
     */
    private fun configureCameraLed() {
        if (!::cameraLed.isInitialized) return
        val show = prefs.enabled && (CutoutGeometry.isPortrait(this) || prefs.style.isEdge)
        cameraLed.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) return
        cameraLed.configure(prefs, CutoutGeometry.resolve(this, prefs), cameraColors())
    }

    /** The ring grows out of the camera and fades in (same entrance as the always-on screen). */
    private fun playCameraIntro() {
        if (!::cameraLed.isInitialized || cameraLed.visibility != View.VISIBLE) return
        introAnim?.cancel()
        introAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { cameraLed.intro = it.animatedValue as Float }
            start()
        }
        cameraLed.restartAnimation()
        previewLed?.restartAnimation()
    }

    /** Shows the charging / download ring on the real camera for a moment while it's being set up. */
    private fun previewMeter(key: String) {
        if (!::cameraLed.isInitialized || cameraLed.visibility != View.VISIBLE) return
        val on = if (key.startsWith("charge_")) prefs.chargeLed else prefs.progressLed
        cameraLed.removeCallbacks(meterPreviewEnd)
        if (!on) {
            setMeters(null, 0)
            return
        }
        if (key.startsWith("charge_")) {
            val level = PowerGuard.batteryLevel(this)
            setMeters(level / 100f, prefs.chargeColor(level))
        } else {
            val color = when (key) {
                "upload_color" -> prefs.uploadColor
                "download_color" -> prefs.downloadColor
                else -> prefs.progressColor.takeIf { it != 0 } ?: prefs.defaultColor
            }
            setMeters(0.62f, color)
        }
        cameraLed.postDelayed(meterPreviewEnd, METER_PREVIEW_MS)
    }

    private fun refreshLive() {
        val on = prefs.enabled
        val count = NotificationStore.colors().size
        val block = PowerGuard.blockReason(this, prefs)
        status.text = when {
            !NotifyListenerService.isEnabled(this) -> getString(R.string.status_need_listener)
            !on -> getString(R.string.master_off)
            block != null -> getString(R.string.status_blocked, block)
            !NotificationStore.isEmpty() -> getString(R.string.status_active, count)
            else -> getString(R.string.status_waiting)
        }
        configureCameraLed()
        ui.refresh()
        updateSwatchSelection()
    }

    // ---- pages -------------------------------------------------------------------------------

    private fun buildPages() {
        val pages = listOf(
            getString(R.string.tab_setup) to pageSetup(),
            getString(R.string.tab_style) to pageStyle(),
            getString(R.string.tab_off) to pageScreenOff(),
            getString(R.string.tab_on) to pageScreenOn(),
            getString(R.string.tab_alerts) to pageAlerts(),
            getString(R.string.tab_battery) to pageBattery(),
            getString(R.string.tab_settings) to pageSettings(),
            getString(R.string.tab_log) to pageMore(),
        )
        pageViews = pages.map { it.second }
        pager.adapter = PagesAdapter(pageViews)
        pager.offscreenPageLimit = pages.size
        TabLayoutMediator(tabs, pager) { tab, i -> tab.text = pages[i].first }.attach()
        ui.refresh()
    }

    private class PagesAdapter(val pages: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = pages[viewType]
            (v.parent as? ViewGroup)?.removeView(v)
            v.layoutParams = ViewGroup.LayoutParams(-1, -1)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private fun pageSetup(): View {
        val (page, col) = ui.page()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        head.addView(TextView(this).apply {
            setText(R.string.setup_intro)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setupProgress = TextView(this).apply {
            setTextColor(getColor(R.color.accent))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setPadding(dp(8), 0, 0, 0)
        }
        head.addView(setupProgress)
        col.addView(head)
        setupBar = LinearProgressIndicator(this).apply {
            setIndicatorColor(getColor(R.color.accent))
            trackColor = getColor(R.color.surface_high)
            trackCornerRadius = dp(4)
            trackThickness = dp(6)
        }
        col.addView(setupBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(10)) })
        // Most important first (notification access, display over apps); autostart and the
        // optional Shizuku/root one-tap setup come last.
        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupList)
        shellBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(shellBox)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            col.addView(TextView(this).apply {
                setText(R.string.restricted_hint)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(dp(8), dp(6), dp(8), 0)
            })
        }
        return page
    }

    private fun pageStyle(): View {
        val (page, col) = ui.page()
        // Animation carousel with live previews.
        val names = resources.getStringArray(R.array.style_names).map { it.substringBefore(" (") }
        val styles = resources.getStringArray(R.array.style_values).map { LedStyle.from(it) }
        styleAdapter = StyleAdapter(styles, names)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            adapter = styleAdapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(4), 0, dp(4))
            post { scrollToPosition(styles.indexOf(prefs.style).coerceAtLeast(0)) }
        }
        col.addView(NestedScrollableHost(this).apply { addView(rv) })

        // Colour row.
        swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), 0, dp(2), 0) }
        buildSwatches()
        col.addView(NestedScrollableHost(this).apply {
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(swatches)
            })
        })

        val c = ui.card(col)
        ui.link(c, R.drawable.ic_camera_ring, getString(R.string.action_cutout), getString(R.string.tile_cutout_sub)) {
            startActivity(Intent(this, CutoutActivity::class.java))
        }
        ui.divider(c)
        ui.chips(c, getString(R.string.chips_multi), "multi_mode",
            labels(R.array.multi_labels), listOf("segments", "cycle", "latest"), "segments")
        ui.sliders(c, Range(getString(R.string.s_speed), "speed", 1, 10, 5, ""),
            Range(getString(R.string.s_intensity), "led_alpha", 10, 100, 100, "%"))
        ui.sliders(c, Range(getString(R.string.s_thickness), "thickness", 1, 16, 3, "dp"),
            Range(getString(R.string.s_gap), "gap", 0, 20, 2, "dp"))
        ui.sliders(c, Range(getString(R.string.s_edge), "edge_radius", 0, 120, 40, "dp"), dependsOn = { prefs.style.isEdge })
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_glow), "glow", true),
            Toggle(getString(R.string.t_rotate), "rotate_ring", true),
            Toggle(getString(R.string.t_notif_colour), "use_notif_color", true),
            Toggle(getString(R.string.t_icon_colour), "use_icon_color", true),
        ))
        return page
    }

    private fun pageSettings(): View {
        val (page, col) = ui.page()
        val look = ui.card(col)
        ui.chips(look, getString(R.string.chips_app_theme), "app_theme",
            labels(R.array.theme_labels), listOf("dark", "light", "system"), "dark")
        ui.toggles(col, listOf(Toggle(getString(R.string.t_haptics), "haptics", true)))
        return page
    }

    private fun pageScreenOff(): View {
        val (page, col) = ui.page()
        val aodOn = { prefs.aodEnabled }
        val iconsOn = { prefs.aodEnabled && prefs.showAppInfo }
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_aod), "aod_enabled", true),
            Toggle(getString(R.string.t_battery_indicator), "battery_indicator", true),
            Toggle(getString(R.string.t_on_screen_off), "aod_on_screen_off", true, aodOn),
            Toggle(getString(R.string.t_on_new), "aod_on_new", true, aodOn),
            Toggle(getString(R.string.t_pocket), "proximity", true, aodOn),
            Toggle(getString(R.string.t_double_tap), "double_tap_wake", true, aodOn),
            Toggle(getString(R.string.t_burn_in), "burn_in", true, aodOn),
            Toggle(getString(R.string.t_app_icons), "show_app_info", true, aodOn),
            Toggle(getString(R.string.t_keep_icons), "app_info_always", false, iconsOn),
            Toggle(getString(R.string.t_aod_icons), "aod_icons", true, iconsOn),
            Toggle(getString(R.string.t_tap_read), "show_content", true, iconsOn),
            Toggle(getString(R.string.t_peek_actions), "peek_actions", true) { iconsOn() && prefs.showContent },
            Toggle(getString(R.string.t_droplet), "droplet", true),
        ))
        val c = ui.card(col)
        ui.chips(c, getString(R.string.chips_method), "aod_method",
            labels(R.array.method_labels), listOf("auto", "activity", "overlay"), "auto", aodOn)
        ui.chips(c, getString(R.string.chips_off_delay), "off_delay",
            labels(R.array.off_delay_labels), listOf("800", "1500", "3000"), "800", aodOn)
        ui.chips(c, getString(R.string.chips_timeout), "aod_timeout",
            labels(R.array.timeout_labels), listOf("30", "60", "120", "300", "900", "3600", "0"), "120", aodOn)
        ui.chips(c, getString(R.string.chips_remind), "pulse_interval",
            labels(R.array.remind_labels), listOf("0", "2", "5", "10", "30"), "0", aodOn)
        ui.sliders(c, Range(getString(R.string.s_brightness), "aod_brightness", 1, 100, 3, "%"),
            Range(getString(R.string.s_reminder), "pulse_duration", 3, 60, 10, "s"), dependsOn = aodOn)
        return page
    }

    private fun pageScreenOn(): View {
        val (page, col) = ui.page()
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_ring_on), "overlay_enabled", true),
            Toggle(getString(R.string.t_lock_only), "overlay_lock_only", false) { prefs.overlayEnabled },
            Toggle(getString(R.string.t_charge), "charge_led", true),
            Toggle(getString(R.string.t_progress), "progress_led", true),
            Toggle(getString(R.string.t_battery_indicator), "battery_indicator", true),
            Toggle(getString(R.string.t_progress_initials), "progress_initials", true) { prefs.progressLed },
            Toggle(getString(R.string.t_progress_app_color), "progress_app_color", true) { prefs.progressLed },
        ))
        col.addView(label(getString(R.string.charge_colours)))
        ui.colorTiles(col, listOf(
            ColorItem(getString(R.string.c_low), "charge_color_low", 0xFFFF1744.toInt()),
            ColorItem(getString(R.string.c_mid), "charge_color_mid", 0xFFFFC400.toInt()),
            ColorItem(getString(R.string.c_high), "charge_color_high", 0xFF00E676.toInt()),
        ), dependsOn = { prefs.chargeLed })
        col.addView(label(getString(R.string.progress_colour)))
        ui.colorTiles(col, listOf(
            ColorItem(getString(R.string.c_progress), "progress_color", 0, allowAuto = true),
        ), dependsOn = { prefs.progressLed })
        ui.colorTiles(col, listOf(
            ColorItem(getString(R.string.c_upload), "upload_color", 0xFF00E676.toInt()),
            ColorItem(getString(R.string.c_download), "download_color", 0xFF2979FF.toInt()),
        ), dependsOn = { prefs.progressLed && !prefs.progressAppColor && prefs.progressColor == 0 })
        col.addView(hint(getString(R.string.transfer_hint)))
        col.addView(hint(getString(R.string.screen_on_hint)))
        return page
    }

    private fun pageAlerts(): View {
        val (page, col) = ui.page()
        val c = ui.card(col)
        ui.link(c, R.drawable.ic_apps, getString(R.string.action_apps), getString(R.string.apps_sub)) {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        ui.divider(c)
        ui.chips(c, getString(R.string.chips_which_apps), "app_mode", labels(R.array.appmode_labels), listOf("all", "selected"), "all")
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_ignore_silent), "ignore_low", true),
            Toggle(getString(R.string.t_dnd), "respect_dnd", true),
            Toggle(getString(R.string.t_clear_unlock), "clear_on_unlock", false),
        ))
        return page
    }

    private fun pageBattery(): View {
        val (page, col) = ui.page()
        val c = ui.card(col)
        ui.chips(c, getString(R.string.chips_fps), "fps_cap",
            labels(R.array.fps_labels), listOf("15", "24", "30", "60", "0"), "30")
        ui.sliders(c, Range(getString(R.string.s_min_battery), "min_battery", 0, 50, 10, "%"))
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_low_refresh), "low_refresh", true),
            Toggle(getString(R.string.t_eco), "eco_anim", true),
            Toggle(getString(R.string.t_age_fade), "age_fade", true),
            Toggle(getString(R.string.t_motion), "motion_glance", true),
            Toggle(getString(R.string.t_quiet), "quiet_enabled", false),
        ))
        val q = ui.card(col)
        quietSub = ui.link(q, R.drawable.ic_moon, getString(R.string.quiet_time), quietText()) { pickQuietHours() }
        col.addView(hint(getString(R.string.battery_hint)))
        return page
    }

    // Settings backup/restore through the system file picker (no storage permission needed).
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = SettingsBackup.write(this, prefs.sp, uri)
        Toast.makeText(this, if (ok) R.string.backup_done else R.string.backup_failed, Toast.LENGTH_SHORT).show()
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val n = SettingsBackup.read(this, prefs.sp, uri)
        if (n < 0) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.restore_done, n), Toast.LENGTH_SHORT).show()
            recreate() // rebuild every control from the restored values
        }
    }

    /** More: everything as a grid of tiles side by side, so the page never needs scrolling. */
    private fun pageMore(): View {
        val (page, col) = ui.page()
        val official = AppSignature.isOfficial(this)
        ui.grid(col, listOf(
            GridItem(R.drawable.ic_play, getString(R.string.tutorial_title), getString(R.string.tutorial_sub)) { showTutorial() },
            GridItem(if (official) R.drawable.ic_verified else R.drawable.ic_bell,
                getString(if (official) R.string.g_official else R.string.g_unofficial),
                getString(R.string.g_version, BuildConfig.VERSION_NAME)) {
                open(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            },
            GridItem(R.drawable.ic_download, getString(R.string.g_download), getString(R.string.g_download_sub)) {
                open(Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_APK_URL)))
            },
            GridItem(R.drawable.ic_language, getString(R.string.g_language), currentLanguageName()) { pickLanguage() },
            GridItem(R.drawable.ic_save, getString(R.string.g_backup), getString(R.string.g_backup_sub)) {
                backupLauncher.launch("glowled-settings.json")
            },
            GridItem(R.drawable.ic_restore, getString(R.string.g_restore), getString(R.string.g_restore_sub)) {
                restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            GridItem(R.drawable.ic_log, getString(R.string.g_log), getString(R.string.g_log_sub)) { showLog() },
        ))
        col.addView(hint(getString(R.string.reach_tip)))
        col.addView(hint(getString(R.string.sig_key, AppSignature.short(AppSignature.sha256(this))) +
            " · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
        return page
    }

    private fun showTutorial(firstRun: Boolean = false) {
        val sections = listOf(
            R.string.tutorial_swipe, R.string.tutorial_test, R.string.tutorial_setup,
            R.string.tutorial_styles, R.string.tutorial_transfers, R.string.tutorial_battery,
            R.string.tutorial_messages, R.string.tutorial_power,
        )
        var step = 0
        fun showStep() {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.tutorial_step, step + 1, sections.size))
                .setMessage(sections[step])
                .setPositiveButton(if (step == sections.lastIndex) R.string.tutorial_done else R.string.tutorial_next) { _, _ ->
                    if (step < sections.lastIndex) { step++; showStep() }
                    else if (firstRun) getSharedPreferences(UI_STATE, MODE_PRIVATE).edit().putBoolean("tutorial_seen", true).apply()
                }
                .setNegativeButton(R.string.tutorial_later) { _, _ ->
                    if (firstRun) getSharedPreferences(UI_STATE, MODE_PRIVATE).edit().putBoolean("tutorial_seen", true).apply()
                }
            if (step > 0) dialog.setNeutralButton(R.string.tutorial_back) { _, _ -> step--; showStep() }
            dialog.show()
        }
        showStep()
    }

    private fun labels(array: Int) = resources.getStringArray(array).toList()

    /** Small section label above a row of tiles. */
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(2))
    }

    private fun showLog() {
        val text = deviceInfo() + "\n\n" + DebugLog.text()
        val tv = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(22), dp(8), dp(22), dp(8))
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.g_log)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.log_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Glow LED log", text))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.log_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                open(Intent.createChooser(send, getString(R.string.g_log)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---- language -----------------------------------------------------------------------------

    private val languageTags = arrayOf("", "en", "pt-BR")

    private fun languageNames() = arrayOf(getString(R.string.lang_system), "English", "Português (Brasil)")

    private fun currentLanguageName(): String {
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val i = languageTags.indexOfFirst { it.equals(cur, ignoreCase = true) }
        return languageNames()[if (i < 0) 0 else i]
    }

    private fun pickLanguage() {
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = languageTags.indexOfFirst { it.equals(cur, ignoreCase = true) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.g_language)
            .setSingleChoiceItems(languageNames(), selected) { d, i ->
                d.dismiss()
                if (i != selected) {
                    AppCompatDelegate.setApplicationLocales(
                        if (languageTags[i].isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(languageTags[i]),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(dp(10), dp(10), dp(10), dp(4))
    }

    private fun deviceInfo() =
        "Glow LED ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}), a11y=${LedAccessibilityService.instance != null}, " +
            "overlay=${Settings.canDrawOverlays(this)}, listener=${NotifyListenerService.isEnabled(this)}, " +
            "miuiLock=${OemHelper.miuiLockScreenAllowed(this)}"

    private fun quietText(): String {
        fun f(m: Int) = String.format("%02d:%02d", m / 60, m % 60)
        return "${f(prefs.quietStart)} – ${f(prefs.quietEnd)}"
    }

    private fun pickQuietHours() {
        val is24 = DateFormat.is24HourFormat(this)
        TimePickerDialog(this, { _, h, m ->
            prefs.sp.edit().putInt("quiet_start", h * 60 + m).apply()
            TimePickerDialog(this, { _, h2, m2 ->
                prefs.sp.edit().putInt("quiet_end", h2 * 60 + m2).apply()
                quietSub?.text = quietText()
            }, prefs.quietEnd / 60, prefs.quietEnd % 60, is24).apply { setTitle(R.string.quiet_end) }.show()
        }, prefs.quietStart / 60, prefs.quietStart % 60, is24).apply { setTitle(R.string.quiet_start) }.show()
    }

    // ---- animation carousel --------------------------------------------------------------------

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
            h.led.uncapped = true
            return h
        }

        override fun onBindViewHolder(h: StyleHolder, position: Int) {
            val style = styles[position]
            val selected = style == prefs.style
            h.name.text = names[position]
            h.card.strokeWidth = if (selected) dp(2) else dp(1)
            h.card.strokeColor = getColor(if (selected) R.color.accent else R.color.glass_stroke)
            h.card.setCardBackgroundColor(getColor(if (selected) R.color.surface_high else R.color.surface))
            h.lens.visibility = if (style.isEdge) View.INVISIBLE else View.VISIBLE
            h.led.post {
                val d = 22 * density
                h.led.configure(prefs, Geometry(CutoutShape.CIRCLE, h.led.width / 2f, h.led.height / 2f, d, d),
                    previewColors(), style, 3, 3)
            }
            h.card.setOnClickListener {
                Haptics.click(it)
                prefs.sp.edit().putString("style", style.key).apply()
            }
        }
    }

    // ---- colour row ----------------------------------------------------------------------------

    private val presetColors = longArrayOf(
        0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFE040FB, 0xFFFF4081,
        0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF76FF03, 0xFF00E676, 0xFFFFFFFF,
    ).map { it.toInt() }

    private fun buildSwatches() {
        val size = dp(40)
        val m = dp(5)
        presetColors.forEach { c ->
            swatches.addView(FrameLayout(this).apply {
                tag = c
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                addView(View(context).apply { setBackgroundResource(R.drawable.swatch_ring); visibility = View.GONE },
                    FrameLayout.LayoutParams(size, size))
                setOnClickListener {
                    Haptics.click(it)
                    prefs.defaultColor = c
                    updateSwatchSelection()
                }
            }, LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
        }
        swatches.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.surface_high)) }
            contentDescription = getString(R.string.custom_colour)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener {
                ColorPickerDialog.show(this@MainActivity, prefs.defaultColor, allowAuto = false) { col ->
                    prefs.defaultColor = col
                    updateSwatchSelection()
                }
            }
        }, LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
    }

    private fun updateSwatchSelection() {
        if (!::swatches.isInitialized) return
        val current = prefs.defaultColor or 0xFF000000.toInt()
        for (i in 0 until swatches.childCount) {
            val v = swatches.getChildAt(i) as? FrameLayout ?: continue
            val c = v.tag as? Int ?: continue
            val sel = c == current
            v.getChildAt(0)?.visibility = if (sel) View.VISIBLE else View.GONE
            v.animate().scaleX(if (sel) 0.82f else 1f).scaleY(if (sel) 0.82f else 1f).setDuration(150).start()
        }
    }

    // ---- setup checklist -----------------------------------------------------------------------

    private data class Step(
        val title: String,
        val desc: String,
        val done: Boolean,
        val counts: Boolean = true,
        val isLink: Boolean = false,
        val action: () -> Unit,
    )

    private fun buildSetup() {
        if (!::setupList.isInitialized) return
        buildShellCard()
        val a11y = LedAccessibilityService.instance != null || isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val steps = mutableListOf<Step>()
        steps += Step(getString(R.string.perm_listener), getString(R.string.perm_listener_desc),
            NotifyListenerService.isEnabled(this)) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        // Either one is enough to show the light over the lock screen; accessibility is optional.
        steps += Step(getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc), overlay || a11y) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            steps += Step(getString(R.string.perm_post), getString(R.string.perm_post_desc), granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        steps += Step(getString(R.string.perm_a11y), getString(R.string.perm_a11y_desc), a11y, counts = false) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val pm = getSystemService(PowerManager::class.java)
        steps += Step(getString(R.string.perm_battery), getString(R.string.perm_battery_desc),
            pm.isIgnoringBatteryOptimizations(packageName)) { requestBatteryExemption() }
        if (OemHelper.isXiaomi) {
            val ok = OemHelper.miuiLockScreenAllowed(this)
            steps += Step(getString(R.string.perm_miui), getString(R.string.perm_miui_desc), ok == true,
                counts = ok != null, isLink = ok == null) { OemHelper.openMiuiPermissions(this) }
        }
        OemHelper.brand?.let { b ->
            steps += Step(getString(R.string.perm_oem, b), getString(R.string.perm_oem_desc), false, counts = false, isLink = true) {
                OemHelper.openAutostart(this)
            }
        }

        val countable = steps.filter { it.counts }
        val done = countable.count { it.done }
        setupIncomplete = done < countable.size
        setupProgress.text = if (!setupIncomplete) getString(R.string.setup_all_done)
        else getString(R.string.setup_progress, done, countable.size)
        setupBar.max = countable.size
        setupBar.setProgressCompat(done, true)
        tabs.getTabAt(0)?.let { tab ->
            if (setupIncomplete) tab.orCreateBadge.apply {
                backgroundColor = getColor(R.color.warn)
                number = countable.size - done
            } else tab.removeBadge()
        }

        setupList.removeAllViews()
        val subtitles = ui.grid(setupList, steps.map { step ->
            GridItem(if (step.done && !step.isLink) R.drawable.ic_check else R.drawable.ic_add,
                step.title, getString(if (step.done && !step.isLink) R.string.setup_ready else R.string.setup_open)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(step.title)
                    .setMessage(step.desc)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.setup_open) { _, _ -> step.action() }
                    .show()
            }
        }, columns = 2)
        subtitles.forEachIndexed { i, sub ->
            sub.setTextColor(getColor(if (steps[i].done && !steps[i].isLink) R.color.ok else R.color.accent))
        }
    }

    // ---- Shizuku / root one-tap setup ---------------------------------------------------------

    private lateinit var shellBox: LinearLayout
    private val shizukuListener = Shizuku.OnBinderReceivedListener { runOnUiThread { buildShellCard() } }

    private fun buildShellCard() {
        if (!::shellBox.isInitialized) return
        shellBox.removeAllViews()
        val c = ui.card(shellBox)
        when {
            Privileged.backend() != Privileged.Backend.NONE -> ui.link(c, R.drawable.ic_check,
                getString(R.string.shell_connected, Privileged.backend().name.lowercase().replaceFirstChar { it.uppercase() }),
                getString(R.string.shell_connected_desc)) { runShellSetup() }
            else -> {
                if (Privileged.shizukuRunning()) {
                    ui.link(c, R.drawable.ic_play, getString(R.string.shell_shizuku), getString(R.string.shell_desc)) {
                        Privileged.requestShizuku { ok ->
                            if (ok) runShellSetup()
                            else Toast.makeText(this, R.string.shell_denied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (Privileged.rootLikely()) {
                    if (Privileged.shizukuRunning()) ui.divider(c)
                    ui.link(c, R.drawable.ic_play, getString(R.string.shell_root), getString(R.string.shell_desc)) {
                        Privileged.requestRoot { ok ->
                            prefs.sp.edit().putBoolean(Privileged.PREF_ROOT_OK, ok).apply()
                            if (ok) runShellSetup()
                            else Toast.makeText(this, R.string.shell_denied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (!Privileged.shizukuRunning() && !Privileged.rootLikely()) {
                    ui.link(c, R.drawable.ic_apps, getString(R.string.shell_get), getString(R.string.shell_get_desc)) {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                }
            }
        }
    }

    private fun runShellSetup() {
        Toast.makeText(this, R.string.shell_running, Toast.LENGTH_SHORT).show()
        Privileged.setup(this) { summary ->
            prefs.sp.edit().remove(LedController.PREF_WINDOW_BLOCKED).apply()
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
            buildSetup()
            refreshLive()
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

    // ---- test notification ---------------------------------------------------------------------

    private fun sendTest() {
        if (!NotifyListenerService.isEnabled(this)) {
            Toast.makeText(this, R.string.need_listener_first, Toast.LENGTH_SHORT).show()
            pager.currentItem = 0
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        if (NotifyListenerService.instance == null) {
            // Access is granted but the system dropped the connection (e.g. after a crash or an
            // OEM task killer): ask it to bind again, otherwise the test would never be seen.
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, NotifyListenerService::class.java),
            )
            com.glowled.app.core.DebugLog.add("Test: notification access was disconnected - reconnecting")
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
        private const val METER_PREVIEW_MS = 3500L
        private const val PREVIEW_CAM_DP = 30
        private const val PREVIEW_HOLE_DP = 14
        private val PREVIEW_ICON_KEYS = setOf("aod_icons", "show_app_info", "aod_enabled")
        private const val PREVIEW_DROP_DELAY_MS = 220L
        private const val DROP_PREVIEW_DP = 26f
        private const val UI_STATE = "ui_state"
        private const val KEY_REACH_OPEN = "reach_open"
        // Discrete changes replay the entrance; sliders just update the ring live while dragging.
        private val REPLAY_KEYS = setOf("style", "default_color", "multi_mode", "glow", "enabled", "rotate_ring")
        private const val RELEASES_URL = "https://github.com/ankitchanki/LED/releases"
        /** Always the newest signed APK (updated with every release). */
        private const val LATEST_APK_URL = "https://github.com/ankitchanki/LED/releases/latest/download/GlowLED-latest.apk"
    }
}
