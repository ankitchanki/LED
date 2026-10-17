package com.glowled.app.util

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/** Shortcuts into the extra background/autostart managers that many brands add on top of Android. */
object OemHelper {

    private val manufacturer = Build.MANUFACTURER.lowercase()

    /** Brand name if this phone is known to kill background apps aggressively, else null. */
    val brand: String?
        get() = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "Xiaomi"
            manufacturer.contains("huawei") -> "Huawei"
            manufacturer.contains("honor") -> "Honor"
            manufacturer.contains("oppo") -> "OPPO"
            manufacturer.contains("realme") -> "realme"
            manufacturer.contains("oneplus") -> "OnePlus"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "vivo"
            manufacturer.contains("samsung") -> "Samsung"
            manufacturer.contains("asus") -> "ASUS"
            manufacturer.contains("meizu") -> "Meizu"
            manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") -> "Transsion"
            manufacturer.contains("nothing") -> null
            else -> null
        }

    val isXiaomi get() = brand == "Xiaomi"

    // MIUI / HyperOS private app-ops (not part of AOSP).
    private const val MIUI_OP_SHOW_WHEN_LOCKED = 10020
    private const val MIUI_OP_BACKGROUND_START = 10021

    /** Null when the state can't be read (non-Xiaomi, or blocked hidden API). */
    private fun miuiOpAllowed(context: Context, op: Int): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        (m.invoke(ops, op, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }

    /** Xiaomi "Show on lock screen" + "Display pop-up windows while running in background". */
    fun miuiLockScreenAllowed(context: Context): Boolean? {
        if (!isXiaomi) return null
        val a = miuiOpAllowed(context, MIUI_OP_SHOW_WHEN_LOCKED) ?: return null
        val b = miuiOpAllowed(context, MIUI_OP_BACKGROUND_START) ?: return null
        return a && b
    }

    /** Opens the MIUI/HyperOS "Other permissions" page for this app. */
    fun openMiuiPermissions(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
        val tries = listOf(
            Intent(editor).setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            Intent(editor).setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
            editor,
        )
        if (tries.none { tryStart(context, it) }) openAppInfo(context)
    }

    private val autostart = listOf(
        cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        cn("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        cn("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        cn("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        cn("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        cn("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        cn("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
        cn("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
    )

    private fun cn(p: String, c: String) = ComponentName(p, c)

    /** Opens the brand's autostart page, or the app's info page as a fallback. */
    fun openAutostart(context: Context) {
        for (c in autostart) {
            if (tryStart(context, Intent().setComponent(c))) return
        }
        openAppInfo(context)
    }

    fun openAppInfo(context: Context) {
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
