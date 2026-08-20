package com.telecomandroid

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * The one door into the call machine.
 *
 * The module, the messaging service and the notification buttons all arrive
 * here, and everything past this point is an intent to `CallService`. Keeping
 * the entry points this thin is what makes a cold start and a warm start the
 * same code path — the only difference between them is whether anyone is
 * listening for the events on the way out.
 */
internal object TelecomEngine {

    fun configure(context: Context, optionsJson: String) {
        val config = runCatching {
            TelecomConfig.fromJson(JSONObject(optionsJson), context)
        }.getOrElse {
            Logger.w("configure() got unparseable options, keeping defaults", it)
            TelecomConfig.default(context)
        }

        Logger.level = config.logLevel
        config.save(context)
        // Created eagerly rather than at first ring: the user may open your
        // notification settings before ever receiving a call, and an app whose
        // call channels appear only after the first missed call is unfixable.
        Notifications.ensureChannels(context, config)
    }

    /** From FCM, or from `reportIncoming()`. */
    fun ring(context: Context, call: CallRecord) {
        start(
            context,
            Intent(context, CallService::class.java)
                .setAction(CallService.ACTION_INCOMING)
                .putExtras(call.toBundle()),
        )
    }

    fun outgoing(context: Context, call: CallRecord) {
        start(
            context,
            Intent(context, CallService::class.java)
                .setAction(CallService.ACTION_OUTGOING)
                .putExtras(call.toBundle()),
        )
    }

    /**
     * A push handled here rather than in the service, because a cancel for a
     * call that is not ringing must not start a foreground service — that would
     * be an FGS start with nothing to show, which Android kills and then holds
     * against the app.
     */
    fun handlePush(context: Context, data: Map<String, String>): Boolean {
        if (!CallRecord.isCallPush(data)) return false

        when (data[CallRecord.PUSH_KEY]) {
            CallRecord.PUSH_INCOMING -> {
                val call = CallRecord.fromPush(data, System.currentTimeMillis())
                if (call == null) {
                    Logger.w("call push without a callId, ignored")
                    return true
                }
                ring(context, call)
            }

            CallRecord.PUSH_CANCEL -> {
                val callId = data["callId"]
                val reason = data["reason"] ?: EndReason.REMOTE_HANGUP
                if (callId != null && CallStore.get(callId) != null) {
                    end(context, reason)
                } else {
                    Logger.d("cancel push for a call we are not in: $callId")
                }
            }
        }

        return true
    }

    fun end(context: Context, reason: String) {
        send(context, CallService.ACTION_END) { putExtra(CallService.EXTRA_REASON, reason) }
    }

    /**
     * For everything that only makes sense mid-call.
     *
     * The `CallStore` check is not an optimisation — `startService` throws when
     * the app is in the background and no service is running, so an unmute
     * arriving a moment after the call ended would otherwise crash the app.
     */
    fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        if (CallStore.get() == null) {
            Logger.d("$action ignored: no call in progress")
            return
        }

        val intent = Intent(context, CallService::class.java).setAction(action).apply(extras)
        runCatching { context.startService(intent) }
            .onFailure { Logger.w("$action could not reach the service", it) }
    }

    private fun start(context: Context, intent: Intent) {
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Logger.e("could not start the call service", it) }
    }
}
