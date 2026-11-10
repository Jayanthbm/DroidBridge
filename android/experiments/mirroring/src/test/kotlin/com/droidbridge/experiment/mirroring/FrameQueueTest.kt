package com.droidbridge.experiment.mirroring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded frame queue.
 *
 * The properties that matter: the newest frames are kept, a keyframe is never discarded for a
 * P-frame, and every discard is counted so the evidence log can show loss instead of hiding it.
 */
class FrameQueueTest {

    private fun frame(pts: Long, keyframe: Boolean = false) =
        FrameQueue.Entry(ptsUs = pts, payload = byteArrayOf(pts.toByte()), isKeyframe = keyframe)

    @Test
    fun `frames come out in the order they went in`() {
        val queue = FrameQueue(4)
        queue.offer(frame(1))
        queue.offer(frame(2))
        queue.offer(frame(3))

        assertEquals(1L, queue.take()!!.ptsUs)
        assertEquals(2L, queue.take()!!.ptsUs)
        assertEquals(3L, queue.take()!!.ptsUs)
        assertNull(queue.take())
        assertEquals(0L, queue.dropped)
    }

    @Test
    fun `a full queue drops the oldest non-keyframe for a new frame`() {
        val queue = FrameQueue(3)
        queue.offer(frame(1))
        queue.offer(frame(2))
        queue.offer(frame(3))

        assertTrue(queue.offer(frame(4)))

        assertEquals(3, queue.size)
        assertEquals(1L, queue.dropped)
        assertEquals(2L, queue.take()!!.ptsUs)
        assertEquals(3L, queue.take()!!.ptsUs)
        assertEquals(4L, queue.take()!!.ptsUs)
    }

    @Test
    fun `the newest frame is always kept`() {
        val queue = FrameQueue(2)
        queue.offer(frame(1))
        queue.offer(frame(2))
        queue.offer(frame(3))

        assertEquals(1L, queue.dropped)
        assertEquals(2L, queue.take()!!.ptsUs)
        assertEquals(3L, queue.take()!!.ptsUs)
    }

    @Test
    fun `an incoming keyframe clears older frames, including older keyframes`() {
        val queue = FrameQueue(4)
        queue.offer(frame(1, keyframe = true))
        queue.offer(frame(2))
        queue.offer(frame(3))
        queue.offer(frame(4))

        assertTrue(queue.offer(frame(5, keyframe = true)))

        assertEquals(1, queue.size)
        assertEquals(4L, queue.dropped)
        assertEquals(5L, queue.take()!!.ptsUs)
    }

    @Test
    fun `a keyframe is not evicted to make room for a p-frame`() {
        val queue = FrameQueue(2)
        queue.offer(frame(1, keyframe = true))
        queue.offer(frame(2, keyframe = true))

        // Both queued frames are keyframes, so the only way to keep the promise is to drop the
        // incoming P-frame. It is counted, and the keyframes stay available to the receiver.
        assertFalse(queue.offer(frame(3)))

        assertEquals(2, queue.size)
        assertEquals(1L, queue.dropped)
        assertEquals(1L, queue.take()!!.ptsUs)
        assertEquals(2L, queue.take()!!.ptsUs)
    }

    @Test
    fun `clear discards everything and counts it`() {
        val queue = FrameQueue(4)
        queue.offer(frame(1))
        queue.offer(frame(2))

        queue.clear()

        assertEquals(0, queue.size)
        assertEquals(2L, queue.dropped)
        assertNull(queue.take())
    }

    @Test
    fun `offer reports the stored entry and preserves the payload`() {
        val queue = FrameQueue(1)
        val entry = frame(7, keyframe = true)
        assertTrue(queue.offer(entry))

        val taken = queue.take()!!
        assertSame(entry, taken)
        assertTrue(taken.isKeyframe)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive capacity is refused`() {
        FrameQueue(0)
    }
}
