package com.droidbridge.experiment.mediaprojection

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P01 encoder-format policy tests.
 *
 * These assert the rules the encoder actually enforced on the OnePlus and the S24 Ultra: even
 * dimensions, and level 3.1 for 720p versus level 4 above it. They are not a substitute for the
 * device configure() run, which is what proves the encoder accepts the format.
 */
class CaptureFormatTest {

    @Test
    fun `odd dimensions round up to even`() {
        assertEquals(1280, CaptureFormat.roundToEven(1279))
        assertEquals(1280, CaptureFormat.roundToEven(1280))
        assertEquals(2340, CaptureFormat.roundToEven(2339))
    }

    @Test
    fun `720p uses level 3_1`() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            CaptureFormat.levelFor(1280, 720),
        )
    }

    @Test
    fun `smaller than 720p uses level 3_1`() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            CaptureFormat.levelFor(640, 360),
        )
    }

    @Test
    fun `rotated panel size uses level 4`() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            CaptureFormat.levelFor(2340, 1080),
        )
    }

    @Test
    fun `exactly 720p is the level boundary`() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            CaptureFormat.levelFor(1280, 720),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            CaptureFormat.levelFor(1281, 720),
        )
    }
}
