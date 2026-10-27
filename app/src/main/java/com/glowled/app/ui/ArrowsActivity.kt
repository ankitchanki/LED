package com.glowled.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.glowled.app.R
import com.glowled.app.core.ArrowChoice
import com.glowled.app.core.LedController
import com.glowled.app.core.MeterIcon
import com.glowled.app.core.ProgressStore
import com.glowled.app.core.TransferDirection
import com.glowled.app.core.TransferInfo
import com.glowled.app.core.UnknownArrow
import com.glowled.app.view.ArrowRingView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

/**
 * Transfer arrows, in three screens: the per-app list, one app's arrow, and what shows when the
 * direction is unknown. Grouped rows with a large title, like the system settings on iPhone.
 */
class ArrowsActivity : AppCompatActivity() {

    private data class App(val pkg: String, val label: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<String, Drawable>(80)
    private val prefs get() = LedController.prefs
    private lateinit var col: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private var mode = MODE_LIST
    private var pkg = ""
    private var label = ""

    // List screen
    private var installed = emptyMap<String, String>()
    private var query = ""
    private var listBody: LinearLayout? = null

    // App screen
    private var ring: ArrowRingView? = null
    private var explain: TextView? = null
    private var segments = emptyList<TextView>()
    private var colourRow: View? = null
    private var colourTitle: TextView? = null
    private var colourSwatch: View? = null
    private var reset: TextView? = null
    private var iconColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        goEdgeToEdge()
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_sub)
        preferMaxRefreshRate()
        col = findViewById(R.id.col)
        findViewById<View>(R.id.scroll).padForNavBar()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIST
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        when (mode) {
            MODE_APP -> {
                setupHeader(label)
                buildApp()
            }
            MODE_UNKNOWN -> {
                setupHeader(getString(R.string.unknown_title))
                buildUnknown()
            }
            else -> {
                setupHeader(getString(R.string.arrows_title))
                buildList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A choice made on the app screen moves it between the sections.
        if (mode == MODE_LIST && installed.isNotEmpty()) renderList()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- shared pieces ----------------------------------------------------------------------------

    private fun header(text: Int, top: Int = 22) = col.addView(TextView(this).apply {
        setText(text)
        isAllCaps = true
        textSize = 13f
        letterSpacing = 0.03f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(top), dp(16), dp(6))
        ViewCompat.setAccessibilityHeading(this, true)
    })

    private fun footer(text: CharSequence, parent: LinearLayout = col) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(getColor(R.color.text_secondary))
        setPadding(dp(16), dp(8), dp(16), 0)
        parent.addView(this)
    }

