package com.telecomandroid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The ringtone and the vibration.
 *
 * This lives here rather than on the notification channel so it can be changed
 * after the app's first run — channels freeze their sound at creation, and an
 * app that ships the wrong ringtone in v1 would be stuck with it until the user
 * reinstalls.
 *
 * Owning it also means honouring the ringer switch honestly: silent is silent,
 * vibrate is vibrate, and neither is something a notification channel would
 * have got right on its own.
 */
internal class Ringer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun start(config: TelecomConfig) {
        stop()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode != AudioManager.RINGER_MODE_SILENT && config.vibrate) startVibration()
        if (mode == AudioManager.RINGER_MODE_NORMAL) startSound(config)
    }

    private fun startSound(config: TelecomConfig) {
        // "" is a deliberate, documented choice meaning silence — for apps that
        // play their own ring from JS. It is not the same as an absent value,
        // which means "use whatever the user set as their system ringtone".
        if (config.ringtoneUri == "") return

        val uri: Uri = config.ringtoneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // RINGTONE usage is what routes this to the ring stream,
                        // so it follows the ring volume and ducks nothing else.
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Logger.w("ringtone failed to start", it)
            release()
        }
    }

    private fun startVibration() {
        val vib = resolveVibrator() ?: return
        if (!vib.hasVibrator()) return

        // 1s off, 1s on — the standard incoming-call cadence. Index 0 restarts
        // the pattern at the silence, so it does not buzz continuously.
        val pattern = longArrayOf(0, 1_000, 1_000)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
            vibrator = vib
        }.onFailure { Logger.w("vibration failed to start", it) }
    }

    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    @Synchronized
    fun stop() {
        release()
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun release() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }.onFailure { Logger.d("ringtone release failed: ${it.message}") }
        player = null
    }
}
