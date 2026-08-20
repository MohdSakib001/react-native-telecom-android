package com.telecomandroid

import android.os.Bundle
import org.json.JSONObject

internal object CallState {
    const val RINGING = "ringing"
    const val ANSWERING = "answering"
    const val CONNECTED = "connected"
    const val ENDED = "ended"
}

internal object EndReason {
    const val LOCAL_HANGUP = "local_hangup"
    const val REMOTE_HANGUP = "remote_hangup"
    const val DECLINED = "declined"
    const val MISSED = "missed"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
    const val FAILED = "failed"
    const val TIMEOUT = "timeout"
}

internal object Route {
    const val EARPIECE = "earpiece"
    const val SPEAKER = "speaker"
    const val BLUETOOTH = "bluetooth"
    const val WIRED = "wired"
    const val UNKNOWN = "unknown"
}

/**
 * One call, from push to teardown.
 *
 * The mirror image of `CurrentCall` in `types.ts`. Mutable because a call is a
 * state machine that outlives every component that touches it — the service,
 * the notification and the Telecom session all read the same instance rather
 * than passing copies around and disagreeing about which is current.
 */
internal class CallRecord(
    val callId: String,
    val direction: String,
    val displayName: String,
    val peerId: String?,
    val avatarUrl: String?,
    val type: String,
    val extra: Map<String, String>,
    val startedAt: Long,
) {
    @Volatile var state: String = CallState.RINGING
    @Volatile var answeredAt: Long? = null
    @Volatile var muted: Boolean = false
    @Volatile var held: Boolean = false
    @Volatile var route: String = Route.EARPIECE

    val isVideo: Boolean get() = type == TYPE_VIDEO

    fun toJson(): JSONObject = JSONObject().apply {
        put("callId", callId)
        put("direction", direction)
        put("state", state)
        put("type", type)
        put("displayName", displayName)
        peerId?.let { put("peerId", it) }
        avatarUrl?.let { put("avatarUrl", it) }
        put("extra", JSONObject(extra as Map<*, *>))
        put("startedAt", startedAt)
        put("answeredAt", answeredAt ?: JSONObject.NULL)
        put("muted", muted)
        put("held", held)
        put("route", route)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(EXTRA_CALL_ID, callId)
        putString(EXTRA_DIRECTION, direction)
        putString(EXTRA_DISPLAY_NAME, displayName)
        putString(EXTRA_PEER_ID, peerId)
        putString(EXTRA_AVATAR_URL, avatarUrl)
        putString(EXTRA_TYPE, type)
        putLong(EXTRA_STARTED_AT, startedAt)
        putString(EXTRA_EXTRA_JSON, JSONObject(extra as Map<*, *>).toString())
    }

    companion object {
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"

        const val DIRECTION_INCOMING = "incoming"
        const val DIRECTION_OUTGOING = "outgoing"

        const val EXTRA_CALL_ID = "rn_telecom_call_id"
        const val EXTRA_DIRECTION = "rn_telecom_direction"
        const val EXTRA_DISPLAY_NAME = "rn_telecom_display_name"
        const val EXTRA_PEER_ID = "rn_telecom_peer_id"
        const val EXTRA_AVATAR_URL = "rn_telecom_avatar_url"
        const val EXTRA_TYPE = "rn_telecom_type"
        const val EXTRA_STARTED_AT = "rn_telecom_started_at"
        const val EXTRA_EXTRA_JSON = "rn_telecom_extra"

        /**
         * These are the reserved keys in an FCM data payload; everything else in
         * the map is handed to JS as `extra`, so a server can pass a channel
         * token or a room id along with the ring without this package needing to
         * know what either of them is.
         */
        private val RESERVED = setOf(
            "rnTelecom", "callId", "callerName", "callerId", "avatarUrl",
            "callType", "reason",
        )

        const val PUSH_KEY = "rnTelecom"
        const val PUSH_INCOMING = "incoming"
        const val PUSH_CANCEL = "cancel"

        fun isCallPush(data: Map<String, String>): Boolean =
            data[PUSH_KEY] == PUSH_INCOMING || data[PUSH_KEY] == PUSH_CANCEL

        /** Incoming, from an FCM data map. Returns null when `callId` is missing. */
        fun fromPush(data: Map<String, String>, now: Long): CallRecord? {
            val callId = data["callId"]?.takeIf { it.isNotBlank() } ?: return null
            return CallRecord(
                callId = callId,
                direction = DIRECTION_INCOMING,
                displayName = data["callerName"]?.takeIf { it.isNotBlank() } ?: "Unknown",
                peerId = data["callerId"],
                avatarUrl = data["avatarUrl"],
                type = if (data["callType"] == TYPE_VIDEO) TYPE_VIDEO else TYPE_AUDIO,
                extra = data.filterKeys { it !in RESERVED },
                startedAt = now,
            )
        }

        /** Incoming, from `reportIncoming()`. */
        fun fromIncomingJson(json: JSONObject, now: Long): CallRecord? {
            val callId = json.optString("callId").takeIf { it.isNotBlank() } ?: return null
            return CallRecord(
                callId = callId,
                direction = DIRECTION_INCOMING,
                displayName = json.optString("callerName").ifBlank { "Unknown" },
                peerId = json.optString("callerId").takeIf { it.isNotBlank() },
                avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() },
                type = if (json.optString("type") == TYPE_VIDEO) TYPE_VIDEO else TYPE_AUDIO,
                extra = readExtra(json),
                startedAt = now,
            )
        }

        /** Outgoing, from `startOutgoing()`. */
        fun fromOutgoingJson(json: JSONObject, now: Long): CallRecord? {
            val callId = json.optString("callId").takeIf { it.isNotBlank() } ?: return null
            return CallRecord(
                callId = callId,
                direction = DIRECTION_OUTGOING,
                displayName = json.optString("peerName").ifBlank { "Unknown" },
                peerId = json.optString("peerId").takeIf { it.isNotBlank() },
                avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() },
                type = if (json.optString("type") == TYPE_VIDEO) TYPE_VIDEO else TYPE_AUDIO,
                extra = readExtra(json),
                startedAt = now,
            )
        }

        fun fromBundle(bundle: Bundle): CallRecord? {
            val callId = bundle.getString(EXTRA_CALL_ID) ?: return null
            val extra = runCatching {
                val obj = JSONObject(bundle.getString(EXTRA_EXTRA_JSON) ?: "{}")
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrDefault(emptyMap())

            return CallRecord(
                callId = callId,
                direction = bundle.getString(EXTRA_DIRECTION) ?: DIRECTION_INCOMING,
                displayName = bundle.getString(EXTRA_DISPLAY_NAME) ?: "Unknown",
                peerId = bundle.getString(EXTRA_PEER_ID),
                avatarUrl = bundle.getString(EXTRA_AVATAR_URL),
                type = bundle.getString(EXTRA_TYPE) ?: TYPE_AUDIO,
                extra = extra,
                startedAt = bundle.getLong(EXTRA_STARTED_AT, System.currentTimeMillis()),
            )
        }

        private fun readExtra(json: JSONObject): Map<String, String> {
            val obj = json.optJSONObject("extra") ?: return emptyMap()
            return obj.keys().asSequence().associateWith { obj.optString(it) }
        }
    }
}
