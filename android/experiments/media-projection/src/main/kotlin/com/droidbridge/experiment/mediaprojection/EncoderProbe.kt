package com.droidbridge.experiment.mediaprojection

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Reads the H.264 encoder's real capabilities instead of assuming them.
 *
 * P01 asks for measured facts, not documentation: which sizes are accepted, which colour formats
 * exist, which profiles are supported, and what the encoder actually reports back. Every value
 * here comes from `MediaCodecList` on the running device (R-29, U18).
 *
 * Nothing here guesses. If a capability cannot be read, the field says so.
 */
object EncoderProbe {

    private const val TAG = "P01Probe"
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** The sizes P01 asks to test explicitly, plus the device's own panel size. */
    private val SIZES_TO_TEST = listOf(
        3120 to 1440,
        2560 to 1440,
        1920 to 1080,
        1280 to 720,
    )

    data class SizeResult(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val supported: Boolean,
        val detail: String,
    )

    data class Report(
        val encoderName: String,
        val isHardwareAccelerated: Boolean,
        val colorFormats: List<Int>,
        val colorFormatNames: List<String>,
        val supportedTypes: List<String>,
        val profiles: List<String>,
        val levels: List<String>,
        val sizeResults: List<SizeResult>,
        val bitrateRange: String,
        val widthRange: String,
        val heightRange: String,
        val frameRateRange: String,
        val alignment: String,
    ) {
        /** A flat, greppable rendering — this is what goes into the evidence report. */
        fun toReportText(): String = buildString {
            appendLine("encoder=$encoderName")
            appendLine("hardwareAccelerated=$isHardwareAccelerated")
            appendLine("colorFormats=$colorFormatNames")
            appendLine("supportedTypes=$supportedTypes")
            appendLine("profiles=$profiles")
            appendLine("levels=$levels")
            appendLine("bitrateRange=$bitrateRange")
            appendLine("widthRange=$widthRange")
            appendLine("heightRange=$heightRange")
            appendLine("frameRateRange=$frameRateRange")
            appendLine("alignment=$alignment")
            sizeResults.forEach { r ->
                appendLine(
                    "size=${r.width}x${r.height}@${r.frameRate} supported=${r.supported} ${r.detail}",
                )
            }
        }
    }

    fun probe(): Report {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val infos = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }

        // Prefer a hardware encoder: a software encoder would make every bitrate and latency
        // measurement meaningless for the product.
        val chosen = infos.firstOrNull { !it.isSoftwareOnly } ?: infos.firstOrNull()
            ?: return Report(
                encoderName = "<no AVC encoder found>",
                isHardwareAccelerated = false,
                colorFormats = emptyList(),
                colorFormatNames = emptyList(),
                supportedTypes = emptyList(),
                profiles = emptyList(),
                levels = emptyList(),
                sizeResults = emptyList(),
                bitrateRange = "n/a",
                widthRange = "n/a",
                heightRange = "n/a",
                frameRateRange = "n/a",
                alignment = "n/a",
            )

        val caps = chosen.getCapabilitiesForType(MIME)
        // videoCapabilities is nullable on the platform type. An encoder that reports no video
        // capabilities cannot be used for this pipeline, and saying so is better than a crash.
        val video = caps.videoCapabilities
        if (video == null) {
            return Report(
                encoderName = chosen.name,
                isHardwareAccelerated = !chosen.isSoftwareOnly,
                colorFormats = caps.colorFormats.toList(),
                colorFormatNames = caps.colorFormats.map { colorFormatName(it) },
                supportedTypes = chosen.supportedTypes.toList(),
                profiles = emptyList(),
                levels = emptyList(),
                sizeResults = emptyList(),
                bitrateRange = "n/a (no videoCapabilities)",
                widthRange = "n/a",
                heightRange = "n/a",
                frameRateRange = "n/a",
                alignment = "n/a",
            )
        }

        val profileNames = caps.profileLevels.map { pl ->
            "${profileName(pl.profile)}/${levelName(pl.level)}"
        }.distinct()

        val sizeResults = SIZES_TO_TEST.map { (w, h) ->
            val supported = try {
                video.areSizeAndRateSupported(w, h, 30.0)
            } catch (e: IllegalArgumentException) {
                false
            }
            SizeResult(
                width = w,
                height = h,
                frameRate = 30,
                supported = supported,
                detail = if (supported) {
                    "accepted"
                } else {
                    // Record the exact rejection, which is what U18 asks for. The exception type
                    // and message are the evidence, so they are captured verbatim.
                    runCatching { video.getSupportedFrameRatesFor(w, h) }
                        .exceptionOrNull()
                        ?.let { "rejected: ${it.javaClass.simpleName}: ${it.message}" }
                        ?: "rejected: areSizeAndRateSupported=false"
                },
            )
        }

        val report = Report(
            encoderName = chosen.name,
            isHardwareAccelerated = !chosen.isSoftwareOnly,
            colorFormats = caps.colorFormats.toList(),
            colorFormatNames = caps.colorFormats.map { colorFormatName(it) },
            supportedTypes = chosen.supportedTypes.toList(),
            profiles = profileNames,
            levels = emptyList(),
            sizeResults = sizeResults,
            bitrateRange = "${video.bitrateRange.lower}-${video.bitrateRange.upper}",
            widthRange = "${video.supportedWidths.lower}-${video.supportedWidths.upper}",
            heightRange = "${video.supportedHeights.lower}-${video.supportedHeights.upper}",
            frameRateRange = "${video.supportedFrameRates.lower}-${video.supportedFrameRates.upper}",
            alignment = "width=${video.widthAlignment} height=${video.heightAlignment}",
        )

        Log.i(TAG, "encoder probe:\n${report.toReportText()}")
        return report
    }

    private fun profileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High422"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High444"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "ConstrainedHigh"
        else -> "profile($profile)"
    }

    private fun levelName(level: Int): String = when (level) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> "1b"
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "1.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "1.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "1.3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "2.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "3.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "4"
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "4.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "5"
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "5.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel6 -> "6"
        MediaCodecInfo.CodecProfileLevel.AVCLevel61 -> "6.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> "6.2"
        else -> "level($level)"
    }

    private fun colorFormatName(format: Int): String = when (format) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "COLOR_FormatSurface"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "COLOR_FormatYUV420Flexible"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "COLOR_FormatYUV420Planar"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "COLOR_FormatYUV420SemiPlanar"
        else -> "color($format)"
    }
}
