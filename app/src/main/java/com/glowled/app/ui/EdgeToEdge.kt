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

/** Transparent, light-icon system bars so the black UI flows under the status and navigation bars. */
fun AppCompatActivity.goEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
)

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
