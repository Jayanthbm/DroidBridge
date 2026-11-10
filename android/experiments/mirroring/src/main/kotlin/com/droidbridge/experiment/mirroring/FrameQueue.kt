package com.droidbridge.experiment.mirroring

/**
 * A bounded queue between the encoder and the socket.
 *
 * ## Why a queue exists at all
 *
 * The drain loop must hand its output buffer back to MediaCodec quickly or the encoder stops
 * producing. A blocking socket write inside that loop would stall the encoder and, on a slow or
 * wedged link, make the latency grow without bound: the phone would stream video from ten seconds ago.
 *
 * ## Why it is bounded, and what it drops first
 *
 * A backlog of old frames is worse than a missing frame — a viewer wants the newest picture. When the
 * queue is full, the **oldest non-keyframe** is discarded to make room, and only then is the incoming
 * frame considered. A keyframe is never dropped to make room for a P-frame: the receiver needs it to
 * (re)start, and dropping it would leave the Mac waiting for the next one.
 *
 * Deliberately not thread-safe by accident: every method synchronises on the queue's own monitor, so
 * the encoder thread and the sender thread cannot interleave a drop with a take.
 */
class FrameQueue(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** One queued access unit, already framed for the wire. */
    class Entry(
        val ptsUs: Long,
        val payload: ByteArray,
        val isKeyframe: Boolean,
    )

    private val entries = ArrayDeque<Entry>()

    /** Frames discarded because the queue was full. */
    var dropped: Long = 0
        private set

    val size: Int get() = synchronized(this) { entries.size }

    /**
     * Offers one frame.
     *
     * @return true when the frame was kept (possibly after evicting older frames)
     */
    fun offer(entry: Entry): Boolean = synchronized(this) {
        if (entries.size >= capacity) {
            if (entry.isKeyframe) {
                // Make room by discarding everything older: the frames before a keyframe are of no
                // use to a decoder that is about to restart from it.
                dropped += entries.size
                entries.clear()
            } else {
                // ArrayDeque has no predicate remove, so the oldest non-keyframe is located by
                // index. The scan is bounded by the queue capacity (a handful of frames).
                val victim = entries.indexOfFirst { !it.isKeyframe }
                if (victim < 0) {
                    // Every queued frame is a keyframe. Dropping the incoming P-frame is the only
                    // option that keeps the queue's promise not to discard keyframes.
                    dropped += 1
                    return false
                }
                entries.removeAt(victim)
                dropped += 1
            }
        }
        entries.addLast(entry)
        true
    }

    /** Removes and returns the oldest frame, or null when empty. */
    fun take(): Entry? = synchronized(this) { entries.removeFirstOrNull() }

    /** Discards everything queued, counting the frames as dropped. */
    fun clear() = synchronized(this) {
        dropped += entries.size
        entries.clear()
    }

    /** Total frames currently queued plus those already dropped. */
    val offered: Long get() = synchronized(this) { dropped + entries.size }
}
