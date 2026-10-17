package com.glowled.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.glowled.app.BuildConfig
import com.glowled.app.R
import com.glowled.app.core.LedController
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        goEdgeToEdge()
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setContentView(R.layout.activity_settings)
        setupHeader(getString(R.string.title_settings))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

}

class SettingsFragment : PreferenceFragmentCompat() {

    private val prefs get() = LedController.prefs

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        listView.padForNavBar { (24 * resources.displayMetrics.density).toInt() }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
        updateSummaries()
    }

    private fun updateSummaries() {
        findPreference<Preference>("default_color")?.summary =
            String.format("#%06X — used when an app has no colour of its own", prefs.defaultColor and 0xFFFFFF)
        findPreference<Preference>("quiet_start")?.summary = formatTime(prefs.quietStart)
        findPreference<Preference>("quiet_end")?.summary = formatTime(prefs.quietEnd)
    }

    private fun formatTime(minutes: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return DateFormat.getTimeFormat(requireContext()).format(c.time)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "default_color" -> ColorPickerDialog.show(requireContext(), prefs.defaultColor, allowAuto = false) {
                prefs.defaultColor = it
                updateSummaries()
            }
            "open_cutout" -> startActivity(Intent(requireContext(), CutoutActivity::class.java))
            "open_apps" -> startActivity(Intent(requireContext(), AppsActivity::class.java))
            "quiet_start", "quiet_end" -> {
                val key = preference.key
                val cur = if (key == "quiet_start") prefs.quietStart else prefs.quietEnd
                TimePickerDialog(requireContext(), { _, h, m ->
                    prefs.sp.edit().putInt(key, h * 60 + m).apply()
                    updateSummaries()
                }, cur / 60, cur % 60, DateFormat.is24HourFormat(requireContext())).show()
            }
            else -> return super.onPreferenceTreeClick(preference)
        }
        return true
    }
}
