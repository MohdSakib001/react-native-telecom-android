package com.telecomandroid

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * An honest answer to "why didn't my phone ring?".
 *
 * Every one of these is a real, silent way an Android device stops delivering
 * calls, and none of them raises an error at the time — the push simply lands
 * and nothing happens. Users blame the app, so the app should be able to point
 * at the setting.
 */
internal object Diagnostics {

    fun collect(context: Context): JSONObject {
        val config = TelecomConfig.load(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { manager?.getNotificationChannel(config.incomingChannelId) }.getOrNull()
        } else {
            null
        }

        return JSONObject().apply {
            put(
                "notificationsEnabled",
                runCatching {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(true),
            )

            // A channel that exists and is not IMPORTANCE_NONE. Users turn these
            // off from the shade by accident more often than anyone expects.
            put(
                "incomingChannelEnabled",
                channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE,
            )
            put("incomingChannelImportance", importanceName(channel?.importance))

            put("canUseFullScreenIntent", canUseFullScreenIntent(context, manager))
            put("ignoringBatteryOptimisations", ignoringBatteryOptimisations(context))
            put("telecomRegistered", TelecomSession.lastRegistrationSucceeded)
            put("hasAutoStartSettings", OemSettings.resolve(context) != null)
            put("manufacturer", Build.MANUFACTURER ?: "unknown")
            put("sdkInt", Build.VERSION.SDK_INT)
        }
    }

    /**
     * Android 14 made this a grant rather than a permission you simply declare.
     * Without it, an incoming call is a heads-up banner — which on a locked
     * phone in a pocket is indistinguishable from not ringing at all.
     */
    private fun canUseFullScreenIntent(
        context: Context,
        manager: NotificationManager?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching { manager?.canUseFullScreenIntent() ?: false }.getOrDefault(false)
    }

    private fun ignoringBatteryOptimisations(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    private fun importanceName(importance: Int?): String = when (importance) {
        null -> "unknown"
        NotificationManager.IMPORTANCE_NONE -> "none"
        NotificationManager.IMPORTANCE_MIN -> "min"
        NotificationManager.IMPORTANCE_LOW -> "low"
        NotificationManager.IMPORTANCE_DEFAULT -> "default"
        NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "high"
        else -> "unknown"
    }
}
