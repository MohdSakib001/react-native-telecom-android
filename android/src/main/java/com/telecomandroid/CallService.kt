package com.telecomandroid

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Owns a call for its whole life.
 *
 * A foreground service and not a broadcast or a headless task, because it has
 * to be the thing Android is least willing to kill: it is holding a ringtone, a
 * notification, a Telecom session and two deadlines, in a process that may have
 * been started thirty seconds ago by a push and has no UI at all.
 *
 * Everything that mutates a call goes through here. The module, the notification
 * buttons and Telecom itself all send intents to this one place, so there is
 * exactly one state machine and no path where two of them disagree.
 */
internal class CallService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rn-telecom-io").apply { isDaemon = true }
    }

    private lateinit var config: TelecomConfig
    private var ringer: Ringer? = null
    private var telecom: TelecomSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var avatar: Bitmap? = null

    private val ringTimeout = Runnable { end(EndReason.MISSED) }
    private val answerTimeout = Runnable { end(EndReason.FAILED) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = TelecomConfig.load(this)
        Logger.level = config.logLevel
        Notifications.ensureChannels(this, config)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING -> CallRecord.fromBundle(intent.extras ?: return stop())
                ?.let { startIncoming(it) }

            ACTION_OUTGOING -> CallRecord.fromBundle(intent.extras ?: return stop())
                ?.let { startOutgoing(it) }

            ACTION_ANSWER -> answer()
            ACTION_DECLINE -> end(EndReason.DECLINED)
            ACTION_CONNECTED -> connected()
            ACTION_END -> end(intent.getStringExtra(EXTRA_REASON) ?: EndReason.LOCAL_HANGUP)
            ACTION_MUTE -> setMuted(intent.getBooleanExtra(EXTRA_FLAG, false))
            ACTION_HOLD -> setHeld(intent.getBooleanExtra(EXTRA_FLAG, false))
            ACTION_ROUTE -> setRoute(intent.getStringExtra(EXTRA_ROUTE) ?: Route.EARPIECE)
            ACTION_REDISPLAY -> redisplay()
            else -> return stop()
        }

        // Never restart. A call that was killed mid-ring is over; resurrecting
        // the service minutes later to ring for it would be worse than silence.
        return START_NOT_STICKY
    }

    // region lifecycle

    private fun startIncoming(call: CallRecord) {
        val existing = CallStore.get()

        if (existing != null && existing.callId == call.callId) {
            // The same call arriving twice — FCM retried, or the app reported a
            // push it also received itself. Re-running this would restart the
            // ringtone and re-arm the deadman on a call already ringing.
            Logger.d("already ringing ${call.callId}, push ignored")
            return
        }

        if (existing != null) {
            // One call at a time, by design. The second caller is told no here
            // rather than being left ringing into a device that will not answer.
            Logger.w("busy: rejecting ${call.callId} while in ${existing.callId}")
            DeclineWebhook.fire(config, call.callId, EndReason.DECLINED)
            return
        }

        CallStore.set(call)
        promote(Notifications.buildIncoming(this, config, call, null), ringingTypes())

        acquireWakeLock(config.ringTimeoutMs, wakeScreen = true)
        ringer = Ringer(this).also { it.start(config) }
        startTelecom(call)

        handler.postDelayed(ringTimeout, config.ringTimeoutMs)
        loadAvatar(call)

        CallStore.emitIncoming(call)
        Logger.d("ringing ${call.callId}")
    }

    private fun startOutgoing(call: CallRecord) {
        CallStore.set(call)
        call.state = CallState.ANSWERING
        call.answeredAt = System.currentTimeMillis()

        promote(Notifications.buildOngoing(this, config, call, null), ringingTypes())
        startTelecom(call)

        // The ring window, not the answer deadman.
        //
        // This used to arm `answerTimeout`, which exists to catch a call that
        // was answered and never got media. An outgoing call has not been
        // answered by definition, so nothing ever cancelled it and the caller's
        // own phone killed a perfectly good ring at 30 seconds — before the
        // server's own timer could mark it missed. It is only a backstop for a
        // dropped socket; the server ends an unanswered call itself.
        handler.postDelayed(ringTimeout, config.ringTimeoutMs)
        loadAvatar(call)
    }

    private fun answer() {
        val call = CallStore.get() ?: run { stop(); return }
        if (call.state != CallState.RINGING) return

        handler.removeCallbacks(ringTimeout)
        ringer?.stop()

        call.state = CallState.ANSWERING
        call.answeredAt = System.currentTimeMillis()

        telecom?.answer(call.isVideo)
        promote(Notifications.buildOngoing(this, config, call, avatar), ringingTypes())

        handler.postDelayed(answerTimeout, config.answerTimeoutMs)
        acquireWakeLock(config.answerTimeoutMs)

        // The app has to come up even when it was never running — this is the
        // whole point of the package. `getCurrentCall()` is what it reads on
        // mount, because the answer event fired before there was a JS to hear it.
        IncomingCallActivity.launchApp(this, call)

        CallStore.emitAnswer(call)
        Logger.d("answered ${call.callId}")
    }

    private fun connected() {
        val call = CallStore.get() ?: return

        // Both: an incoming call got here through `answer()` and is on the
        // answer deadman, an outgoing one is on the ring window instead.
        handler.removeCallbacks(answerTimeout)
        handler.removeCallbacks(ringTimeout)
        releaseWakeLock()

        call.state = CallState.CONNECTED
        telecom?.setActive()
        // The upgrade: from here the service is also what keeps the microphone
        // alive when the user leaves the app.
        promote(Notifications.buildOngoing(this, config, call, avatar), connectedTypes(call))
        Logger.d("connected ${call.callId}")
    }

    private fun end(reason: String) {
        val call = CallStore.get()
        handler.removeCallbacks(ringTimeout)
        handler.removeCallbacks(answerTimeout)
        ringer?.stop()
        ringer = null

        if (call != null) {
            // Only the two reasons that can happen without JS. Everything else
            // was decided by an app that is awake and can report it properly.
            if (reason == EndReason.DECLINED || reason == EndReason.MISSED) {
                DeclineWebhook.fire(config, call.callId, reason)
            }

            call.state = CallState.ENDED
            telecom?.disconnect(reason)
            CallStore.emitEnded(call.callId, reason)
            CallStore.clear(call.callId)
            Logger.d("ended ${call.callId}: $reason")
        }

        telecom?.stop()
        telecom = null
        stop()
    }

    // endregion

    // region controls

    private fun setMuted(muted: Boolean) {
        val call = CallStore.get() ?: return
        if (call.muted == muted) return

        call.muted = muted
        CallStore.emitMute(call.callId, muted)
    }

    private fun setHeld(held: Boolean) {
        val call = CallStore.get() ?: return
        if (call.held == held) return

        call.held = held
        if (held) telecom?.setInactive() else telecom?.setActive()
        promote(
            Notifications.buildOngoing(this, config, call, avatar),
            if (call.state == CallState.CONNECTED) connectedTypes(call) else ringingTypes(),
        )
        CallStore.emitHold(call.callId, held)
    }

    private fun setRoute(route: String) {
        val call = CallStore.get() ?: return
        telecom?.requestRoute(route)
        // `route` is not written here. Telecom answers through onRouteChanged
        // with what it actually did, and believing the request over the result
        // is how an app ends up showing a speaker icon on an earpiece call.
        Logger.d("route requested for ${call.callId}: $route")
    }

    // endregion

    /** The user swiped a ringing call away. Put it back. */
    private fun redisplay() {
        val call = CallStore.get() ?: return
        if (call.state != CallState.RINGING) return

        promote(Notifications.buildIncoming(this, config, call, avatar), ringingTypes())
    }

    private fun startTelecom(call: CallRecord) {
        telecom = TelecomSession(this).also { session ->
            session.start(call, object : TelecomSession.Callbacks {
                // Telecom calls back on its own dispatcher; the state machine
                // only ever runs on the main thread, so everything hops.
                override fun onAnswerRequested() {
                    handler.post { answer() }
                }

                override fun onDisconnectRequested() {
                    handler.post {
                        val ringing = CallStore.get()?.state == CallState.RINGING
                        end(if (ringing) EndReason.DECLINED else EndReason.LOCAL_HANGUP)
                    }
                }

                override fun onSetActive() {
                    handler.post { setHeld(false) }
                }

                override fun onSetInactive() {
                    handler.post { setHeld(true) }
                }

                override fun onRouteChanged(route: String) {
                    handler.post {
                        val current = CallStore.get() ?: return@post
                        if (current.route == route) return@post
                        current.route = route
                        CallStore.emitRoute(current.callId, route)
                    }
                }

                override fun onMuteChanged(muted: Boolean) {
                    handler.post { setMuted(muted) }
                }
            })
        }
    }

    fun availableRoutes(): List<String> = telecom?.availableRoutes() ?: listOf(Route.EARPIECE)

    /**
     * Posted without an avatar first, then re-posted once the image lands.
     *
     * A foreground service has roughly five seconds to call `startForeground`
     * before the system kills it, and a slow avatar CDN is not worth dying for.
     */
    private fun loadAvatar(call: CallRecord) {
        val url = call.avatarUrl ?: return

        io.execute {
            val bitmap = Notifications.fetchAvatar(url) ?: return@execute
            handler.post {
                val current = CallStore.get() ?: return@post
                if (current.callId != call.callId) return@post

                avatar = bitmap
                val notification = if (current.state == CallState.RINGING) {
                    Notifications.buildIncoming(this, config, current, bitmap)
                } else {
                    Notifications.buildOngoing(this, config, current, bitmap)
                }
                runCatching {
                    NotificationManagerCompat.from(this)
                        .notify(Notifications.NOTIFICATION_ID, notification)
                }.onFailure { Logger.w("avatar re-post failed", it) }
            }
        }
    }

    /**
     * While ringing, the service is only a call — it holds no hardware yet.
     */
    private fun ringingTypes(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            0
        }

    /**
     * Once media is up the service is also what keeps the mic (and on a video
     * call, the camera) alive in the background.
     *
     * Each type is added only if the runtime permission is actually held.
     * Android validates the claim at `startForeground` and throws a
     * SecurityException otherwise — which would cost the whole service, and
     * with it the call, over a permission the app never needed.
     */
    private fun connectedTypes(call: CallRecord): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (call.isVideo && granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Falls back to a plain notification when the OS refuses a foreground start.
     *
     * Android 12 blocks background foreground-service starts, and the allowlist
     * a high-priority FCM message grants is not unconditional — Doze, an
     * app-standby bucket or a battery saver can all take it away. A notification
     * that rings without a service is degraded; an exception here would mean not
     * ringing at all.
     */
    private fun promote(notification: Notification, types: Int) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.NOTIFICATION_ID,
                notification,
                types,
            )
        }.onFailure { error ->
            val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error is ForegroundServiceStartNotAllowedException
            Logger.w(if (blocked) "foreground start blocked" else "startForeground failed", error)

            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(Notifications.NOTIFICATION_ID, notification)
            }.onFailure { Logger.e("notification post failed — the phone will not ring", it) }
        }
    }

    /**
     * Held only while something is counting: the ring window and the gap
     * between answering and media. Once the call is connected the audio stack
     * keeps the CPU awake, and holding it any longer is just battery.
     *
     * `wakeScreen` is what turns a dark phone on, and it is not optional for a
     * ring. The documented mechanism — a full-screen intent launching an
     * activity marked `turnScreenOn` — only fires once that activity's window
     * is actually drawn, and this package's is transparent and finishes inside
     * `onCreate`. So the flag is set on a window that never appears, and
     * nothing else here was even asking for the display.
     *
     * `SCREEN_BRIGHT_WAKE_LOCK` and `ACQUIRE_CAUSES_WAKEUP` are deprecated, and
     * their replacement is the activity flag that does not work. Deprecated and
     * functional beats modern and silent when the alternative is a phone that
     * rings in the dark.
     */
    private fun acquireWakeLock(timeoutMs: Long, wakeScreen: Boolean = false) {
        releaseWakeLock()
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager

            @Suppress("DEPRECATION")
            val flags = if (wakeScreen) {
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            } else {
                PowerManager.PARTIAL_WAKE_LOCK
            }

            wakeLock = power
                .newWakeLock(flags, "$packageName:rn-telecom")
                .apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs)
                }
        }.onFailure { Logger.w("wake lock failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stop(): Int {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifications.cancel(this)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringer?.stop()
        telecom?.stop()
        releaseWakeLock()
        avatar = null
        io.shutdown()
        instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * Only ever read for `getAudioRoutes()`, which needs to ask the live
         * Telecom session what endpoints exist. Everything that *changes* a call
         * goes through an intent, so there is no second write path to get wrong.
         */
        @Volatile
        private var instance: CallService? = null

        fun current(): CallService? = instance

        const val ACTION_INCOMING = "com.telecomandroid.INCOMING"
        const val ACTION_OUTGOING = "com.telecomandroid.OUTGOING"
        const val ACTION_ANSWER = "com.telecomandroid.ANSWER"
        const val ACTION_DECLINE = "com.telecomandroid.DECLINE"
        const val ACTION_CONNECTED = "com.telecomandroid.CONNECTED"
        const val ACTION_END = "com.telecomandroid.END"
        const val ACTION_MUTE = "com.telecomandroid.MUTE"
        const val ACTION_HOLD = "com.telecomandroid.HOLD"
        const val ACTION_ROUTE = "com.telecomandroid.ROUTE"
        const val ACTION_REDISPLAY = "com.telecomandroid.REDISPLAY"

        const val EXTRA_REASON = "reason"
        const val EXTRA_FLAG = "flag"
        const val EXTRA_ROUTE = "route"
    }
}
