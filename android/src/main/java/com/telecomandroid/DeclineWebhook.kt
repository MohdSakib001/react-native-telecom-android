package com.telecomandroid

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tells the server the call was refused, from native, without JS.
 *
 * This is the fix for the quiet failure every VoIP app ships with: a killed
 * phone rings, the user hits decline on the lock screen, and nothing is sent —
 * because the JS that would have sent it never booted. The caller sits watching
 * a ringing screen until it times out.
 *
 * Fired for `declined` and `missed` only. Everything else happened with the app
 * awake, so JS can report it with the auth and retries it already has.
 */
internal object DeclineWebhook {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rn-telecom-webhook").apply { isDaemon = true }
    }

    fun fire(config: TelecomConfig, callId: String, reason: String) {
        val spec = config.declineWebhook ?: return
        val url = spec.optString("url").takeIf { it.isNotBlank() } ?: return

        executor.execute {
            runCatching { send(spec, url, callId, reason) }
                .onSuccess { Logger.d("decline webhook -> $it") }
                .onFailure { Logger.w("decline webhook failed", it) }
        }
    }

    private fun send(
        spec: JSONObject,
        url: String,
        callId: String,
        reason: String,
    ): Int {
        val body = spec.optString("body")
            .ifBlank { """{"callId":"{callId}","reason":"{reason}"}""" }
            .replace("{callId}", callId)
            .replace("{reason}", reason)

        val connection = (URL(url.replace("{callId}", callId).replace("{reason}", reason))
            .openConnection() as HttpURLConnection).apply {
            requestMethod = spec.optString("method").ifBlank { "POST" }
            val timeout = spec.optInt("timeoutMs", 10_000)
            connectTimeout = timeout
            readTimeout = timeout
            doOutput = true
            setRequestProperty("Content-Type", "application/json")

            spec.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { setRequestProperty(it, headers.optString(it)) }
            }
        }

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
