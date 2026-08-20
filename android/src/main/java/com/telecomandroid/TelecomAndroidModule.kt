package com.telecomandroid

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JS surface. Deliberately almost empty.
 *
 * Every method here either forwards an intent or reads `CallStore`. None of the
 * call logic lives on the bridge, because the bridge is the part that is not
 * there when it matters — a push can ring, be answered and be over before a
 * React instance exists, and any state kept here would have missed all of it.
 */
internal class TelecomAndroidModule(
    private val context: ReactApplicationContext,
) : NativeTelecomAndroidSpec(context) {

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        // From here until invalidate(), events reach JS. Before and after, they
        // are dropped and `getCurrentCall()` is how the app catches up.
        CallStore.events = ::emit
    }

    override fun invalidate() {
        CallStore.events = null
        super.invalidate()
    }

    private fun emit(payload: String) {
        if (!context.hasActiveReactInstance()) return

        runCatching {
            context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT_NAME, payload)
        }.onFailure { Logger.w("event emit failed", it) }
    }

    override fun configure(optionsJson: String) {
        TelecomEngine.configure(context, optionsJson)
    }

    override fun getCurrentCall(promise: Promise) {
        promise.resolve(CallStore.get()?.toJson()?.toString())
    }

    override fun reportIncoming(payloadJson: String, promise: Promise) {
        val call = runCatching {
            CallRecord.fromIncomingJson(JSONObject(payloadJson), System.currentTimeMillis())
        }.getOrNull()

        if (call == null) {
            promise.reject(E_PAYLOAD, "reportIncoming needs at least a callId")
            return
        }

        TelecomEngine.ring(context, call)
        promise.resolve(null)
    }

    override fun startOutgoing(payloadJson: String, promise: Promise) {
        val call = runCatching {
            CallRecord.fromOutgoingJson(JSONObject(payloadJson), System.currentTimeMillis())
        }.getOrNull()

        if (call == null) {
            promise.reject(E_PAYLOAD, "startOutgoing needs at least a callId")
            return
        }

        TelecomEngine.outgoing(context, call)
        promise.resolve(null)
    }

    override fun answer(callId: String) {
        if (CallStore.get(callId) == null) return
        TelecomEngine.send(context, CallService.ACTION_ANSWER)
    }

    override fun reportConnected(callId: String) {
        // Guarded rather than trusted: a late `reportConnected` for a call that
        // already ended would otherwise cancel the *next* call's deadman.
        if (CallStore.get(callId) == null) return
        TelecomEngine.send(context, CallService.ACTION_CONNECTED)
    }

    override fun reportEnded(callId: String, reason: String) {
        if (CallStore.get(callId) == null) return
        TelecomEngine.end(context, reason)
    }

    override fun setMuted(callId: String, muted: Boolean) {
        if (CallStore.get(callId) == null) return
        TelecomEngine.send(context, CallService.ACTION_MUTE) {
            putExtra(CallService.EXTRA_FLAG, muted)
        }
    }

    override fun setHeld(callId: String, held: Boolean) {
        if (CallStore.get(callId) == null) return
        TelecomEngine.send(context, CallService.ACTION_HOLD) {
            putExtra(CallService.EXTRA_FLAG, held)
        }
    }

    override fun setAudioRoute(callId: String, route: String) {
        if (CallStore.get(callId) == null) return
        TelecomEngine.send(context, CallService.ACTION_ROUTE) {
            putExtra(CallService.EXTRA_ROUTE, route)
        }
    }

    override fun getAudioRoutes(promise: Promise) {
        // Asked of the live Telecom session rather than guessed from
        // AudioManager: what is *connected* and what a call may actually be
        // routed to are different lists, and Bluetooth is where they diverge.
        val routes = CallService.current()?.availableRoutes() ?: listOf(Route.EARPIECE)
        promise.resolve(JSONArray(routes).toString())
    }

    override fun getDiagnostics(promise: Promise) {
        runCatching { Diagnostics.collect(context).toString() }
            .onSuccess { promise.resolve(it) }
            .onFailure { promise.reject(E_DIAGNOSTICS, it) }
    }

    override fun openFullScreenIntentSettings() {
        OemSettings.openFullScreenIntentSettings(context)
    }

    override fun requestBatteryOptimisationExemption() {
        OemSettings.requestBatteryOptimisationExemption(context)
    }

    override fun openAutoStartSettings(promise: Promise) {
        promise.resolve(OemSettings.openAutoStart(context))
    }

    // Required by NativeEventEmitter. The subscription bookkeeping is JS-side;
    // native has nothing to count, because it emits to whoever is listening.
    override fun addListener(eventName: String) = Unit

    override fun removeListeners(count: Double) = Unit

    companion object {
        const val NAME = "RNTelecomAndroid"
        const val EVENT_NAME = "RNTelecomAndroid"

        private const val E_PAYLOAD = "E_TELECOM_PAYLOAD"
        private const val E_DIAGNOSTICS = "E_TELECOM_DIAGNOSTICS"
    }
}
