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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.glowled.app.BuildConfig
import com.glowled.app.R
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
    private var logText: TextView? = null
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
        // Visual feedback right on the camera when the look changes.
        if (key in REPLAY_KEYS) playCameraIntro()
        if (key == "style" || key == Prefs.K_ENABLED) ui.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_main)
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
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            prefs.enabled = on
            refreshLive()
        }
        findViewById<View>(R.id.btnPreview).setOnClickListener { LedController.startAod(pulse = false, preview = true) }
        findViewById<View>(R.id.btnTest).setOnClickListener { sendTest() }

        buildPages()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        // Shizuku's binder arrives asynchronously; refresh the setup card when it does.
        Shizuku.addBinderReceivedListenerSticky(shizukuListener)
        buildSetup()
        // First run / something missing: open on Setup so the user sees what to do.
        if (savedInstanceState == null && setupIncomplete) pager.setCurrentItem(0, false)
        else if (savedInstanceState == null) pager.setCurrentItem(1, false)
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
        logText?.text = DebugLog.text()
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
            getString(R.string.tab_log) to pageLog(),
        )
        pager.adapter = PagesAdapter(pages.map { it.second })
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
        shellBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(shellBox)
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
        ui.chips(c, "Several notifications", "multi_mode",
            listOf("Cycle colours", "Split ring", "Newest only"), listOf("cycle", "segments", "latest"), "cycle")
        ui.sliders(c, Range("Speed", "speed", 1, 10, 5, ""), Range("Intensity", "led_alpha", 10, 100, 100, "%"))
        ui.sliders(c, Range("Thickness", "thickness", 1, 16, 3, "dp"), Range("Gap", "gap", 0, 20, 2, "dp"))
        ui.sliders(c, Range("Edge corners", "edge_radius", 0, 120, 40, "dp"), dependsOn = { prefs.style.isEdge })
        ui.toggles(col, listOf(
            Toggle("Soft glow", "glow", true),
            Toggle("App's notification colour", "use_notif_color", true),
            Toggle("App icon colour", "use_icon_color", true),
        ))
        return page
    }

    private fun pageScreenOff(): View {
        val (page, col) = ui.page()
        val aodOn = { prefs.aodEnabled }
        ui.toggles(col, listOf(
            Toggle("Light while screen is off", "aod_enabled", true),
            Toggle("When I turn screen off", "aod_on_screen_off", true, aodOn),
            Toggle("When a notification arrives", "aod_on_new", true, aodOn),
            Toggle("Pocket detection", "proximity", true, aodOn),
            Toggle("Double-tap to wake", "double_tap_wake", true, aodOn),
            Toggle("Burn-in protection", "burn_in", true, aodOn),
            Toggle("Show app under camera", "show_app_info", true, aodOn),
            Toggle("Keep app icons visible", "app_info_always", false) { prefs.aodEnabled && prefs.showAppInfo },
            Toggle("Tap icon to read message", "show_content", true) { prefs.aodEnabled && prefs.showAppInfo },
            Toggle("Droplet animation", "droplet", true),
        ))
        val c = ui.card(col)
        ui.chips(c, "Screen-off method", "aod_method",
            listOf("Auto", "Lock-screen window", "Overlay"), listOf("auto", "activity", "overlay"), "auto", aodOn)
        ui.chips(c, "Keep the light on for", "aod_timeout",
            listOf("30 s", "1 min", "2 min", "5 min", "15 min", "1 hour", "Until cleared"),
            listOf("30", "60", "120", "300", "900", "3600", "0"), "120", aodOn)
        ui.chips(c, "Remind me afterwards", "pulse_interval",
            listOf("Off", "Every 2 min", "5 min", "10 min", "30 min"), listOf("0", "2", "5", "10", "30"), "0", aodOn)
        ui.sliders(c, Range("Brightness", "aod_brightness", 1, 100, 3, "%"),
            Range("Reminder", "pulse_duration", 3, 60, 10, "s"), dependsOn = aodOn)
        return page
    }

    private fun pageScreenOn(): View {
        val (page, col) = ui.page()
        ui.toggles(col, listOf(
            Toggle("Light while using the phone", "overlay_enabled", true),
            Toggle("Only on the lock screen", "overlay_lock_only", false) { prefs.overlayEnabled },
        ))
        col.addView(hint(getString(R.string.screen_on_hint)))
        return page
    }

    private fun pageAlerts(): View {
        val (page, col) = ui.page()
        val c = ui.card(col)
        ui.link(c, R.drawable.ic_apps, getString(R.string.action_apps), "Turn apps on/off, pick colours") {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        ui.divider(c)
        ui.chips(c, "Which apps", "app_mode", listOf("All apps", "Only apps I choose"), listOf("all", "selected"), "all")
        ui.toggles(col, listOf(
            Toggle("Ignore silent notifications", "ignore_low", true),
            Toggle("Respect Do Not Disturb", "respect_dnd", true),
            Toggle("Clear after I unlock", "clear_on_unlock", false),
        ))
        return page
    }

    private fun pageBattery(): View {
        val (page, col) = ui.page()
        val c = ui.card(col)
        ui.chips(c, "Animation frame rate", "fps_cap",
            listOf("15 fps", "24 fps", "30 fps", "60 fps", "Max"), listOf("15", "24", "30", "60", "0"), "30")
        ui.sliders(c, Range("Pause below battery", "min_battery", 0, 50, 10, "%"))
        ui.toggles(col, listOf(
            Toggle("Lowest refresh rate when off", "low_refresh", true),
            Toggle("Quiet hours", "quiet_enabled", false),
        ))
        val q = ui.card(col)
        quietSub = ui.link(q, R.drawable.ic_moon, "Quiet hours time", quietText()) { pickQuietHours() }
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

    private fun pageLog(): View {
        val (page, col) = ui.page()

        // Install / update: whether this is the signed release (updates install over it).
        val official = AppSignature.isOfficial(this)
        val u = ui.card(col)
        ui.link(u, if (official) R.drawable.ic_check else R.drawable.ic_bell,
            getString(if (official) R.string.sig_official else R.string.sig_other),
            getString(R.string.sig_desc, BuildConfig.VERSION_NAME, AppSignature.short(AppSignature.sha256(this)))) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
        }
        ui.divider(u)
        ui.link(u, R.drawable.ic_play, getString(R.string.check_updates), getString(R.string.check_updates_desc)) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
        }
        ui.divider(u)
        ui.link(u, R.drawable.ic_apps, getString(R.string.backup), getString(R.string.backup_desc)) {
            backupLauncher.launch("glowled-settings.json")
        }
        ui.divider(u)
        ui.link(u, R.drawable.ic_add, getString(R.string.restore), getString(R.string.restore_desc)) {
            restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        val c = ui.card(col)
        ui.link(c, R.drawable.ic_bell, "Copy event log", "Send this to the developer if something fails") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Glow LED log", deviceInfo() + "\n\n" + DebugLog.text()))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(18), dp(4), dp(18), dp(12))
            setTextIsSelectable(true)
            text = DebugLog.text()
        }
        c.addView(logText)
        col.addView(hint("Glow LED ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
        return page
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
            }, prefs.quietEnd / 60, prefs.quietEnd % 60, is24).apply { setTitle("Quiet hours end") }.show()
        }, prefs.quietStart / 60, prefs.quietStart % 60, is24).apply { setTitle("Quiet hours start") }.show()
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
            return h
        }

        override fun onBindViewHolder(h: StyleHolder, position: Int) {
            val style = styles[position]
            val selected = style == prefs.style
            h.name.text = names[position]
            h.card.strokeWidth = if (selected) dp(2) else 0
            h.card.setCardBackgroundColor(getColor(if (selected) R.color.surface_high else R.color.surface))
            h.lens.visibility = if (style.isEdge) View.INVISIBLE else View.VISIBLE
            h.led.post {
                val d = 22 * density
                h.led.configure(prefs, Geometry(CutoutShape.CIRCLE, h.led.width / 2f, h.led.height / 2f, d, d),
                    previewColors(), style, 3, 3)
            }
            h.card.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
                    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
        steps += Step(getString(R.string.perm_a11y), getString(R.string.perm_a11y_desc), a11y, counts = false) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (OemHelper.isXiaomi) {
            val ok = OemHelper.miuiLockScreenAllowed(this)
            steps += Step(getString(R.string.perm_miui), getString(R.string.perm_miui_desc), ok == true,
                counts = ok != null, isLink = ok == null) { OemHelper.openMiuiPermissions(this) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            steps += Step(getString(R.string.perm_post), getString(R.string.perm_post_desc), granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        steps += Step(getString(R.string.perm_battery), getString(R.string.perm_battery_desc),
            pm.isIgnoringBatteryOptimizations(packageName)) { requestBatteryExemption() }
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
        val inflater = LayoutInflater.from(this)
        steps.sortedBy { if (it.done && !it.isLink) 1 else 0 }.forEach { s ->
            val row = inflater.inflate(R.layout.item_setup, setupList, false) as MaterialCardView
            row.findViewById<TextView>(R.id.title).text = s.title
            val desc = row.findViewById<TextView>(R.id.desc)
            desc.text = s.desc
            val icon = row.findViewById<ImageView>(R.id.stateIcon)
            val bg = row.findViewById<View>(R.id.stateBg)
            icon.imageTintList = ColorStateList.valueOf(getColor(R.color.black))
            if (s.done && !s.isLink) {
                icon.setImageResource(R.drawable.ic_check)
                bg.backgroundTintList = ColorStateList.valueOf(getColor(R.color.ok))
                desc.visibility = View.GONE
                row.findViewById<View>(R.id.chevron).visibility = View.INVISIBLE
                row.alpha = 0.7f
                row.isClickable = false
            } else {
                icon.setImageResource(if (s.isLink) R.drawable.ic_apps else R.drawable.ic_add)
                val tint = if (!s.counts) R.color.accent else if (s == steps[0]) R.color.bad else R.color.warn
                bg.backgroundTintList = ColorStateList.valueOf(getColor(tint))
                row.setOnClickListener { s.action() }
            }
            setupList.addView(row)
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
        // Discrete changes replay the entrance; sliders just update the ring live while dragging.
        private val REPLAY_KEYS = setOf("style", "default_color", "multi_mode", "glow", "enabled")
        private const val DOWNLOAD_URL = "https://github.com/ankitchanki/LED/tree/main/releases"
    }
}
