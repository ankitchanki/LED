package com.glowled.app.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.glowled.app.BuildConfig
import com.glowled.app.R
import com.glowled.app.core.LedController
import com.glowled.app.core.Updater
import com.glowled.app.service.UpdateCheckJob
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/**
 * The in-app updater: says whether Glow LED is up to date, shows what's new, and downloads and
 * installs the new version without leaving the app.
 */
class UpdateActivity : AppCompatActivity() {

    private enum class State { CHECKING, LATEST, AVAILABLE, FAILED, DOWNLOADING, INSTALLING }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = LedController.prefs
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var col: LinearLayout
    private lateinit var statusIcon: View
    private lateinit var statusSpinner: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var bar: LinearProgressIndicator
    private lateinit var ring: com.glowled.app.view.ArrowRingView
    private lateinit var appIcon: ImageView
    private var bob: android.animation.ValueAnimator? = null
    private lateinit var button: TextView
    private lateinit var notesHead: TextView
    private lateinit var notesCard: View
    private lateinit var notes: TextView

    private var state = State.CHECKING
    private var release: Updater.Release? = null
    @Volatile private var cancelled = false
    /** Waiting for the user to allow "Install unknown apps", then install straight away. */
    private var installAfterPermission = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Updater.K_AUTO) UpdateCheckJob.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        goEdgeToEdge()
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_sub)
        preferMaxRefreshRate()
        setupHeader(getString(R.string.upd_title))
        col = findViewById(R.id.col)
        findViewById<View>(R.id.scroll).padForNavBar()
        build()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        UpdateCheckJob.cancelNotification(this)
        check()
    }

    override fun onResume() {
        super.onResume()
        if (installAfterPermission && Updater.canInstall(this)) {
            installAfterPermission = false
            startDownload()
        }
    }

    override fun onDestroy() {
        bob?.cancel()
        cancelled = true
        Updater.onInstallResult = null
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- layout -----------------------------------------------------------------------------------

    private fun group(): LinearLayout {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 16 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.glass_stroke)
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        // Same inset as the groups SettingsUi builds below.
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
        return inner
    }

    private fun header(text: String, top: Int = 24) = TextView(this).apply {
        this.text = text
        isAllCaps = true
        textSize = 13f
        letterSpacing = 0.03f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(top), dp(16), dp(6))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        col.addView(this)
    }

    private fun build() {
        // App and installed version.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(18))
        }
        // The app icon, with a ring around it that fills as the update downloads.
        val ringBox = android.widget.FrameLayout(this)
        ring = com.glowled.app.view.ArrowRingView(this).apply {
            color = getColor(R.color.accent)
            icon = com.glowled.app.core.MeterIcon.NONE
            fraction = 0f
            alpha = 0f
        }
        ringBox.addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
        appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ringBox.addView(appIcon, android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        top.addView(ringBox, LinearLayout.LayoutParams(dp(108), dp(108)))
        top.addView(TextView(this).apply {
            text = getString(R.string.upd_installed, BuildConfig.VERSION_NAME.substringBefore('-'))
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
        })
        col.addView(top)

        // Status, progress and the install button.
        val g = group()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        val iconBox = android.widget.FrameLayout(this)
        statusIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RowIcons.GREEN) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.accent))
        }
        iconBox.addView(statusIcon, android.widget.FrameLayout.LayoutParams(dp(32), dp(32)))
        iconBox.addView(statusSpinner, android.widget.FrameLayout.LayoutParams(dp(32), dp(32)))
        row.addView(iconBox, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_primary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusSub = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(statusTitle)
        texts.addView(statusSub)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        g.addView(row)
        bar = LinearProgressIndicator(this).apply {
            setIndicatorColor(getColor(R.color.accent))
            trackColor = getColor(R.color.switch_off)
            trackCornerRadius = dp(3)
            trackThickness = dp(5)
            max = 100
        }
        g.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        button = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF000000.toInt())
            background = GradientDrawable().apply { setColor(getColor(R.color.accent)); cornerRadius = 14 * d }
            isClickable = true
            isFocusable = true
            setOnClickListener { Haptics.click(it); onButton() }
        }
        g.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(dp(14), dp(4), dp(14), dp(14)) })

        // What's new.
        notesHead = header("")
        val ng = group()
        notesCard = ng.parent as View
        notes = TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setTextIsSelectable(true)
        }
        ng.addView(notes)

        // Automatic checks.
        header(getString(R.string.sec_auto_apps))
        val ui = SettingsUi(this, prefs.sp)
        val auto = ui.card(col)
        ui.toggles(auto, listOf(SettingsUi.Toggle(getString(R.string.upd_auto), Updater.K_AUTO, true)))
        ui.link(auto, R.drawable.ic_s_globe, getString(R.string.upd_all_releases), null, RowIcons.INDIGO) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE)))
        }
        col.addView(TextView(this).apply {
            setText(R.string.upd_auto_note)
            textSize = 13f
            setLineSpacing(0f, 1.1f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(8), dp(16), 0)
        })
        render()
    }

    // ---- states -----------------------------------------------------------------------------------

    private fun render(progress: Int = 0) {
        val r = release
        statusSpinner.visibility = if (state == State.CHECKING) View.VISIBLE else View.GONE
        statusIcon.visibility = if (state == State.CHECKING) View.GONE else View.VISIBLE
        val (iconColor, icon) = when (state) {
            State.LATEST -> RowIcons.GREEN to R.drawable.ic_check
            State.FAILED -> RowIcons.ORANGE to R.drawable.ic_s_restore
            else -> getColor(R.color.accent) to R.drawable.ic_s_download
        }
        (statusIcon as ImageView).setImageResource(icon)
        (statusIcon.background as GradientDrawable).setColor(iconColor)
        statusTitle.text = when (state) {
            State.CHECKING -> getString(R.string.upd_checking)
            State.LATEST -> getString(R.string.upd_latest)
            State.FAILED -> getString(R.string.upd_failed_title)
            State.AVAILABLE -> getString(R.string.upd_available, r?.version)
            State.DOWNLOADING -> getString(R.string.upd_downloading, progress)
            State.INSTALLING -> getString(R.string.upd_installing)
        }
        statusSub.text = when (state) {
            State.FAILED -> getString(R.string.upd_failed)
            State.AVAILABLE, State.DOWNLOADING -> r?.size?.takeIf { it > 0 }?.let {
                getString(R.string.upd_size, Formatter.formatShortFileSize(this, it))
            } ?: ""
            State.INSTALLING -> getString(R.string.upd_installing_sub)
            State.LATEST -> getString(R.string.upd_latest_sub, BuildConfig.VERSION_NAME.substringBefore('-'))
            else -> ""
        }
        statusSub.visibility = if (statusSub.text.isEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (state == State.DOWNLOADING || state == State.INSTALLING) View.VISIBLE else View.GONE
        bar.isIndeterminate = state == State.INSTALLING
        if (state == State.DOWNLOADING) bar.setProgressCompat(progress, true)
        renderRing(progress)
        button.visibility = when (state) {
            State.AVAILABLE, State.FAILED, State.LATEST, State.DOWNLOADING -> View.VISIBLE
            else -> View.GONE
        }
        button.text = getString(when (state) {
            State.AVAILABLE -> R.string.upd_install
            State.FAILED -> R.string.upd_retry
            State.DOWNLOADING -> R.string.cancel
            else -> R.string.upd_check_now
        })
        val filled = state == State.AVAILABLE
        (button.background as GradientDrawable).setColor(getColor(if (filled) R.color.accent else R.color.switch_off))
        button.setTextColor(if (filled) 0xFF000000.toInt() else getColor(R.color.text_primary))
        val text = r?.notes?.let { Updater.plainNotes(it) }.orEmpty()
        notesHead.text = r?.let { getString(R.string.upd_whats_new, it.version) } ?: ""
        notes.text = text
        val showNotes = text.isNotEmpty() && state != State.CHECKING && state != State.FAILED
        notesHead.visibility = if (showNotes) View.VISIBLE else View.GONE
        notesCard.visibility = if (showNotes) View.VISIBLE else View.GONE
    }

    /** Ring around the icon: fills with the download, full while installing; the icon gently bobs. */
    private fun renderRing(progress: Int) {
        val busy = state == State.DOWNLOADING || state == State.INSTALLING
        ring.animate().alpha(if (busy) 1f else 0f).setDuration(200).start()
        ring.fraction = if (state == State.INSTALLING) 1f else progress / 100f
        if (busy && bob == null) {
            bob = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    appIcon.scaleX = 0.92f + 0.08f * f
                    appIcon.scaleY = 0.92f + 0.08f * f
                }
                start()
            }
        } else if (!busy) {
            bob?.cancel()
            bob = null
            appIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun onButton() = when (state) {
        State.AVAILABLE -> startDownload()
        State.DOWNLOADING -> { cancelled = true }
        else -> check()
    }

    private fun check() {
        state = State.CHECKING
        render()
        io.execute {
            val r = Updater.check(this)
            main.post {
                if (isDestroyed) return@post
                release = r
                state = when {
                    r == null -> State.FAILED
                    Updater.isNewer(r.version, BuildConfig.VERSION_NAME) -> State.AVAILABLE
                    else -> State.LATEST
                }
                render()
            }
        }
    }

    private fun startDownload() {
        val r = release ?: return
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.upd_allow_title)
                .setMessage(R.string.upd_allow_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.setup_open) { _, _ ->
                    installAfterPermission = true
                    open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        cancelled = false
        state = State.DOWNLOADING
        render(0)
        io.execute {
            val result = runCatching {
                val file = Updater.download(this, r, { cancelled }) { f -> main.post { if (state == State.DOWNLOADING) render((f * 100).toInt()) } }
                if (!Updater.verify(this, file)) {
                    file.delete()
                    error(getString(R.string.upd_verify_failed))
                }
                file
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { install(it) }.onFailure { e ->
                    state = State.AVAILABLE
                    render()
                    if (!cancelled) toastLong(e.message?.takeIf { it == getString(R.string.upd_verify_failed) }
                        ?: getString(R.string.upd_download_failed))
                }
            }
        }
    }

    private fun install(file: java.io.File) {
        state = State.INSTALLING
        render()
        Updater.onInstallResult = { ok, msg ->
            main.post {
                if (isDestroyed) return@post
                if (!ok) {
                    state = State.AVAILABLE
                    render()
                    toastLong(getString(R.string.upd_install_failed, msg ?: ""))
                }
                // On success Android replaces the app and restarts it, so there is nothing to show.
            }
        }
        io.execute {
            val err = runCatching { Updater.install(this, file) }.exceptionOrNull()
            if (err != null) main.post {
                state = State.AVAILABLE
                render()
                toastLong(getString(R.string.upd_install_failed, err.message ?: ""))
            }
        }
    }

    private fun toastLong(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { }
    }
}
