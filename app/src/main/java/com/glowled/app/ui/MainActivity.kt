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
import com.glowled.app.core.MeterIcon
import com.glowled.app.core.UnknownArrow
import com.glowled.app.core.Updater
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
import com.glowled.app.util.OemHelper
import com.glowled.app.util.SettingsBackup
import com.glowled.app.view.LedView
import com.glowled.app.view.NestedScrollableHost
import com.google.android.material.card.MaterialCardView
import com.glowled.app.view.GlowSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Single-screen home: header with master switch, swipeable setting pages (pull down for the live
 * preview) and a floating Test pill. Every setting lives here; nothing hides behind a menu.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var master: GlowSwitch
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
    private var arrowsSub: TextView? = null
    private var unknownSub: TextView? = null
    private var updateSub: TextView? = null
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
        ui.onScroll = { tuckTestPill() }

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
        buildSetup()
        val uiState = getSharedPreferences(UI_STATE, MODE_PRIVATE)
        if (savedInstanceState == null && !uiState.getBoolean("tutorial_seen", false)) {
            // Shown once, however it is closed (Done, Close or tapping outside); More → Tutorial reopens it.
            uiState.edit().putBoolean("tutorial_seen", true).apply()
            window.decorView.post { showTutorial() }
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

    private fun setMeters(fraction: Float?, color: Int, icon: MeterIcon = MeterIcon.NONE) {
        if (::cameraLed.isInitialized) cameraLed.setMeter(fraction, color, icon)
        previewLed?.setMeter(fraction, color, icon)
    }

    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val pill = findViewById<View>(R.id.btnTest)
        val headerTop = header.paddingTop
        val pillHeight = resources.getDimensionPixelSize(R.dimen.pill_height)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.updatePadding(top = bars.top + headerTop, left = bars.left + dp(20), right = bars.right + dp(20))
            // The pill floats just above the navigation bar; the pages scroll underneath it and end
            // with enough empty space that their last row can always be scrolled clear of it.
            (pill.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bars.bottom + dp(PILL_GAP_DP)
            pill.requestLayout()
            ui.setBottomClearance(bars.bottom + dp(PILL_GAP_DP) + pillHeight + dp(8))
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        LedController.appInForeground = true
        LedController.updateOverlay() // our own preview replaces the screen-on ring meanwhile
        master.isChecked = prefs.enabled
        arrowsSub?.text = arrowsText()
        unknownSub?.text = unknownText()
        updateSub?.text = updateText()
        buildSetup()
        refreshLive()
        LedController.addListener(storeListener)
        NotifyListenerService.forceReconnect(this)
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
            setMeters(level / 100f, prefs.chargeColor(level), MeterIcon.BOLT)
        } else {
            val color = when (key) {
                "upload_color" -> prefs.uploadColor
                "download_color" -> prefs.downloadColor
                else -> prefs.progressColor.takeIf { it != 0 } ?: prefs.defaultColor
            }
            val icon = when {
                !prefs.progressArrow -> MeterIcon.NONE
                key == "upload_color" -> MeterIcon.UP
                else -> MeterIcon.DOWN
            }
            setMeters(0.62f, color, icon)
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
        section(col, R.string.sec_showcase, first = true)
        col.addView(com.glowled.app.view.ShowcaseView(this, previewApps),
            LinearLayout.LayoutParams(-1, dp(SHOWCASE_DP)).apply { setMargins(dp(4), 0, dp(4), dp(10)) })
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
        // Most important first (notification access, display over apps); accessibility and
        // autostart, both optional, come last.
        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(setupList)
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
        section(col, R.string.sec_animation, first = true)
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

        section(col, R.string.sec_colour)
        swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), 0, dp(2), 0) }
        buildSwatches()
        col.addView(NestedScrollableHost(this).apply {
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(swatches)
            })
        })
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_notif_colour), "use_notif_color", true),
            Toggle(getString(R.string.t_icon_colour), "use_icon_color", true),
        ))
        val multi = ui.card(col)
        ui.chips(multi, getString(R.string.chips_multi), "multi_mode",
            labels(R.array.multi_labels), listOf("segments", "cycle", "latest"), "segments")

        section(col, R.string.sec_shape)
        val c = ui.card(col)
        ui.link(c, R.drawable.ic_s_camera, getString(R.string.action_cutout), getString(R.string.tile_cutout_sub), RowIcons.DARK_GREY) {
            startActivity(Intent(this, CutoutActivity::class.java))
        }
        ui.divider(c)
        ui.sliders(c, Range(getString(R.string.s_speed), "speed", 1, 10, 5, ""),
            Range(getString(R.string.s_intensity), "led_alpha", 10, 100, 100, "%"))
        ui.sliders(c, Range(getString(R.string.s_thickness), "thickness", 1, 16, 3, "dp"),
            Range(getString(R.string.s_gap), "gap", 0, 20, 2, "dp"))
        ui.sliders(c, Range(getString(R.string.s_edge), "edge_radius", 0, 120, 40, "dp"), dependsOn = { prefs.style.isEdge })
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_glow), "glow", true),
            Toggle(getString(R.string.t_rotate), "rotate_ring", true),
        ))
        return page
    }

    private fun pageScreenOff(): View {
        val (page, col) = ui.page()
        val aodOn = { prefs.aodEnabled }
        val iconsOn = { prefs.aodEnabled && prefs.showAppInfo }
        section(col, R.string.sec_when, first = true)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_aod), "aod_enabled", true),
            Toggle(getString(R.string.t_on_screen_off), "aod_on_screen_off", true, aodOn),
            Toggle(getString(R.string.t_on_new), "aod_on_new", true, aodOn),
            Toggle(getString(R.string.t_charge_aod), "charge_aod", false) { prefs.aodEnabled && prefs.chargeLed },
        ))
        section(col, R.string.sec_black)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_app_icons), "show_app_info", true, aodOn),
            Toggle(getString(R.string.t_aod_icons), "aod_icons", true, iconsOn),
            Toggle(getString(R.string.t_keep_icons), "app_info_always", false, iconsOn),
            Toggle(getString(R.string.t_droplet), "droplet", true),
        ))
        section(col, R.string.sec_messages)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_tap_read), "show_content", true, iconsOn),
            Toggle(getString(R.string.t_peek_actions), "peek_actions", true) { iconsOn() && prefs.showContent },
        ))
        section(col, R.string.sec_gestures)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_double_tap), "double_tap_wake", true, aodOn),
            Toggle(getString(R.string.t_pocket), "proximity", true, aodOn),
            Toggle(getString(R.string.t_burn_in), "burn_in", true, aodOn),
        ))
        section(col, R.string.sec_timing)
        val c = ui.card(col)
        ui.chips(c, getString(R.string.chips_timeout), "aod_timeout",
            labels(R.array.timeout_labels), listOf("30", "60", "120", "300", "900", "3600", "0"), "120", aodOn)
        ui.chips(c, getString(R.string.chips_remind), "pulse_interval",
            labels(R.array.remind_labels), listOf("0", "2", "5", "10", "30"), "0", aodOn)
        ui.sliders(c, Range(getString(R.string.s_brightness), "aod_brightness", 1, 100, 3, "%"),
            Range(getString(R.string.s_reminder), "pulse_duration", 3, 60, 10, "s"), dependsOn = aodOn)
        ui.chips(c, getString(R.string.chips_off_delay), "off_delay",
            labels(R.array.off_delay_labels), listOf("800", "1500", "3000"), "800", aodOn)
        ui.chips(c, getString(R.string.chips_method), "aod_method",
            labels(R.array.method_labels), listOf("auto", "activity", "overlay"), "auto", aodOn)
        return page
    }

    private fun pageScreenOn(): View {
        val (page, col) = ui.page()
        section(col, R.string.sec_ring_on, first = true)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_ring_on), "overlay_enabled", true),
            Toggle(getString(R.string.t_lock_only), "overlay_lock_only", false) { prefs.overlayEnabled },
        ))
        section(col, R.string.sec_charging)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_charge), "charge_led", true),
            Toggle(getString(R.string.t_battery_indicator), "battery_indicator", false),
        ))
        col.addView(label(getString(R.string.charge_colours)))
        ui.colorTiles(col, listOf(
            ColorItem(getString(R.string.c_low), "charge_color_low", 0xFFFF1744.toInt()),
            ColorItem(getString(R.string.c_mid), "charge_color_mid", 0xFFFFC400.toInt()),
            ColorItem(getString(R.string.c_high), "charge_color_high", 0xFF00E676.toInt()),
        ), dependsOn = { prefs.chargeLed })
        section(col, R.string.sec_transfers)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_progress), "progress_led", true),
            Toggle(getString(R.string.t_progress_initials), "progress_initials", true) { prefs.progressLed },
            Toggle(getString(R.string.t_progress_app_color), "progress_app_color", true) { prefs.progressLed },
        ))
        col.addView(label(getString(R.string.sec_arrows)))
        val arrows = ui.card(col)
        arrowsSub = ui.link(arrows, R.drawable.ic_s_apps, getString(R.string.arrows_title), arrowsText(), RowIcons.INDIGO) {
            ArrowsActivity.open(this, ArrowsActivity.MODE_LIST)
        }
        ui.divider(arrows)
        unknownSub = ui.link(arrows, R.drawable.ic_s_updown, getString(R.string.unknown_title), unknownText(), RowIcons.GREY) {
            ArrowsActivity.open(this, ArrowsActivity.MODE_UNKNOWN)
        }
        ui.dependOn(arrows.parent as View) { prefs.progressLed && prefs.progressArrow }
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
        section(col, R.string.sec_apps, first = true)
        val c = ui.card(col)
        ui.link(c, R.drawable.ic_s_apps, getString(R.string.action_apps), getString(R.string.apps_sub), RowIcons.BLUE) {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        ui.divider(c)
        ui.chips(c, getString(R.string.chips_which_apps), "app_mode", labels(R.array.appmode_labels), listOf("all", "selected"), "all")
        section(col, R.string.sec_filters)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_ignore_silent), "ignore_low", true),
            Toggle(getString(R.string.t_dnd), "respect_dnd", true),
            Toggle(getString(R.string.t_clear_unlock), "clear_on_unlock", false),
        ))
        return page
    }

    private fun pageBattery(): View {
        val (page, col) = ui.page()
        section(col, R.string.sec_power, first = true)
        ui.toggles(col, listOf(
            Toggle(getString(R.string.t_eco), "eco_anim", true),
            Toggle(getString(R.string.t_age_fade), "age_fade", true),
            Toggle(getString(R.string.t_motion), "motion_glance", true),
            Toggle(getString(R.string.t_low_refresh), "low_refresh", true),
        ))
        val c = ui.card(col)
        ui.chips(c, getString(R.string.chips_fps), "fps_cap",
            labels(R.array.fps_labels), listOf("15", "24", "30", "60", "0"), "30")
        ui.sliders(c, Range(getString(R.string.s_min_battery), "min_battery", 0, 50, 10, "%"))
        section(col, R.string.t_quiet)
        ui.toggles(col, listOf(Toggle(getString(R.string.t_quiet), "quiet_enabled", false)))
        quietSub = ui.link(col, R.drawable.ic_s_clock, getString(R.string.quiet_time), quietText(), RowIcons.INDIGO) { pickQuietHours() }
        col.addView(hint(getString(R.string.battery_hint)))
        return page
    }

    private fun arrowsText(): String {
        val n = prefs.appsWithArrow().size
        return if (n == 0) getString(R.string.arrows_sub_none) else getString(R.string.arrows_sub_some, n)
    }

    /** "Version 2.7.0 · up to date", or the newer version the last check found. */
    private fun updateText(): String {
        val cur = BuildConfig.VERSION_NAME.substringBefore('-')
        return Updater.pendingVersion()?.let { getString(R.string.upd_sub_available, it) }
            ?: if (prefs.sp.contains(Updater.K_CHECKED)) getString(R.string.upd_sub_latest, cur)
            else getString(R.string.upd_sub_unknown, cur)
    }

    private fun unknownText(): String = getString(when (prefs.unknownArrow) {
        UnknownArrow.NONE -> R.string.unk_none
        UnknownArrow.BOTH -> R.string.unk_both
        UnknownArrow.DOWN -> R.string.unk_down
        UnknownArrow.UP -> R.string.unk_up
    })

    /** Section heading inside a page, so related settings are grouped under a clear name. */
    private fun section(col: LinearLayout, title: Int, first: Boolean = false) {
        col.addView(TextView(this).apply {
            setText(title)
            isAllCaps = true
            textSize = 13f
            letterSpacing = 0.03f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(if (first) 10 else 24), dp(16), dp(6))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
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

    /** Settings: app theme and haptics, then everything else as a grid of tiles side by side. */
    private fun pageMore(): View {
        val (page, col) = ui.page()
        section(col, R.string.sec_appearance, first = true)
        val look = ui.card(col)
        ui.chips(look, getString(R.string.chips_app_theme), "app_theme",
            labels(R.array.theme_labels), listOf("dark", "light", "system"), "dark")
        ui.toggles(col, listOf(Toggle(getString(R.string.t_haptics), "haptics", true)))
        section(col, R.string.sec_tools)
        updateSub = ui.grid(col, listOf(
            GridItem(R.drawable.ic_s_download, getString(R.string.upd_title), updateText(), RowIcons.BLUE) {
                startActivity(Intent(this, UpdateActivity::class.java))
            },
            GridItem(R.drawable.ic_s_play, getString(R.string.tutorial_title), getString(R.string.tutorial_sub), RowIcons.ORANGE) { showTutorial() },
            GridItem(R.drawable.ic_s_globe, getString(R.string.g_language), currentLanguageName(), RowIcons.INDIGO) { pickLanguage() },
            GridItem(R.drawable.ic_s_save, getString(R.string.g_backup), getString(R.string.g_backup_sub), RowIcons.CYAN) {
                backupLauncher.launch("glowled-settings.json")
            },
            GridItem(R.drawable.ic_s_restore, getString(R.string.g_restore), getString(R.string.g_restore_sub), RowIcons.BROWN) {
                restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            GridItem(R.drawable.ic_s_log, getString(R.string.g_log), getString(R.string.g_log_sub), RowIcons.GREY) { showLog() },
        )).getOrNull(0)
        col.addView(hint(getString(R.string.reach_tip)))
        return page
    }

    private fun showTutorial() {
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
                }
                .setNegativeButton(R.string.tutorial_later, null)
            if (step > 0) dialog.setNeutralButton(R.string.tutorial_back) { _, _ -> step--; showStep() }
            dialog.show()
        }
        showStep()
    }

    private fun labels(array: Int) = resources.getStringArray(array).toList()

    /** Small label above a group inside a section. */
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(14), dp(16), dp(6))
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

    // Each name is written in its own language, so anyone can find theirs.
    private val languageTags = arrayOf("", "en", "pt-BR", "es", "fr", "de", "it", "ru", "tr", "ar", "hi", "id", "ja", "zh-CN")

    private fun languageNames() = arrayOf(
        getString(R.string.lang_system),
        "English",
        "Português (Brasil)",
        "Español",
        "Français",
        "Deutsch",
        "Italiano",
        "Русский",
        "Türkçe",
        "العربية",
        "हिन्दी",
        "Bahasa Indonesia",
        "日本語",
        "简体中文",
    )

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

    /** Grey note under a group. */
    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 13f
        setLineSpacing(0f, 1.1f)
        setPadding(dp(16), dp(8), dp(16), dp(4))
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
        /** One-line label for the tile; [title] heads the explanation dialog. */
        val short: String,
        val desc: String,
        val done: Boolean,
        val counts: Boolean = true,
        val isLink: Boolean = false,
        val icon: Int = 0,
        val color: Int = 0,
        val actionLabel: Int = R.string.setup_open,
        val action: () -> Unit,
    )

    private fun buildSetup() {
        if (!::setupList.isInitialized) return
        val a11y = LedAccessibilityService.instance != null || isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val steps = mutableListOf<Step>()
        steps += Step(getString(R.string.perm_listener), getString(R.string.perm_listener_short), getString(R.string.perm_listener_desc),
            NotifyListenerService.isEnabled(this), icon = R.drawable.ic_s_bell, color = RowIcons.RED) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        // Either one is enough to show the light over the lock screen; accessibility is optional.
        steps += Step(getString(R.string.perm_overlay), getString(R.string.perm_overlay_short), getString(R.string.perm_overlay_desc), overlay || a11y,
            icon = R.drawable.ic_s_layers, color = RowIcons.BLUE) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            steps += Step(getString(R.string.perm_post), getString(R.string.perm_post), getString(R.string.perm_post_desc), granted,
                icon = R.drawable.ic_s_badge, color = RowIcons.ORANGE) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        // Some banking apps refuse to open while an accessibility service from outside the Play
        // Store is on, so it stays optional and can be switched off from here.
        val a11yLive = LedAccessibilityService.instance
        steps += Step(getString(R.string.perm_a11y), getString(R.string.perm_a11y_short),
            getString(R.string.perm_a11y_desc) + "\n\n" + getString(R.string.a11y_banking), a11y, counts = false,
            icon = R.drawable.ic_s_access, color = RowIcons.BLUE,
            actionLabel = if (a11yLive != null) R.string.a11y_turn_off else R.string.setup_open) {
            if (a11yLive != null) {
                a11yLive.disableSelf()
                window.decorView.postDelayed({ buildSetup(); refreshLive() }, 400)
            } else {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        steps += Step(getString(R.string.perm_battery), getString(R.string.perm_battery_short), getString(R.string.perm_battery_desc),
            pm.isIgnoringBatteryOptimizations(packageName), icon = R.drawable.ic_s_battery, color = RowIcons.GREEN) { requestBatteryExemption() }
        if (OemHelper.isXiaomi) {
            val ok = OemHelper.miuiLockScreenAllowed(this)
            steps += Step(getString(R.string.perm_miui), getString(R.string.perm_miui_short), getString(R.string.perm_miui_desc), ok == true,
                counts = ok != null, isLink = ok == null, icon = R.drawable.ic_s_lock, color = RowIcons.DARK_GREY) { OemHelper.openMiuiPermissions(this) }
        }
        OemHelper.brand?.let { b ->
            steps += Step(getString(R.string.perm_oem, b), getString(R.string.perm_oem_short), getString(R.string.perm_oem_desc), false, counts = false, isLink = true,
                icon = R.drawable.ic_s_power, color = RowIcons.GREY) {
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
        // A row per step: tick = done, orange "Allow" = needed, chevron = optional.
        ui.statusTiles(setupList, steps.map { step ->
            SettingsUi.StatusItem(step.short, step.done && !step.isLink, optional = !step.counts || step.isLink, step.icon, step.color) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(step.title)
                    .setMessage(step.desc)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(step.actionLabel) { _, _ -> step.action() }
                    .show()
            }
        })
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

    private var pillTucked = false
    private val showTestPill = Runnable {
        pillTucked = false
        findViewById<View>(R.id.btnTest).animate().translationY(0f).alpha(1f)
            .setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** While the settings scroll, the Test pill slides out of the way; it comes back once they stop. */
    private fun tuckTestPill() {
        val pill = findViewById<View>(R.id.btnTest)
        pill.removeCallbacks(showTestPill)
        if (!pillTucked) {
            pillTucked = true
            val away = pill.height + (pill.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            pill.animate().translationY(away.toFloat()).alpha(0f).setDuration(160).start()
        }
        pill.postDelayed(showTestPill, PILL_RETURN_MS)
    }

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
        val connected = NotifyListenerService.instance != null
        if (!connected) {
            // Access is granted but the system dropped the connection (after an update, a crash
            // or an OEM task killer): bind it again, and show the test light directly meanwhile.
            NotifyListenerService.forceReconnect(this)
            com.glowled.app.core.DebugLog.add("Test: notification access was disconnected - reconnecting; showing the test light directly")
            LedController.showTestLight(getString(R.string.test_title), getString(R.string.test_text))
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
        private const val SHOWCASE_DP = 210
        /** How long after scrolling stops the Test pill comes back. */
        private const val PILL_RETURN_MS = 450L
        private const val METER_PREVIEW_MS = 3500L
        private const val PREVIEW_CAM_DP = 30
        private const val PREVIEW_HOLE_DP = 14
        private val PREVIEW_ICON_KEYS = setOf("aod_icons", "show_app_info", "aod_enabled")
        private const val PREVIEW_DROP_DELAY_MS = 220L
        private const val PILL_GAP_DP = 12
        private const val DROP_PREVIEW_DP = 26f
        private const val UI_STATE = "ui_state"
        private const val KEY_REACH_OPEN = "reach_open"
        // Discrete changes replay the entrance; sliders just update the ring live while dragging.
        private val REPLAY_KEYS = setOf("style", "default_color", "multi_mode", "glow", "enabled", "rotate_ring")
    }
}
