package com.glowled.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.glowled.app.R
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.NotificationStore
import com.glowled.app.core.PowerGuard
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService
import com.glowled.app.util.OemHelper
import com.glowled.app.view.LedView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var master: MaterialSwitch
    private lateinit var masterLabel: TextView
    private lateinit var status: TextView
    private lateinit var hero: LedView
    private lateinit var setupList: LinearLayout
    private val prefs get() = LedController.prefs
    private val storeListener: () -> Unit = { runOnUiThread { updateStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, b.bottom)
            insets
        }

        master = findViewById(R.id.master)
        masterLabel = findViewById(R.id.masterLabel)
        status = findViewById(R.id.status)
        hero = findViewById(R.id.hero)
        setupList = findViewById(R.id.setupList)

        master.isChecked = prefs.enabled
        master.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            updateStatus()
        }

        findViewById<View>(R.id.btnPreview).setOnClickListener { LedController.startAod(pulse = false, preview = true) }
        findViewById<View>(R.id.btnTest).setOnClickListener { sendTest() }
        findViewById<View>(R.id.btnCutout).setOnClickListener { startActivity(Intent(this, CutoutActivity::class.java)) }
        findViewById<View>(R.id.btnApps).setOnClickListener { startActivity(Intent(this, AppsActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.restrictedHint).visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        master.isChecked = prefs.enabled
        hero.post { configureHero() }
        buildSetup()
        updateStatus()
        LedController.addListener(storeListener)
    }

    override fun onPause() {
        super.onPause()
        LedController.removeListener(storeListener)
    }

    private fun configureHero() {
        // A stand-in camera hole centred in the card shows the chosen animation live.
        val loc = IntArray(2)
        hero.getLocationOnScreen(loc)
        val d = resources.displayMetrics.density
        val g = Geometry(CutoutShape.CIRCLE, loc[0] + hero.width / 2f, loc[1] + hero.height / 2f, 30 * d, 30 * d)
        val style = prefs.style.takeIf { !it.isEdge } ?: com.glowled.app.core.LedStyle.SPIN
        hero.configure(prefs, g, intArrayOf(prefs.defaultColor, 0xFF25D366.toInt(), 0xFFFF4081.toInt()), style)
    }

    private fun updateStatus() {
        masterLabel.setText(if (prefs.enabled) R.string.master_on else R.string.master_off)
        val count = NotificationStore.colors().size
        val block = PowerGuard.blockReason(this, prefs)
        status.text = when {
            !NotifyListenerService.isEnabled(this) -> getString(R.string.status_need_listener)
            block != null -> getString(R.string.status_blocked, block)
            !NotificationStore.isEmpty() -> getString(R.string.status_active, count)
            else -> getString(R.string.status_waiting)
        }
        hero.visibility = if (prefs.enabled) View.VISIBLE else View.INVISIBLE
    }

    // ---- setup checklist ------------------------------------------------------------------------

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

        setupList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        steps.forEach { s ->
            val row = inflater.inflate(R.layout.item_setup, setupList, false)
            row.findViewById<TextView>(R.id.title).text = s.title
            row.findViewById<TextView>(R.id.desc).text = s.desc
            val dotColor = when {
                s.isLink -> R.color.warn
                s.done -> R.color.ok
                s.required -> R.color.bad
                else -> R.color.warn
            }
            row.findViewById<View>(R.id.dot).backgroundTintList = ContextCompat.getColorStateList(this, dotColor)
            val btn = row.findViewById<MaterialButton>(R.id.action)
            btn.setText(if (s.isLink) R.string.open else if (s.done) R.string.granted else R.string.grant)
            btn.isEnabled = !s.done || s.isLink
            btn.setOnClickListener { s.action() }
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

    // ---- test notification ----------------------------------------------------------------------

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
