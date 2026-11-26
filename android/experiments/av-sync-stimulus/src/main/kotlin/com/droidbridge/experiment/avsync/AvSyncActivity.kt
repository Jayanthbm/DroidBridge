package com.droidbridge.experiment.avsync

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * P12 A/V offset stimulus, in its own package.
 *
 * Every cycle fires two things from the same main-thread step: the whole window turns white for
 * [FLASH_MS] and a 150 ms 1 kHz burst starts. The cycle log line carries one `tNs` for both, so the
 * phone-side trigger time is exact; the measurement itself is taken on the Mac from the received
 * video frames (`P08_RECORD_DIR` timeline) and the received audio buffers
 * (`P08_AUDIO_TIMELINE_DIR`).
 *
 *   adb shell am start -n com.droidbridge.experiment.avsync/.AvSyncActivity
 *
 * Ten cycles, six seconds apart: long enough for the Mac's one-second audio windows and its
 * keyframes to settle, and short enough to keep the evidence small.
 */
class AvSyncActivity : Activity() {

    companion object {
        private const val TAG = "P12AvSync"
        private const val SAMPLE_RATE = 44_100
        private const val TONE_HZ = 1_000.0
        private const val FLASH_MS = 180L
        private const val INTERVAL_MS = 6_000L
        private const val CYCLES = 10
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var label: TextView
    private var track: AudioTrack? = null
    private var beepPcm: ShortArray = ShortArray(0)
    private var index = 0

    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        label = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = getString(R.string.stimulus_starting)
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        beepPcm = buildBeep()
        running = true
        handler.postDelayed({ cycle() }, 1_500)
    }

    private fun buildBeep(): ShortArray {
        val frames = (SAMPLE_RATE * 0.15).toInt()
        val out = ShortArray(frames * 2)
        val amp = Short.MAX_VALUE / 3
        var phase = 0.0
        for (frame in 0 until frames) {
            val value = (sin(phase) * amp).toInt().toShort()
            out[frame * 2] = value
            out[frame * 2 + 1] = value
            phase += 2.0 * PI * TONE_HZ / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        return out
    }

    private fun cycle() {
        if (!running) return
        if (index >= CYCLES) {
            label.text = resources.getQuantityString(R.plurals.stimulus_done, CYCLES, CYCLES)
            Log.i(TAG, "STIM done cycles=$CYCLES")
            return
        }
        val tNs = SystemClock.elapsedRealtimeNanos()
        index += 1
        root.setBackgroundColor(Color.WHITE)
        label.text = getString(R.string.stimulus_cycle, index, CYCLES)
        val beepAt = playBeep()
        Log.i(
            TAG,
            "STIM idx=$index tNs=$tNs beepAtNs=$beepAt flashMs=$FLASH_MS intervalMs=$INTERVAL_MS"
        )
        handler.postDelayed({
            if (running) root.setBackgroundColor(Color.BLACK)
        }, FLASH_MS)
        handler.postDelayed({ cycle() }, INTERVAL_MS)
    }

    private fun playBeep(): Long {
        val player = track ?: run {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, beepPcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            created.write(beepPcm, 0, beepPcm.size)
            track = created
            created
        }
        runCatching { player.stop() }
        runCatching { player.reloadStaticData() }
        runCatching { player.play() }
        return SystemClock.elapsedRealtimeNanos()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        super.onDestroy()
    }
}