    /** A rounded group; rows added to it get a hairline between them. */
    private fun group(parent: LinearLayout = col): LinearLayout {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 18 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.glass_stroke)
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))
        return inner
    }

    private fun hairline(g: LinearLayout, inset: Int) {
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(getColor(R.color.outline)) },
            LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(inset) })
    }

    private fun row(g: LinearLayout, minH: Int = 52, inset: Int = 16): LinearLayout {
        hairline(g, inset)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(minH)
            setPadding(dp(16), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val dr = it.getDrawable(0); it.recycle(); dr
            }
            g.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun titleSub(r: LinearLayout, title: CharSequence, sub: CharSequence?): TextView? {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.text_primary))
        })
        val s = sub?.let {
            TextView(this).apply {
                text = it
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(1), 0, 0)
                box.addView(this)
            }
        }
        r.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        return s
    }

    private fun chevron(r: LinearLayout) = r.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
        alpha = 0.6f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })

    private fun appIcon(target: ImageView, p: String) {
        icons.get(p)?.let { return target.setImageDrawable(it) }
        target.tag = p
        io.execute {
            val dr = try { packageManager.getApplicationIcon(p) } catch (_: Exception) { null }
            if (dr != null) icons.put(p, dr)
            main.post { if (target.tag == p) target.setImageDrawable(dr) }
        }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * d
    }

    /** The arrow as a word, with its sign. */
    private fun arrowWord(dir: TransferDirection): String = when (dir) {
        TransferDirection.DOWNLOAD -> "↓ " + getString(R.string.arrow_download)
        TransferDirection.UPLOAD -> "↑ " + getString(R.string.arrow_upload)
        TransferDirection.UNKNOWN -> getString(R.string.arrow_auto)
    }

    private fun unknownWord(u: UnknownArrow): String = getString(when (u) {
        UnknownArrow.NONE -> R.string.unk_none
        UnknownArrow.BOTH -> R.string.unk_both
        UnknownArrow.DOWN -> R.string.unk_down
        UnknownArrow.UP -> R.string.unk_up
    })

    // ---- 1. per-app list --------------------------------------------------------------------------

    private fun buildList() {
        val search = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.surface_high))
            radius = 12 * d
            cardElevation = 0f
            strokeWidth = 0
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        bar.addView(EditText(this).apply {
            hint = getString(R.string.apps_search)
            background = null
            textSize = 16f
            maxLines = 1
            inputType = EditorInfo.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase().orEmpty()
                    renderList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        search.addView(bar)
        col.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBody)
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val names = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .associateWith { p ->
                    try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }
                }
            // Download managers and updaters have no launcher icon but are worth listing.
            val extra = (TransferInfo.knownPackages + prefs.transferApps).filter { it !in names }.mapNotNull { p ->
                try { p to pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { null }
            }
            main.post {
                if (isDestroyed) return@post
                installed = names + extra
                renderList()
            }
        }
    }

    private fun renderList() {
        val body = listBody ?: return
        body.removeAllViews()
        val holder = col
        col = body
        try {
            if (query.isNotEmpty()) {
                val hits = installed.entries
                    .filter { it.value.lowercase().contains(query) || it.key.contains(query) }
                    .sortedBy { it.value.lowercase() }
                    .take(MAX_RESULTS)
                    .map { App(it.key, it.value) }
                header(R.string.sec_search_results, top = 14)
                if (hits.isEmpty()) footer(getString(R.string.arrows_empty)) else appGroup(hits)
                return
            }
            val set = prefs.appsWithArrow().filter { it in installed }
            val auto = (prefs.transferApps + TransferInfo.knownPackages).filter { it in installed && it !in set }
            fun apps(ps: Collection<String>) = ps.map { App(it, installed.getValue(it)) }.sortedBy { it.label.lowercase() }
            if (set.isNotEmpty()) {
                header(R.string.sec_set_by_you, top = 14)
                appGroup(apps(set))
            }
            if (auto.isNotEmpty()) {
                header(R.string.sec_auto_apps, top = if (set.isEmpty()) 14 else 22)
                appGroup(apps(auto))
            }
            footer(getString(R.string.arrows_footer))
        } finally {
            col = holder
        }
    }

    private fun appGroup(apps: List<App>) {
        val g = group()
        apps.forEach { app ->
            val r = row(g, minH = 60, inset = 64)
            r.addView(ImageView(this).also { appIcon(it, app.pkg) }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
            val choice = prefs.appArrow(app.pkg)
            val known = TransferInfo.knownDirection(app.pkg)
            val sub = when {
                choice != ArrowChoice.AUTO -> getString(R.string.arrow_sub_always)
                known != null -> getString(R.string.arrow_sub_known)
                else -> getString(R.string.arrow_sub_text)
            }
            titleSub(r, app.label, sub)
            r.addView(valueChip(choice, known))
            chevron(r)
            r.setOnClickListener {
                Haptics.click(it)
                startActivity(Intent(this, ArrowsActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_APP).putExtra(EXTRA_PKG, app.pkg).putExtra(EXTRA_LABEL, app.label))
            }
        }
    }

    /** The app's arrow on the right: a tinted pill when the user chose it, grey when automatic. */
    private fun valueChip(choice: ArrowChoice, known: TransferDirection?): TextView = TextView(this).apply {
        textSize = 14f
        val (txt, tint) = when (choice) {
            ArrowChoice.DOWN -> arrowWord(TransferDirection.DOWNLOAD) to prefs.downloadColor
            ArrowChoice.UP -> arrowWord(TransferDirection.UPLOAD) to prefs.uploadColor
            ArrowChoice.NONE -> getString(R.string.arrow_none) to 0
            ArrowChoice.AUTO -> (known?.let { arrowWord(it) } ?: getString(R.string.arrow_auto)) to 0
        }
        text = txt
        if (tint != 0) {
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            // Bright ring colours are hard to read on a light card: darken the text there.
            setTextColor(if (night) tint else androidx.core.graphics.ColorUtils.blendARGB(tint, 0xFF000000.toInt(), 0.4f))
            typeface = Typeface.DEFAULT_BOLD
            background = rounded((tint and 0xFFFFFF) or 0x2E000000, 12f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        } else {
            setTextColor(getColor(R.color.text_secondary))
        }
    }

    // ---- 2. one app -------------------------------------------------------------------------------

    private fun buildApp() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(16))
        }
        top.addView(ImageView(this).also { appIcon(it, pkg) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        top.addView(TextView(this).apply {
            setText(if (ProgressStore.current()?.pkg == pkg) R.string.arrow_status_active else R.string.arrow_status_idle)
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
        })
        col.addView(top)

        // Live ring, always on black like the real screen.
        val stage = MaterialCardView(this).apply {
            setCardBackgroundColor(0xFF000000.toInt())
            radius = 22 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = 0xFF1F1F23.toInt()
        }
        ring = ArrowRingView(this).apply { fraction = 0.62f }
        stage.addView(ring, ViewGroup.MarginLayoutParams(-1, dp(150)))
        col.addView(stage, LinearLayout.LayoutParams(-1, -2))

        header(R.string.sec_arrow_on_ring)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(getColor(R.color.surface_high), 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val options = listOf(
            ArrowChoice.AUTO to (getString(R.string.arrow_auto) to getString(R.string.arrow_auto)),
            ArrowChoice.DOWN to ("↓" to getString(R.string.arrow_download)),
            ArrowChoice.UP to ("↑" to getString(R.string.arrow_upload)),
            ArrowChoice.NONE to (getString(R.string.seg_none) to getString(R.string.arrow_none)),
        )
        segments = options.map { (choice, words) ->
            TextView(this).apply {
                text = words.first
                contentDescription = words.second
                gravity = Gravity.CENTER
                textSize = if (words.first.length == 1) 19f else 15f
                setTextColor(getColor(R.color.text_primary))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (prefs.appArrow(pkg) == choice) return@setOnClickListener
                    Haptics.click(it)
                    prefs.setAppArrow(pkg, choice)
                    refreshApp()
                }
                seg.addView(this, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }
        col.addView(seg, LinearLayout.LayoutParams(-1, -2))
        explain = footer("")

        header(R.string.sec_ring_colour)
        val g = group()
        val r = row(g)
        colourRow = r
        colourTitle = titleSub(r, "", null).let { (r.getChildAt(0) as LinearLayout).getChildAt(0) as TextView }
        colourSwatch = View(this).also {
            r.addView(it, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(4) })
        }
        chevron(r)
        r.setOnClickListener { pickColour() }

        reset = TextView(this).apply {
            setText(R.string.reset_auto)
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFFFF453A.toInt())
            background = rounded(getColor(R.color.surface), 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.click(it)
                prefs.setAppArrow(pkg, ArrowChoice.AUTO)
                refreshApp()
            }
            col.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(24) })
        }

        io.execute {
            val c = LedController.colors.iconColor(pkg)
            main.post {
                if (isDestroyed) return@post
                iconColor = c
                refreshApp()
            }
        }
        refreshApp()
    }

    private fun appLook() = TransferInfo.look(pkg, TransferDirection.UNKNOWN, prefs.appArrow(pkg), prefs.unknownArrow)

    /** Same colour order as the real ring (LedController.computeMeter). */
    private fun ringColour(dir: TransferDirection): Int = when {
        prefs.progressColor != 0 -> prefs.progressColor
        prefs.progressAppColor -> prefs.appColor(pkg).takeIf { it != 0 }
            ?: TransferInfo.appColor(pkg, iconColor.takeIf { it != 0 } ?: prefs.defaultColor)
        dir == TransferDirection.UPLOAD -> prefs.uploadColor
        dir == TransferDirection.DOWNLOAD -> prefs.downloadColor
        else -> iconColor.takeIf { it != 0 } ?: prefs.defaultColor
    }

    private fun refreshApp() {
        val choice = prefs.appArrow(pkg)
        val look = appLook()
        ring?.apply {
            color = ringColour(look.direction)
            icon = if (prefs.progressArrow) look.icon else MeterIcon.NONE
        }
        segments.forEachIndexed { i, v ->
            val on = ArrowChoice.entries[i] == choice
            v.isSelected = on
            v.background = if (on) rounded(getColor(R.color.surface), 10f) else null
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            ViewCompat.setStateDescription(v, if (on) getString(R.string.selected) else null)
        }
        val known = TransferInfo.knownDirection(pkg)
        explain?.text = when (choice) {
            ArrowChoice.DOWN -> getString(R.string.arrow_explain_down, label)
            ArrowChoice.UP -> getString(R.string.arrow_explain_up, label)
            ArrowChoice.NONE -> getString(R.string.arrow_explain_none, label)
            ArrowChoice.AUTO -> if (known != null) getString(R.string.arrow_explain_known, arrowWord(known))
            else getString(R.string.arrow_explain_text, unknownWord(prefs.unknownArrow))
        }
        // The colour row edits whichever colour the ring is using right now.
        val title = when {
            prefs.progressColor != 0 -> null
            prefs.progressAppColor -> R.string.ring_colour_app
            look.direction == TransferDirection.UPLOAD -> R.string.ring_colour_upload
            look.direction == TransferDirection.DOWNLOAD -> R.string.ring_colour_download
            else -> R.string.ring_colour_app
        }
        colourRow?.visibility = if (title == null) View.GONE else View.VISIBLE
        title?.let { colourTitle?.setText(it) }
        colourSwatch?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ringColour(look.direction))
        }
        reset?.visibility = if (choice == ArrowChoice.AUTO) View.GONE else View.VISIBLE
    }

    private fun pickColour() {
        val dir = appLook().direction
        when {
            prefs.progressAppColor || dir == TransferDirection.UNKNOWN ->
                ColorPickerDialog.show(this, prefs.appColor(pkg), allowAuto = true) { c ->
                    prefs.setAppColor(pkg, c)
                    refreshApp()
                }
            else -> {
                val key = if (dir == TransferDirection.UPLOAD) "upload_color" else "download_color"
                ColorPickerDialog.show(this, ringColour(dir), allowAuto = false) { c ->
                    prefs.sp.edit().putInt(key, c).apply()
                    refreshApp()
                }
            }
        }
    }

    // ---- 3. if unknown ----------------------------------------------------------------------------

    private fun buildUnknown() {
        footer(getString(R.string.unknown_intro)).apply {
            textSize = 15f
            setPadding(dp(4), 0, dp(4), dp(14))
        }
        val g = group()
        val options = listOf(
            Triple(UnknownArrow.NONE, R.string.unk_none, R.string.unk_none_sub),
            Triple(UnknownArrow.BOTH, R.string.unk_both, R.string.unk_both_sub),
            Triple(UnknownArrow.DOWN, R.string.unk_down, R.string.unk_down_sub),
            Triple(UnknownArrow.UP, R.string.unk_up, R.string.unk_up_sub),
        )
        val checks = mutableListOf<Pair<UnknownArrow, ImageView>>()
        val rows = mutableListOf<Pair<UnknownArrow, View>>()
        fun mark() {
            val cur = prefs.unknownArrow
            checks.forEach { (u, v) -> v.visibility = if (u == cur) View.VISIBLE else View.INVISIBLE }
            rows.forEach { (u, v) -> v.isSelected = u == cur }
        }
        options.forEach { (u, title, sub) ->
            val r = row(g, minH = 64, inset = 72)
            r.setPadding(dp(10), dp(6), dp(16), dp(6))
            val mini = ArrowRingView(this).apply {
                fraction = 0.66f
                when (u) {
                    UnknownArrow.NONE -> { color = 0xFF9CA3AF.toInt(); icon = MeterIcon.NONE }
                    UnknownArrow.BOTH -> { color = 0xFF9CA3AF.toInt(); icon = MeterIcon.BOTH }
                    UnknownArrow.DOWN -> { color = prefs.downloadColor; icon = MeterIcon.DOWN }
                    UnknownArrow.UP -> { color = prefs.uploadColor; icon = MeterIcon.UP }
                }
            }
            r.addView(mini, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            titleSub(r, getString(title), getString(sub))
            val check = ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            r.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)))
            ViewCompat.setAccessibilityDelegate(r, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = prefs.unknownArrow == u
                }
            })
            r.setOnClickListener {
                if (prefs.unknownArrow == u) return@setOnClickListener
                Haptics.click(it)
                prefs.sp.edit().putString("unknown_arrow", u.key).apply()
                mark()
            }
            checks += u to check
            rows += u to r
        }
        mark()

        header(R.string.sec_order)
        val o = group()
        listOf(R.string.order_1, R.string.order_2, R.string.order_3, R.string.order_4).forEachIndexed { i, s ->
            val r = row(o, minH = 46, inset = 52)
            r.isClickable = false
            r.isFocusable = false
            r.foreground = null
            r.addView(TextView(this).apply {
                text = (i + 1).toString()
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.surface_high)) }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            titleSub(r, getString(s), null)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val MODE_LIST = "list"
        const val MODE_APP = "app"
        const val MODE_UNKNOWN = "unknown"
        private const val MAX_RESULTS = 60

        fun open(context: Context, mode: String) =
            context.startActivity(Intent(context, ArrowsActivity::class.java).putExtra(EXTRA_MODE, mode))
    }
}
