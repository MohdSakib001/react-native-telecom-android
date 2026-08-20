package com.telecomandroid

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The Jetpack `core-telecom` half.
 *
 * The notification is what the user sees; this is what makes it a *call* — the
 * OS pauses music, routes audio to the right endpoint, offers Bluetooth, and
 * puts the call in the log. On Android 14+ it is the platform's own call API;
 * below that, core-telecom quietly puts a ConnectionService behind it.
 *
 * Every entry point is best-effort and never throws. Registering a self-managed
 * phone account is refused outright by several large OEMs, and on some builds
 * `addCall` throws from inside the platform. Losing system integration means a
 * slightly worse call. Taking the process down on an incoming call means a
 * calling app that crashes when it rings.
 */
internal class TelecomSession(private val context: Context) {

    interface Callbacks {
        fun onAnswerRequested()
        fun onDisconnectRequested()
        fun onSetActive()
        fun onSetInactive()
        fun onRouteChanged(route: String)
        fun onMuteChanged(muted: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val control = AtomicReference<CallControlScope?>(null)
    private val endpoints = AtomicReference<List<CallEndpointCompat>>(emptyList())
    private var job: Job? = null

    @Volatile
    var registered = false
        private set

    val isActive: Boolean get() = control.get() != null

    fun start(call: CallRecord, callbacks: Callbacks) {
        // core-telecom's floor. Below it there is no self-managed API worth
        // emulating, and the notification path already works on its own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Logger.d("telecom skipped: API ${Build.VERSION.SDK_INT}")
            return
        }

        runCatching { startUnsafe(call, callbacks) }
            .onFailure {
                registered = false
                lastRegistrationSucceeded = false
                Logger.w("telecom unavailable, notification-only ring", it)
            }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startUnsafe(call: CallRecord, callbacks: Callbacks) {
        val manager = CallsManager(context)

        var capabilities = CallsManager.CAPABILITY_BASELINE
        if (call.isVideo) capabilities = capabilities or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING

        manager.registerAppWithTelecom(capabilities)
        registered = true
        lastRegistrationSucceeded = true

        val attributes = CallAttributesCompat(
            displayName = call.displayName,
            // Self-managed calls still need an address, and there is rarely a
            // real number involved. A sip URI built from the peer id is stable,
            // which matters because it is what shows in the call log.
            address = Uri.fromParts("sip", call.peerId ?: call.callId, null),
            direction = if (call.direction == CallRecord.DIRECTION_INCOMING) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = if (call.isVideo) {
                CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            } else {
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL
            },
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )

        job = scope.launch {
            runCatching {
                manager.addCall(
                    attributes,
                    onAnswer = { callbacks.onAnswerRequested() },
                    onDisconnect = { callbacks.onDisconnectRequested() },
                    onSetActive = { callbacks.onSetActive() },
                    onSetInactive = { callbacks.onSetInactive() },
                ) {
                    control.set(this)

                    launch {
                        currentCallEndpoint.collect { callbacks.onRouteChanged(routeOf(it)) }
                    }
                    launch {
                        availableEndpoints.collect { endpoints.set(it) }
                    }
                    launch {
                        // The OS mute state, not ours — a headset button or the
                        // system call UI can change it behind the app's back.
                        isMuted.collect { callbacks.onMuteChanged(it) }
                    }
                }
            }.onFailure { Logger.w("telecom session ended abnormally", it) }

            control.set(null)
        }
    }

    fun answer(isVideo: Boolean) = withControl("answer") {
        answer(
            if (isVideo) {
                CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            } else {
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL
            },
        )
    }

    fun setActive() = withControl("setActive") { setActive() }

    fun setInactive() = withControl("setInactive") { setInactive() }

    fun disconnect(reason: String) = withControl("disconnect") {
        disconnect(DisconnectCause(disconnectCauseFor(reason)))
    }

    /**
     * Resolved against what Telecom currently offers rather than assumed. Asking
     * for Bluetooth on a phone with no headset connected is not an error worth
     * raising — it is just a button the app should not have shown.
     */
    fun requestRoute(route: String) {
        val target = endpoints.get().firstOrNull { routeOf(it) == route }
        if (target == null) {
            Logger.d("no endpoint available for route $route")
            return
        }

        withControl("requestEndpointChange") { requestEndpointChange(target) }
    }

    fun availableRoutes(): List<String> =
        endpoints.get().map { routeOf(it) }.distinct().ifEmpty { listOf(Route.EARPIECE) }

    fun stop() {
        runCatching { job?.cancel() }
        job = null
        control.set(null)
        endpoints.set(emptyList())
    }

    /**
     * Every call into the Telecom scope funnels through here, for the same
     * reason the whole class is wrapped: the platform throws from inside these
     * on OEM builds we cannot test, and a failed route change is not worth a
     * crash during a live call.
     */
    private fun withControl(
        action: String,
        block: suspend CallControlScope.() -> CallControlResult,
    ) {
        val scopeRef = control.get()
        if (scopeRef == null) {
            Logger.d("telecom $action ignored: no active session")
            return
        }

        scopeRef.launch {
            runCatching { scopeRef.block() }
                .onSuccess {
                    // A refusal is Telecom's answer, not an exception: another
                    // call took the audio, or the endpoint went away mid-request.
                    if (it !is CallControlResult.Success) {
                        Logger.d("telecom $action refused: $it")
                    }
                }
                .onFailure { Logger.w("telecom $action failed", it) }
        }
    }

    private fun routeOf(endpoint: CallEndpointCompat): String = when (endpoint.type) {
        CallEndpointCompat.TYPE_EARPIECE -> Route.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> Route.SPEAKER
        CallEndpointCompat.TYPE_BLUETOOTH -> Route.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> Route.WIRED
        else -> Route.UNKNOWN
    }

    companion object {
        /**
         * Remembered across sessions so `getDiagnostics()` can answer without
         * registering a phone account as a side effect of being asked. Starts
         * false: until a call has been attempted, nobody knows.
         */
        @Volatile
        var lastRegistrationSucceeded: Boolean = false
            private set
    }

    /**
     * Narrows this module's end reasons onto the only four codes `CallControl`
     * accepts: LOCAL, REMOTE, MISSED and REJECTED.
     *
     * Anything else throws `IllegalArgumentException` from
     * `validateDisconnectCause` — and that throw is not survivable the way an
     * ordinary refusal is. It leaves androidx's `CallSession` cancelled, so
     * every later `answer` and `setActive` in the process fails with a
     * `JobCancellationException`. One illegal code, once, costs every call
     * after it until the app restarts.
     *
     * `DisconnectCause.ERROR` and `ANSWERED_ELSEWHERE` are real constants and
     * compile perfectly well; they are simply not permitted at this call site.
     * Nothing but a device could have caught that, which is why this is written
     * as an allowlist — the four legal cases named and everything else falling
     * to LOCAL — rather than by excluding the two known-bad ones. A reason
     * added later cannot reintroduce the bug.
     *
     * Only the OS's view narrows. The `ended` event still carries the true
     * reason to JS; this value decides how the call is written into the system
     * call log and nothing more. `failed` and `answered_elsewhere` land on
     * LOCAL because this device ended its own leg — MISSED would log a missed
     * call that never happened, and REJECTED would claim the user refused one.
     */
    private fun disconnectCauseFor(reason: String): Int = when (reason) {
        EndReason.DECLINED -> DisconnectCause.REJECTED
        EndReason.MISSED, EndReason.TIMEOUT -> DisconnectCause.MISSED
        EndReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
        else -> DisconnectCause.LOCAL
    }
}
