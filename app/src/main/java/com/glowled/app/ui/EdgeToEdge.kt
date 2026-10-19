package com.glowled.app.ui

import android.graphics.Color
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glowled.app.R
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar

/**
 * Transparent system bars so the UI flows under the status and navigation bars; light icons on the
 * dark theme, dark icons on the light theme.
 */
fun AppCompatActivity.goEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
)

/**
 * Asks for the display's fastest refresh rate (e.g. 120 Hz) while this screen is open. Some ROMs
 * (HyperOS) otherwise keep third-party apps at 60 Hz. Only while the app is in the foreground.
 */
fun android.app.Activity.preferMaxRefreshRate() {
    val d = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display else null)
        ?: @Suppress("DEPRECATION") windowManager.defaultDisplay ?: return
    val cur = d.mode
    val best = d.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
}

/** Wires the shared large collapsing header (layout/part_header.xml). */
fun AppCompatActivity.setupHeader(title: CharSequence) {
    findViewById<CollapsingToolbarLayout>(R.id.collapsing).title = title
    findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
}

/** Pads a scrolling view so its last item clears the navigation bar plus an optional bottom dock. */
fun View.padForNavBar(extra: () -> Int = { 0 }) {
    val base = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        v.post { v.updatePadding(bottom = base + nav + extra()) }
        insets
    }
}

/** Keeps a bottom dock above the navigation bar. */
fun View.dockAboveNavBar() {
    val base = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
        v.updatePadding(bottom = base + nav)
        insets
    }
}
