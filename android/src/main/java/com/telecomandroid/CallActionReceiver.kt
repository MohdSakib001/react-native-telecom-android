package com.telecomandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The answer and decline buttons.
 *
 * A receiver rather than an activity, because declining has to work on a locked
 * screen without unlocking it, and answering has to be recorded before the app
 * is anywhere near being drawn. It does no work itself — it hands the action to
 * the service, which is the only thing allowed to change a call.
 */
internal class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallRecord.EXTRA_CALL_ID) ?: return

        // A button belonging to a call that has already ended — the caller hung
        // up while the user's thumb was moving. Ignore it rather than starting a
        // service for a call that no longer exists.
        if (CallStore.get(callId) == null) {
            Logger.d("stale action ${intent.action} for $callId")
            return
        }

        when (intent.action) {
            ACTION_ANSWER -> TelecomEngine.send(context, CallService.ACTION_ANSWER)
            ACTION_DECLINE -> TelecomEngine.send(context, CallService.ACTION_DECLINE)
            ACTION_HANGUP -> TelecomEngine.end(context, EndReason.LOCAL_HANGUP)
            ACTION_REDISPLAY -> TelecomEngine.send(context, CallService.ACTION_REDISPLAY)
            else -> Logger.d("unknown action ${intent.action}")
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.telecomandroid.action.ANSWER"
        const val ACTION_DECLINE = "com.telecomandroid.action.DECLINE"
        const val ACTION_HANGUP = "com.telecomandroid.action.HANGUP"
        const val ACTION_REDISPLAY = "com.telecomandroid.action.REDISPLAY"
    }
}
