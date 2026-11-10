package com.droidbridge.experiment.mirroring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry and keyframe rules, tested without a device.
 *
 * Every case here is a measurement from P01 restated as an assertion: the encoder's 2-pixel
 * alignment, the 1280 long-side cap chosen for the 8 GB M1, and the rule that a receiver never
 * starts on a P-frame.
 */
class MirrorPolicyTest {

    @Test
    fun `portrait panel is scaled to the long-side cap and stays portrait`() {
        val (width, height) = MirrorPolicy.fitEncodeSize(1440, 3120)
        assertEquals(1280, height)
        assertEquals(0, width % 2)
        assertTrue("width $width must stay portrait", width < height)
        // Aspect preserved to within the alignment rounding.
        val expected = 1440.0 / 3120.0
        assertEquals(expected, width.toDouble() / height, 0.005)
    }

    @Test
    fun `landscape content within the cap is not scaled`() {
        assertEquals(1280 to 720, MirrorPolicy.fitEncodeSize(1280, 720))
        assertEquals(640 to 360, MirrorPolicy.fitEncodeSize(640, 360))
    }

    @Test
    fun `square content becomes a square within the cap`() {
        assertEquals(1280 to 1280, MirrorPolicy.fitEncodeSize(2000, 2000))
    }

    @Test
    fun `alignment rounds an odd value up to the encoder's requirement`() {
        assertEquals(4, MirrorPolicy.align(3))
        assertEquals(4, MirrorPolicy.align(4))
        assertEquals(0, MirrorPolicy.align(0))
    }

    @Test
    fun `every fitted size is even`() {
        val sizes = listOf(1080 to 2400, 1441 to 3119, 100 to 100, 3120 to 1440, 3 to 5)
        for ((width, height) in sizes) {
            val (fittedWidth, fittedHeight) = MirrorPolicy.fitEncodeSize(width, height)
            assertEquals("width for $width x $height", 0, fittedWidth % 2)
            assertEquals("height for $width x $height", 0, fittedHeight % 2)
            assertTrue("long side for $width x $height", maxOf(fittedWidth, fittedHeight) <= 1280)
        }
    }

    @Test
    fun `rotation needs a re-encode`() {
        assertTrue(MirrorPolicy.needsReEncode(1280, 720, 720, 1280))
    }

    @Test
    fun `same aspect does not need a re-encode`() {
        assertFalse(MirrorPolicy.needsReEncode(1280, 720, 1280, 720))
    }

    @Test
    fun `a one-pixel wobble does not force a re-encode`() {
        // 1441 instead of 1440 moves the fitted width by at most one aligned step; the aspect change
        // is far below the 2% tolerance, and a re-encode would cost a keyframe and a format message.
        assertFalse(MirrorPolicy.needsReEncode(590, 1280, 1441, 3120))
    }

    @Test
    fun `a zero content size is ignored rather than treated as a rotation`() {
        assertFalse(MirrorPolicy.needsReEncode(1280, 720, 0, 0))
    }

    @Test
    fun `frames are dropped until the first keyframe`() {
        val gate = MirrorPolicy.FrameGate()
        assertTrue(gate.isAwaitingKeyframe)
        assertFalse(gate.admit(isKeyframe = false))
        assertFalse(gate.admit(isKeyframe = false))
        assertTrue(gate.admit(isKeyframe = true))
        assertFalse(gate.isAwaitingKeyframe)
        assertTrue(gate.admit(isKeyframe = false))
    }

    @Test
    fun `requiring a keyframe re-arms the gate`() {
        val gate = MirrorPolicy.FrameGate()
        assertTrue(gate.admit(isKeyframe = true))
        gate.requireKeyframe()
        assertTrue(gate.isAwaitingKeyframe)
        assertFalse(gate.admit(isKeyframe = false))
        assertTrue(gate.admit(isKeyframe = true))
    }
}
