package com.telecomandroid

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place that knows what call this process is in.
 *
 * A process-wide object rather than module state, because the module is the
 * last thing to exist and the first thing to die: FCM can start the service,
 * ring, and have the user decline — all before a React instance exists, and
 * again after one has been torn down. The call outlives the bridge, so the call
 * cannot live on the bridge.
 *
 * Only one call at a time. Call waiting is a genuinely different product with
 * its own UI, and pretending to support it with a map would mostly produce
 * calls that cannot be ended.
 */
internal object CallStore {

    private val current = AtomicReference<CallRecord?>(null)

    /**
     * Set by the module when React is up, cleared when it goes away. Null means
     * events are dropped — which is fine and expected, because `getCurrentCall()`
     * is the cold-start source of truth, not a replayed event queue.
     */
    @Volatile
    var events: ((String) -> Unit)? = null

    fun get(): CallRecord? = current.get()

    fun get(callId: String): CallRecord? = current.get()?.takeIf { it.callId == callId }

    fun set(call: CallRecord?) {
        current.set(call)
    }

    /** Clears only if the call being cleared is still the current one. */
    fun clear(callId: String) {
        current.compareAndSet(current.get()?.takeIf { it.callId == callId }, null)
    }

    fun emit(type: String, build: JSONObject.() -> Unit = {}) {
        val sink = events
        val payload = JSONObject().apply {
            put("type", type)
            build()
        }

        if (sink == null) {
            Logger.d("event dropped (no JS): $payload")
            return
        }

        runCatching { sink(payload.toString()) }
            .onFailure { Logger.w("failed to emit $type", it) }
    }

    fun emitIncoming(call: CallRecord) = emit("incoming") { put("call", call.toJson()) }

    fun emitAnswer(call: CallRecord) = emit("answer") {
        put("callId", call.callId)
        put("call", call.toJson())
    }

    fun emitDecline(callId: String) = emit("decline") { put("callId", callId) }

    fun emitEnded(callId: String, reason: String) = emit("ended") {
        put("callId", callId)
        put("reason", reason)
    }

    fun emitMute(callId: String, muted: Boolean) = emit("muteChanged") {
        put("callId", callId)
        put("muted", muted)
    }

    fun emitHold(callId: String, held: Boolean) = emit("holdChanged") {
        put("callId", callId)
        put("held", held)
    }

    fun emitRoute(callId: String, route: String) = emit("audioRouteChanged") {
        put("callId", callId)
        put("route", route)
    }
}
