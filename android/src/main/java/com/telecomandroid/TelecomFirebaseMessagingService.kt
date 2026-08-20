package com.telecomandroid

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The fast door.
 *
 * A call push handled here rings without the JavaScript runtime ever starting.
 * That is the entire reason this package exists: routed through
 * `setBackgroundMessageHandler` instead, a killed app has to boot React Native
 * before it can make a sound, and several seconds of silence on a phone in a
 * pocket is a missed call.
 *
 * Opt in from your app's manifest — see the README. It is not declared with an
 * intent-filter here on purpose, because doing so would silently race
 * `@react-native-firebase/messaging` for FCM delivery and the winner would come
 * down to manifest merge order.
 */
class TelecomFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Intercepts at the intent, not at `onMessageReceived`, because token
     * refreshes arrive the same way — and anything that is not ours has to
     * reach Firebase's own service intact, or an app that adopts this package
     * quietly loses every other push it was receiving.
     */
    override fun handleIntent(intent: Intent) {
        val marker = runCatching { intent.getStringExtra(CallRecord.PUSH_KEY) }.getOrNull()

        if (marker == CallRecord.PUSH_INCOMING || marker == CallRecord.PUSH_CANCEL) {
            super.handleIntent(intent)
            return
        }

        if (!forwardToFirebaseMessaging(intent)) super.handleIntent(intent)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (TelecomEngine.handlePush(applicationContext, message.data)) return
        super.onMessageReceived(message)
    }

    /**
     * Hands a non-call message to `@react-native-firebase/messaging`.
     *
     * Both services stay declared; ours simply wins delivery through a higher
     * intent-filter priority and passes on what it does not own. Returns false
     * when RNFirebase is not installed at all, in which case the ordinary
     * Firebase dispatch is the right thing to do.
     */
    private fun forwardToFirebaseMessaging(intent: Intent): Boolean {
        val target = runCatching {
            Intent(intent).setClassName(this, RNFIREBASE_SERVICE)
        }.getOrNull() ?: return false

        if (packageManager.resolveService(target, 0) == null) return false

        return runCatching { startService(target); true }
            .onFailure { Logger.d("could not forward push to RNFirebase: ${it.message}") }
            .getOrDefault(false)
    }

    private companion object {
        const val RNFIREBASE_SERVICE =
            "io.invertase.firebase.messaging.ReactNativeFirebaseMessagingService"
    }
}
