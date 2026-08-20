package com.telecomandroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The screens Android does not have an API for.
 *
 * Several manufacturers ship an aggressive process killer with a whitelist that
 * is not exposed anywhere in the framework, and an app that is not on it is
 * simply never woken by a push. There is no permission to request and no way to
 * detect the state — the only thing an app can do is take the user to the
 * screen. These component names are the ones that have been stable across
 * enough firmware versions to be worth trying.
 */
internal object OemSettings {

    private val CANDIDATES = listOf(
        // Xiaomi / Redmi / POCO
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Oppo / Realme
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Letv, Asus, Nokia
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
        // OnePlus
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    )

    /** The first candidate this device can actually open, or null. */
    fun resolve(context: Context): Intent? {
        val packageManager = context.packageManager

        return CANDIDATES.asSequence()
            .map { (pkg, cls) ->
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            .firstOrNull { packageManager.resolveActivity(it, 0) != null }
    }

    fun openAutoStart(context: Context): Boolean {
        val intent = resolve(context) ?: return false
        return runCatching { context.startActivity(intent); true }
            .onFailure { Logger.w("autostart screen refused to open", it) }
            .getOrDefault(false)
    }

    /**
     * Android 14+ only. Below it the permission is granted by declaration, so
     * there is no screen to send anyone to and this opens app settings instead.
     */
    fun openFullScreenIntentSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }

        open(context, intent.setData(Uri.parse("package:${context.packageName}")))
    }

    /**
     * Deliberately the dialog, not the settings list. Play allows the direct
     * request for calling apps, and a one-tap prompt is the difference between
     * a user who is reachable and one who is not.
     */
    @Suppress("BatteryLife")
    fun requestBatteryOptimisationExemption(context: Context) {
        open(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    private fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Logger.w("could not open settings screen", it) }
    }
}
