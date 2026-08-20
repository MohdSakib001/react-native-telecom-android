package com.telecomandroid

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * The full-screen intent target. It draws nothing.
 *
 * Its only job is the two things an ordinary activity launch cannot do from a
 * service: turn the screen on, and appear over the keyguard. It does them, hands
 * off to the host app's launcher activity, and finishes — which is why the host
 * activity must carry `showWhenLocked` and `turnScreenOn` of its own. Without
 * those, the ring lights up the phone and then hides behind the lock screen.
 */
internal class IncomingCallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        forwardToApp(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { forwardToApp(it) }
        finish()
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                val keyguard = getSystemService(KeyguardManager::class.java)
                // Null callback: whether the user actually unlocks is their
                // business. The app is launched either way, and sits behind
                // the keyguard until they do.
                keyguard?.requestDismissKeyguard(this, null)
            }.onFailure { Logger.d("requestDismissKeyguard failed: ${it.message}") }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun forwardToApp(source: Intent) {
        val launch = launchIntent(this, source.extras)
        if (launch == null) {
            Logger.e("no launcher activity found — cannot show the call")
            return
        }

        runCatching { startActivity(launch) }
            .onFailure { Logger.e("failed to launch host activity", it) }
    }

    companion object {
        /** The full-screen and content intent for a ringing call. */
        fun intent(context: Context, call: CallRecord): Intent =
            Intent(context, IncomingCallActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtras(call.toBundle())
            }

        /** Called on answer, to bring the app up over whatever is on screen. */
        fun launchApp(context: Context, call: CallRecord) {
            runCatching { context.startActivity(intent(context, call)) }
                .onFailure { Logger.e("failed to start call activity", it) }
        }

        /**
         * `SINGLE_TOP`, never `CLEAR_TOP`: the app may already be open on a
         * screen the user cares about, and answering a call is not a reason to
         * throw their navigation stack away. The extras ride along so a warm app
         * can react immediately instead of polling `getCurrentCall()`.
         */
        private fun launchIntent(context: Context, extras: Bundle?): Intent? =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    extras?.let { putExtras(it) }
                }
    }
}
