package com.droidbridge.protocol.media

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MirrorFormatTest {

    private val tinyParameterSets = H264ParameterSets.fromBareNalUnits(hex("67 42"), hex("68"))

    @Test
    fun `encodes the documented payload with deterministic key order`() {
        val format = MirrorFormat(
            width = 1280,
            height = 720,
            rotation = 0,
            parameterSets = tinyParameterSets,
        )

        assertEquals(
            """{"width":1280,"height":720,"rotation":0,"sps":"Z0I=","pps":"aA==","nalLengthSize":4}""",
            format.toJsonPayload(),
        )
    }

    @Test
    fun `encodes a rotated format`() {
        val format = MirrorFormat(width = 720, height = 1280, rotation = 90, parameterSets = tinyParameterSets)

        assertTrue(format.toJsonPayload().contains("\"rotation\":90"), format.toJsonPayload())
    }

    @Test
    fun `base64 decodes back to the parameter sets`() {
        val format = MirrorFormat(1920, 1080, 0, H264ParameterSets.fromCodecSpecificData(Vectors.CSD_0, Vectors.CSD_1))

        val json = format.toJsonPayload()
        val encodedSps = json.substringAfter("\"sps\":\"").substringBefore('"')

        assertBytesEqual(Vectors.SPS, Base64.getDecoder().decode(encodedSps), "SPS round trip")
    }

    @Test
    fun `encodes codec specific data the way the producer must`() {
        // The whole producer flow for one frame: csd-0/csd-1 -> format message -> access unit.
        val parameterSets = H264ParameterSets.fromCodecSpecificData(Vectors.CSD_0, Vectors.CSD_1)
        val format = MirrorFormat(1280, 720, 0, parameterSets)
        val accessUnit = AnnexBToAvcc.convertAccessUnit(Vectors.KEYFRAME_ACCESS_UNIT, ptsUs = 33_333)

        assertTrue(format.toJsonPayload().startsWith("""{"width":1280,"height":720,"rotation":0,"sps":"""))
        assertTrue(accessUnit.isKeyframe, "the first frame after mirror.format must be an IDR")
        assertEquals(33_333L, accessUnit.ptsUs)
    }

    @Test
    fun `rejects an unsupported rotation`() {
        assertFailsWith<IllegalArgumentException> {
            MirrorFormat(1280, 720, 45, tinyParameterSets)
        }
    }

    @Test
    fun `rejects a NAL length size CoreMedia does not accept`() {
        assertFailsWith<IllegalArgumentException> {
            MirrorFormat(1280, 720, 0, tinyParameterSets, nalLengthSize = 3)
        }
    }

    @Test
    fun `rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            MirrorFormat(0, 720, 0, tinyParameterSets)
        }
        assertFailsWith<IllegalArgumentException> {
            MirrorFormat(1280, -1, 0, tinyParameterSets)
        }
    }
}
