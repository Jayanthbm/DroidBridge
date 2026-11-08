package com.droidbridge.experiment.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P04: the parts of the clipboard probe that can be asserted without a device.
 *
 * The matrix itself needs a focused activity, which needs an unlocked phone. What is testable here
 * is the reporting: a `ReadResult` must never claim content it did not observe, because the whole
 * point of P04 is to record honestly which cases can read the clipboard and which cannot.
 */
class ReadResultTest {

    private fun result(
        hasPrimaryClip: Boolean = false,
        textLength: Int = 0,
        preview: String = "",
        mimes: List<String> = emptyList(),
        hasImage: Boolean = false,
        sensitive: Boolean = false,
        error: String? = null,
    ) = ClipboardProbe.ReadResult(
        case = "test",
        hasPrimaryClip = hasPrimaryClip,
        mimeTypes = mimes,
        textLength = textLength,
        textPreview = preview,
        hasImage = hasImage,
        hasUri = mimes.contains("text/uri-list"),
        isSensitive = sensitive,
        description = "",
        error = error,
    )

    @Test
    fun aRefusedReadReportsNoContent() {
        // The case that matters most: the OS refused, so nothing may be reported as read.
        val refused = result(hasPrimaryClip = false)

        assertFalse(refused.hasPrimaryClip)
        assertEquals(0, refused.textLength)
        assertTrue("a refused read must not carry a preview", refused.textPreview.isEmpty())
    }

    @Test
    fun aSuccessfulReadCarriesItsLengthAndPreview() {
        val read = result(hasPrimaryClip = true, textLength = 21, preview = "droidbridge-p04-sample")

        assertTrue(read.hasPrimaryClip)
        assertEquals(21, read.textLength)
        assertEquals("droidbridge-p04-sample", read.textPreview)
    }

    @Test
    fun anImageOnlyClipIsReportedAsAnImageAndNotAsText() {
        val image = result(hasPrimaryClip = true, mimes = listOf("image/png"), hasImage = true)

        assertTrue(image.hasImage)
        assertEquals(0, image.textLength)
        assertFalse("an image clip is not a URI list", image.hasUri)
    }

    @Test
    fun aUriListClipIsDetected() {
        val uri = result(hasPrimaryClip = true, mimes = listOf("text/uri-list"))
        assertTrue(uri.hasUri)
    }

    @Test
    fun theSensitiveFlagIsCarriedThrough() {
        // Whether the flag blocks a read is the open question; that it is reported is not.
        val sensitive = result(hasPrimaryClip = true, textLength = 16, sensitive = true)
        assertTrue(sensitive.isSensitive)
    }

    @Test
    fun anErrorIsReportedInsteadOfBeingSwallowed() {
        val failed = result(error = "SecurityException: denied")
        assertTrue(failed.error?.contains("SecurityException") == true)
        assertEquals(0, failed.textLength)
    }

    @Test
    fun theLogLineIsGreppableAndQuotesFreeText() {
        val line = result(
            hasPrimaryClip = true,
            textLength = 5,
            preview = "a\"b\nc",
            mimes = listOf("text/plain"),
        ).toLine()

        // Keys are stable so the evidence log can be parsed mechanically.
        assertTrue(line.contains("case=\"test\""))
        assertTrue(line.contains("hasPrimaryClip=true"))
        assertTrue(line.contains("textLen=5"))
        assertTrue(line.contains("mimes=[text/plain]"))
        assertTrue(line.contains("sensitive=false"))
        // A newline in the preview must not break the one-line-per-observation contract.
        assertFalse("the log line must not contain a raw newline", line.contains("\n"))
    }

    @Test
    fun anErrorIsIncludedInTheLogLine() {
        val line = result(error = "SecurityException: denied").toLine()
        assertTrue(line.contains("error=\"SecurityException: denied\""))
    }

    @Test
    fun anEmptyReadOmitsThePreviewField() {
        // A zero-length read has nothing to preview, and printing preview="" would suggest it
        // observed an empty string rather than nothing at all.
        val line = result(hasPrimaryClip = true, textLength = 0).toLine()
        assertFalse(line.contains("preview="))
    }
}
