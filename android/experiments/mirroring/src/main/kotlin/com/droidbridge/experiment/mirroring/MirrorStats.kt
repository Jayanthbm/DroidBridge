package com.droidbridge.experiment.mirroring

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Counters for one mirroring session.
 *
 * Everything here is a count, a duration or a size — never a pixel and never a byte of the screen.
 * The Mac's received-frame log tells the other half of the story; the two together are how "the
 * stream works" is demonstrated instead of asserted.
 */
class MirrorStats {

    val state = AtomicReference("idle")

    /** Encode geometry actually in use, and the capture size that produced it. */
    val encodeWidth = AtomicInteger(0)
    val encodeHeight = AtomicInteger(0)
    val contentWidth = AtomicInteger(0)
    val contentHeight = AtomicInteger(0)

    /** Frames handed to the socket, and the bytes they occupied. */
    val framesSent = AtomicLong(0)
    val bytesSent = AtomicLong(0)
    val keyframesSent = AtomicLong(0)

    /** Frames the bounded queue discarded because the link was slower than the encoder. */
    val framesDropped = AtomicLong(0)

    /** Format messages sent: one per encode geometry. */
    val formatMessages = AtomicLong(0)

    /** Keyframe requests received from the Mac after a decoder flush. */
    val keyframeRequests = AtomicLong(0)

    /** Largest single frame, and the slowest socket write, in milliseconds. */
    val largestFrameBytes = AtomicInteger(0)
    val maxSendMs = AtomicLong(0)

    /** Re-encodes performed because the captured content changed orientation or aspect. */
    val reEncodes = AtomicInteger(0)
    val lastResize = AtomicReference("none")

    /** Wall-clock duration of the session so far. */
    val startedAtMs = AtomicLong(0)
    val lastStopReason = AtomicReference("none")

    fun resetSession() {
        state.set("starting")
        framesSent.set(0)
        bytesSent.set(0)
        keyframesSent.set(0)
        framesDropped.set(0)
        formatMessages.set(0)
        keyframeRequests.set(0)
        largestFrameBytes.set(0)
        maxSendMs.set(0)
        reEncodes.set(0)
        lastResize.set("none")
        startedAtMs.set(android.os.SystemClock.elapsedRealtime())
        lastStopReason.set("active")
    }

    fun durationMs(): Long {
        val started = startedAtMs.get()
        if (started == 0L) return 0
        return (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
    }

    /** One greppable line, emitted by the service every few seconds and at teardown. */
    fun summaryLine(): String {
        val duration = durationMs().coerceAtLeast(1)
        val seconds = duration / 1000.0
        val frames = framesSent.get()
        val bytes = bytesSent.get()
        val fps = frames / seconds
        val kbps = bytes * 8 / 1000.0 / seconds
        return (
            "MIRROR state=${state.get()} encode=${encodeWidth.get()}x${encodeHeight.get()} " +
                "content=${contentWidth.get()}x${contentHeight.get()} frames=$frames bytes=$bytes " +
                "keyframes=${keyframesSent.get()} fps=%.1f kbps=%.0f dropped=${framesDropped.get()} " +
                "formats=${formatMessages.get()} keyframeRequests=${keyframeRequests.get()} " +
                "reEncodes=${reEncodes.get()} lastResize=${lastResize.get()} " +
                "largestFrame=${largestFrameBytes.get()} maxSendMs=${maxSendMs.get()} " +
                "durationMs=$duration stopReason=${lastStopReason.get()}"
            ).format(fps, kbps)
    }
}
