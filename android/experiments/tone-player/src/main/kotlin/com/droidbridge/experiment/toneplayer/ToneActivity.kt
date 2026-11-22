package com.droidbridge.experiment.toneplayer

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * P12 controlled audio source, in its own package.
 *
 * Playback is the only thing this app does. Which capture policy the tone publishes is chosen by
 * intent extra so a single install can serve both the allowed and the refused measurement:
 *
 *   adb shell am start -n com.droidbridge.experiment.toneplayer/.ToneActivity \
 *       --es policy allow|none --ei seconds 20
 *
 * The tone is a 440 Hz sine at a quarter of full scale, so an honest capture reproduces peak 8190
 * and RMS ≈ 5790, and anything materially below that is attenuation rather than success.
 */
class ToneActivity : Activity() {

    companion object {
        private const val TAG = "P12Audio"
        private const val SAMPLE_RATE = 44_100
        private const val FREQUENCY_HZ = 440.0
    }

    private var track: AudioTrack? = null
    @Volatile private var playing = false
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = TextView(this).apply {
            textSize = 18f
            setPadding(48, 96, 48, 48)
        }
        setContentView(info)
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    override fun onDestroy() {
        playing = false
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        super.onDestroy()
    }

    private fun start(intent: Intent?) {
        val policyName = intent?.getStringExtra("policy") ?: "allow"
        val seconds = intent?.getIntExtra("seconds", 20) ?: 20
        val allowed = policyName != "none"
        // Usage selects which platform rule applies. USAGE_MEDIA is the capturable class;
        // USAGE_VOICE_COMMUNICATION is the call-audio class that playback capture must refuse, so
        // the exclusion can be measured without placing a real call.
        val usage = when (intent?.getStringExtra("usage")) {
            "voice" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            "game" -> AudioAttributes.USAGE_GAME
            "unknown" -> AudioAttributes.USAGE_UNKNOWN
            "notification" -> AudioAttributes.USAGE_NOTIFICATION
            else -> AudioAttributes.USAGE_MEDIA
        }
        val usageName = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "USAGE_VOICE_COMMUNICATION"
            AudioAttributes.USAGE_GAME -> "USAGE_GAME"
            AudioAttributes.USAGE_UNKNOWN -> "USAGE_UNKNOWN"
            AudioAttributes.USAGE_NOTIFICATION -> "USAGE_NOTIFICATION"
            else -> "USAGE_MEDIA"
        }

        playing = false
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(
                if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .setAllowedCapturePolicy(
                if (allowed) AudioAttributes.ALLOW_CAPTURE_BY_ALL
                else AudioAttributes.ALLOW_CAPTURE_BY_NONE
            )
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = player
        playing = true
        player.play()

        val label = if (allowed) "ALLOW_CAPTURE_BY_ALL" else "ALLOW_CAPTURE_BY_NONE"
        info.text = getString(R.string.tone_status, label, usageName, seconds)
        Log.i(TAG, "tone start hz=$FREQUENCY_HZ usage=$usageName allowed=$label capturePolicy=${attributes.allowedCapturePolicy} seconds=$seconds pid=${android.os.Process.myPid()}")

        Thread({
            val amplitude = Short.MAX_VALUE / 4
            val chunk = ShortArray(2048)
            var phase = 0.0
            val endAt = System.currentTimeMillis() + seconds * 1000L
            try {
                while (playing && System.currentTimeMillis() < endAt) {
                    for (i in chunk.indices step 2) {
                        val value = (sin(phase) * amplitude).toInt().toShort()
                        chunk[i] = value
                        chunk[i + 1] = value
                        phase += 2.0 * PI * FREQUENCY_HZ / SAMPLE_RATE
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                    }
                    player.write(chunk, 0, chunk.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "tone write failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                Log.i(TAG, "tone stop pid=${android.os.Process.myPid()}")
                runCatching { player.stop() }
                runCatching { player.release() }
                if (track === player) track = null
            }
        }, "p12-tone-external").start()
    }
}
