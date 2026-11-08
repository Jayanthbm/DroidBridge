package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class H264ParameterSetsTest {

    @Test
    fun `parses start coded codec specific data into bare NAL units`() {
        val parameterSets = H264ParameterSets.fromCodecSpecificData(Vectors.CSD_0, Vectors.CSD_1)

        assertBytesEqual(Vectors.SPS, parameterSets.sps, "SPS must lose its start code")
        assertBytesEqual(Vectors.PPS, parameterSets.pps, "PPS must lose its start code")
        assertEquals(2, parameterSets.parameterSetCount)
    }

    @Test
    fun `rejects a codec specific buffer holding more than one NAL unit`() {
        val concatenated = hex("00 00 00 01") + Vectors.SPS + hex("00 00 00 01") + Vectors.PPS

        val failure = assertFailsWith<MalformedStreamException> {
            H264ParameterSets.fromCodecSpecificData(concatenated, Vectors.CSD_1)
        }

        assertTrue(failure.message!!.contains("exactly one NAL unit"), failure.message)
    }

    @Test
    fun `rejects a buffer whose NAL unit is the wrong type`() {
        val failure = assertFailsWith<MalformedStreamException> {
            H264ParameterSets.fromCodecSpecificData(Vectors.CSD_0, Vectors.CSD_0)
        }

        assertTrue(failure.message!!.contains("SPS"), failure.message)
        assertTrue(failure.message!!.contains("csd-1"), failure.message)
    }

    @Test
    fun `rejects bare NAL units that still contain a start code`() {
        val failure = assertFailsWith<MalformedStreamException> {
            H264ParameterSets.fromBareNalUnits(Vectors.CSD_0, Vectors.CSD_1)
        }

        assertTrue(failure.message!!.contains("start code"), failure.message)
    }

    @Test
    fun `rejects swapped bare NAL units`() {
        assertFailsWith<MalformedStreamException> {
            H264ParameterSets.fromBareNalUnits(Vectors.PPS, Vectors.SPS)
        }
    }

    @Test
    fun `rejects an empty parameter set`() {
        assertFailsWith<MalformedStreamException> {
            H264ParameterSets.fromBareNalUnits(ByteArray(0), Vectors.PPS)
        }
    }
}
