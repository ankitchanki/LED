package com.glowled.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glowled.app.R
import com.glowled.app.core.LedController
import com.glowled.app.view.GlowSwitch
import android.content.res.ColorStateList
import java.util.concurrent.Executors

/** Per-app on/off switch and LED colour. */
class AppsActivity : AppCompatActivity() {

    private data class AppItem(val pkg: String, val label: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<String, Drawable>(120)
    private var all = emptyList<AppItem>()
    private var shown = emptyList<AppItem>()
    private val prefs get() = LedController.prefs
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        goEdgeToEdge()
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_apps)
        preferMaxRefreshRate()
        setupHeader(getString(R.string.title_apps))
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val dock = findViewById<View>(R.id.searchDock)
        dock.dockAboveNavBar()
        list.padForNavBar { dock.height }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        load()
    }

    private fun load() {
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .map { pkg ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    AppItem(pkg, label)
                }
                .sortedBy { it.label.lowercase() }
            main.post {
                if (isDestroyed) return@post
                all = items
                findViewById<View>(R.id.loading).visibility = View.GONE
                filter(findViewById<EditText>(R.id.search).text.toString())
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        shown = if (query.isEmpty()) all else all.filter { it.label.lowercase().contains(query) || it.pkg.contains(query) }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val colorLabel: TextView = v.findViewById(R.id.colorLabel)
        val swatch: View = v.findViewById(R.id.swatch)
        val toggle: GlowSwitch = v.findViewById(R.id.toggle)
        var pkg: String? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = shown[position]
            h.pkg = item.pkg
            h.label.text = item.label
            bindColor(h, item.pkg)
            h.toggle.setOnCheckedChangeListener(null)
            h.toggle.isChecked = prefs.appEnabled(item.pkg)
            h.toggle.setOnCheckedChangeListener { _, on -> prefs.setAppEnabled(item.pkg, on) }

            val cached = icons.get(item.pkg)
            h.icon.setImageDrawable(cached)
            if (cached == null) {
                io.execute {
                    val d = try { packageManager.getApplicationIcon(item.pkg) } catch (_: Exception) { null }
                    if (d != null) icons.put(item.pkg, d)
                    main.post { if (h.pkg == item.pkg) h.icon.setImageDrawable(d) }
                }
            }
            val pick = View.OnClickListener {
                ColorPickerDialog.show(this@AppsActivity, prefs.appColor(item.pkg), allowAuto = true) { c ->
                    prefs.setAppColor(item.pkg, c)
                    bindColor(h, item.pkg)
                }
            }
            h.swatch.setOnClickListener(pick)
            h.itemView.setOnClickListener(pick)
        }

        private fun bindColor(h: Holder, pkg: String) {
            val custom = prefs.appColor(pkg)
            if (custom != 0) {
                h.swatch.backgroundTintList = ColorStateList.valueOf(custom)
                h.colorLabel.text = String.format("#%06X", custom and 0xFFFFFF)
            } else {
                h.colorLabel.setText(R.string.apps_auto)
                h.swatch.backgroundTintList = null
                io.execute {
                    val c = LedController.colors.iconColor(pkg).takeIf { it != 0 } ?: prefs.defaultColor
                    main.post {
                        if (h.pkg == pkg && prefs.appColor(pkg) == 0) h.swatch.backgroundTintList = ColorStateList.valueOf(c)
                    }
                }
            }
        }
    }
}
