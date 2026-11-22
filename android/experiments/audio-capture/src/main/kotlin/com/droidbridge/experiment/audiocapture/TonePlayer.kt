package com.droidbridge.experiment.audiocapture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * P12 tone source.
 *
 * A controlled tone is the point: measuring capture against a third-party player would confound the
 * result with that app's own capture policy. This plays the same signal twice, and the only
 * difference is the policy the track publishes — `ALLOW_CAPTURE_BY_ALL` versus
 * `ALLOW_CAPTURE_BY_NONE` — so a difference in what the capture side receives is attributable to the
 * policy and nothing else.
 */
object TonePlayer {

    private const val TAG = "P12Audio"
    private const val SAMPLE_RATE = 44_100
    private const val FREQUENCY_HZ = 440.0

    @Volatile private var track: AudioTrack? = null
    @Volatile private var playing = false

    fun play(allowed: Boolean, seconds: Int = 20) {
        stop()
        val policy = if (allowed) {
            AudioAttributes.ALLOW_CAPTURE_BY_ALL
        } else {
            AudioAttributes.ALLOW_CAPTURE_BY_NONE
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(policy)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE)
        val player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = player
        playing = true
        player.play()
        Log.i(
            TAG,
            "tone start hz=$FREQUENCY_HZ allowed=${if (allowed) "ALLOW_CAPTURE_BY_ALL" else "ALLOW_CAPTURE_BY_NONE"} " +
                "capturePolicy=${attributes.allowedCapturePolicy} seconds=$seconds"
        )

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
                Log.i(TAG, "tone stop")
                runCatching { player.stop() }
                runCatching { player.release() }
                if (track === player) track = null
            }
        }, "p12-tone").start()
    }

    fun stop() {
        playing = false
        track?.let {
            runCatching { it.pause() }
            runCatching { it.stop() }
        }
    }
}
