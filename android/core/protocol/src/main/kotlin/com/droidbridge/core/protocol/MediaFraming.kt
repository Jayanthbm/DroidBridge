package com.droidbridge.core.protocol

import com.droidbridge.protocol.media.AccessUnit
import com.droidbridge.protocol.media.AnnexB
import com.droidbridge.protocol.media.AnnexBToAvcc

/**
 * The Android side's entry point into the shared wire format.
 *
 * `shared/protocol` is consumed as a composite build, not copied. This object exists so the
 * Android build proves that linkage on every compile: if the substitution breaks, this file stops
 * compiling and the failure is loud instead of surfacing as a duplicated framing rule later.
 *
 * Phase 0 has no transport, so nothing here sends anything. It is the seam the mirroring feature
 * uses in Phase 4 (ADR-027).
 */
object MediaFraming {

    /**
     * Splits one MediaCodec Annex-B output buffer into NAL units, start codes removed.
     *
     * Thin by design: the rules (both start-code lengths, trailing-zero padding, rejection of
     * empty or unframed input) live in the shared module and are tested there.
     */
    fun splitNalUnits(buffer: ByteArray): List<ByteArray> = AnnexB.splitNalUnits(buffer)

    /**
     * Converts one Annex-B access unit to the length-prefixed AVCC form CoreMedia requires,
     * reporting whether it carries an IDR slice.
     */
    fun toAvcc(annexB: ByteArray, ptsUs: Long, dropParameterSets: Boolean = false): AccessUnit =
        AnnexBToAvcc.convertAccessUnit(annexB, ptsUs, dropParameterSets)
}
