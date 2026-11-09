package com.droidbridge.experiment.mediaprojection

import android.media.MediaCodecInfo

/**
 * Pure policy for the capture format, kept out of the service so it can be unit-tested.
 *
 * Two rules matter and both were learned from the encoder rather than assumed:
 * - dimensions must be even, because the encoder reports 2-pixel width/height alignment;
 * - the level must rise with the content size. Level 3.1 covers 1280x720; a rotation to
 *   2340x1080 needs level 4.
 */
object CaptureFormat {

    fun roundToEven(value: Int): Int = if (value % 2 == 0) value else value + 1

    fun levelFor(width: Int, height: Int): Int =
        if (width * height > 1280 * 720) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
}
