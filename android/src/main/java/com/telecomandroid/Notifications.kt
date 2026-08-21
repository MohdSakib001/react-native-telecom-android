package com.telecomandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * The call UI the user actually sees.
 *
 * CallStyle is what makes Android treat this as a call rather than a message:
 * the ranker floats it above everything, the answer and decline buttons are
 * drawn by the system, and on a locked device it is what the full-screen intent
 * expands into.
 */
internal object Notifications {

    const val NOTIFICATION_ID = 0x7E1E // stable: one call at a time, one slot

    private const val REQ_FULL_SCREEN = 1
    private const val REQ_CONTENT = 2
    private const val REQ_ANSWER = 3
    private const val REQ_DECLINE = 4
    private const val REQ_HANGUP = 5
    private const val REQ_REDISPLAY = 6

    private const val PI_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * Channels are immutable once created, which is why this one is created
     * silent: the ringtone lives in `Ringer` instead, so an app can change it
     * after the first launch. A channel that owns the sound freezes whatever
     * the app happened to be configured with the first time it ever ran.
     *
     * Importance stays HIGH — that is the part the full-screen intent needs.
     */
    fun ensureChannels(context: Context, config: TelecomConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val incoming = NotificationChannel(
            config.incomingChannelId,
            config.incomingChannelName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Rings when someone calls you"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setBypassDnd(true)
        }

        val ongoing = NotificationChannel(
            config.ongoingChannelId,
            config.ongoingChannelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while you are on a call"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }

        manager.createNotificationChannel(incoming)
        manager.createNotificationChannel(ongoing)
    }

    /**
     * `quiet` is for a call arriving while the app is already on screen: the app
     * is drawing its own ring, so this one keeps the service alive and fills the
     * shade without peeking over it or seizing the screen.
     */
    fun buildIncoming(
        context: Context,
        config: TelecomConfig,
        call: CallRecord,
        avatar: Bitmap?,
        quiet: Boolean = false,
    ): Notification {
        val person = person(call, avatar)

        return base(context, config.incomingChannelId, call)
            .setContentTitle(call.displayName)
            .setContentText(
                if (call.isVideo) "Incoming video call" else "Incoming call"
            )
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    action(context, CallActionReceiver.ACTION_DECLINE, call.callId, REQ_DECLINE),
                    action(context, CallActionReceiver.ACTION_ANSWER, call.callId, REQ_ANSWER),
                ),
            )
            // Android exempts a CallStyle notification owned by a phoneCall
            // foreground service from being dismissed. Several OEMs ignore that,
            // and a swiped-away ring leaves the phone ringing with no controls.
            .setDeleteIntent(
                action(context, CallActionReceiver.ACTION_REDISPLAY, call.callId, REQ_REDISPLAY),
            )
            .setTimeoutAfter(config.ringTimeoutMs)
            .apply {
                if (quiet) {
                    setPriority(NotificationCompat.PRIORITY_DEFAULT).setSilent(true)
                } else {
                    // The `true` is the important half: it tells Android to
                    // launch the activity even when the screen is on, instead of
                    // quietly demoting the ring to a heads-up banner.
                    setPriority(NotificationCompat.PRIORITY_MAX)
                        .setFullScreenIntent(fullScreenIntent(context, call), true)
                }
            }
            .build()
    }

    fun buildOngoing(
        context: Context,
        config: TelecomConfig,
        call: CallRecord,
        avatar: Bitmap?,
    ): Notification {
        val connected = call.state == CallState.CONNECTED
        val builder = base(context, config.ongoingChannelId, call)
            .setContentTitle(call.displayName)
            .setContentText(
                when {
                    call.held -> "On hold"
                    connected -> "Ongoing call"
                    call.direction == CallRecord.DIRECTION_OUTGOING -> "Calling…"
                    else -> "Connecting…"
                },
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person(call, avatar),
                    action(context, CallActionReceiver.ACTION_HANGUP, call.callId, REQ_HANGUP),
                ),
            )

        // Only once media is up. A timer started at `answering` counts seconds
        // the user was staring at a silent screen, which is not a call duration.
        val answeredAt = call.answeredAt
        if (connected && answeredAt != null) {
            builder
                .setUsesChronometer(true)
                .setWhen(answeredAt)
                .setShowWhen(true)
        }

        return builder.build()
    }

    private fun base(context: Context, channelId: String, call: CallRecord) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon(context))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context, call))

    private fun person(call: CallRecord, avatar: Bitmap?): Person =
        Person.Builder()
            .setName(call.displayName)
            .setKey(call.peerId ?: call.callId)
            .setImportant(true)
            .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()

    private fun fullScreenIntent(context: Context, call: CallRecord): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_FULL_SCREEN,
            IncomingCallActivity.intent(context, call),
            PI_FLAGS,
        )

    private fun contentIntent(context: Context, call: CallRecord): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_CONTENT,
            IncomingCallActivity.intent(context, call),
            PI_FLAGS,
        )

    private fun action(
        context: Context,
        action: String,
        callId: String,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
            putExtra(CallRecord.EXTRA_CALL_ID, callId)
        },
        PI_FLAGS,
    )

    /**
     * `ic_call_notification` in the host app if it exists, otherwise the stock
     * system call glyph. Falling back to the launcher icon is tempting and
     * wrong: adaptive icons render as a white blob in the status bar.
     */
    private fun smallIcon(context: Context): Int {
        val id = context.resources.getIdentifier(
            "ic_call_notification", "drawable", context.packageName,
        )
        return if (id != 0) id else android.R.drawable.sym_action_call
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }.onFailure { Logger.w("failed to cancel notification", it) }
    }

    /**
     * Best-effort, and called off the main thread only.
     *
     * The service posts its notification without an avatar first and re-posts
     * once this returns — a foreground service has about five seconds to call
     * `startForeground`, and a slow CDN is not a reason to be killed.
     */
    fun fetchAvatar(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.onFailure { Logger.d("avatar fetch failed: ${it.message}") }.getOrNull()
    }
}
