package com.telecomandroid

import android.content.Context
import org.json.JSONObject

/**
 * What `configure()` was last given.
 *
 * Persisted, because the process that needs it most is the one JS has never run
 * in: FCM wakes a killed app, the messaging service has to build a channel and
 * a notification before anything else happens, and the only record of what the
 * app wanted is on disk.
 */
internal data class TelecomConfig(
    val appName: String,
    val incomingChannelId: String,
    val incomingChannelName: String,
    val ongoingChannelId: String,
    val ongoingChannelName: String,
    /** null = system ringtone, "" = silent, anything else = that URI. */
    val ringtoneUri: String?,
    val vibrate: Boolean,
    val ringTimeoutMs: Long,
    val answerTimeoutMs: Long,
    val declineWebhook: JSONObject?,
    val logLevel: Int,
) {
    fun save(context: Context) {
        prefs(context).edit().putString(KEY, toJson().toString()).apply()
    }

    private fun toJson() = JSONObject().apply {
        put("appName", appName)
        put("incomingChannelId", incomingChannelId)
        put("incomingChannelName", incomingChannelName)
        put("ongoingChannelId", ongoingChannelId)
        put("ongoingChannelName", ongoingChannelName)
        // JSONObject conflates "absent" and "null", and here they mean different
        // things — system ringtone versus silence — so absence is encoded.
        if (ringtoneUri != null) put("ringtoneUri", ringtoneUri)
        put("vibrate", vibrate)
        put("ringTimeoutMs", ringTimeoutMs)
        put("answerTimeoutMs", answerTimeoutMs)
        declineWebhook?.let { put("declineWebhook", it) }
        put("logLevel", logLevel)
    }

    companion object {
        private const val PREFS = "rn_telecom_android"
        private const val KEY = "config"

        const val DEFAULT_INCOMING_CHANNEL = "rn_telecom_incoming_v1"
        const val DEFAULT_ONGOING_CHANNEL = "rn_telecom_ongoing_v1"

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /**
         * Never fails. A device that rings with default settings is a far better
         * outcome than one that throws out of a messaging service because the
         * stored JSON was written by a previous version of the package.
         */
        fun load(context: Context): TelecomConfig {
            val raw = runCatching { prefs(context).getString(KEY, null) }.getOrNull()
                ?: return default(context)

            return runCatching { fromJson(JSONObject(raw), context) }
                .getOrElse {
                    Logger.w("stored config unreadable, using defaults", it)
                    default(context)
                }
        }

        fun fromJson(json: JSONObject, context: Context): TelecomConfig {
            val appName = json.optString("appName").ifBlank { appLabel(context) }
            return TelecomConfig(
                appName = appName,
                incomingChannelId = json.optString("incomingChannelId")
                    .ifBlank { DEFAULT_INCOMING_CHANNEL },
                incomingChannelName = json.optString("incomingChannelName")
                    .ifBlank { "Incoming calls" },
                ongoingChannelId = json.optString("ongoingChannelId")
                    .ifBlank { DEFAULT_ONGOING_CHANNEL },
                ongoingChannelName = json.optString("ongoingChannelName")
                    .ifBlank { "Ongoing calls" },
                ringtoneUri = if (json.has("ringtoneUri") && !json.isNull("ringtoneUri")) {
                    json.optString("ringtoneUri")
                } else {
                    null
                },
                vibrate = json.optBoolean("vibrate", true),
                ringTimeoutMs = json.optLong("ringTimeoutMs", 45_000L),
                answerTimeoutMs = json.optLong("answerTimeoutMs", 30_000L),
                declineWebhook = json.optJSONObject("declineWebhook"),
                logLevel = when (json.optString("logLevel")) {
                    "silent" -> Logger.SILENT
                    "debug" -> Logger.DEBUG
                    else -> Logger.WARN
                },
            )
        }

        fun default(context: Context) = TelecomConfig(
            appName = appLabel(context),
            incomingChannelId = DEFAULT_INCOMING_CHANNEL,
            incomingChannelName = "Incoming calls",
            ongoingChannelId = DEFAULT_ONGOING_CHANNEL,
            ongoingChannelName = "Ongoing calls",
            ringtoneUri = null,
            vibrate = true,
            ringTimeoutMs = 45_000L,
            answerTimeoutMs = 30_000L,
            declineWebhook = null,
            logLevel = Logger.WARN,
        )

        private fun appLabel(context: Context): String = runCatching {
            val app = context.applicationContext
            app.packageManager.getApplicationLabel(app.applicationInfo).toString()
        }.getOrDefault("Call")
    }
}
