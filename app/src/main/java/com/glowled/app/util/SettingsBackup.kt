package com.glowled.app.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/**
 * Saves/restores every Glow LED setting (including per-app colours and the camera position) to a
 * JSON file the user picks, so an uninstall/reinstall never means setting everything up again.
 */
object SettingsBackup {

    // Device-specific runtime state that shouldn't travel to a new install.
    private val skip = setOf(
        "root_ok", "aod_window_blocked", "transfer_apps",
        "update_latest", "update_notes", "update_checked", "update_notified",
    )

    fun write(context: Context, sp: SharedPreferences, uri: Uri): Boolean = try {
        val json = JSONObject()
        json.put("_app", "glowled")
        json.put("_version", 1)
        val values = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in skip || v == null) return@forEach
            val t = when (v) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is String -> "s"
                else -> return@forEach
            }
            values.put(k, JSONObject().put("t", t).put("v", v))
        }
        json.put("prefs", values)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
        true
    } catch (_: Exception) {
        false
    }

    /** Returns the number of settings restored, or -1 if the file isn't a Glow LED backup. */
    fun read(context: Context, sp: SharedPreferences, uri: Uri): Int = try {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
        val json = JSONObject(text)
        if (json.optString("_app") == "glowled") restore(sp, json.getJSONObject("prefs")) else -1
    } catch (_: Exception) {
        -1
    }

    private fun restore(sp: SharedPreferences, values: JSONObject): Int {
        val e = sp.edit()
        var n = 0
        values.keys().forEach { k ->
            if (k in skip) return@forEach
            val o = values.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                else -> return@forEach
            }
            n++
        }
        e.apply()
        return n
    }
}
