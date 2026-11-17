package com.droidbridge.experiment.mirroring

/**
 * Enforces strictly increasing, non-negative encoder presentation timestamps within one format
 * epoch. A new encoder/format calls [reset], since Android may restart its PTS clock after rotation.
 */
internal class MonotonicTimestampGate {
    private var lastPTSUs: Long? = null

    @Synchronized
    fun reset() {
        lastPTSUs = null
    }

    @Synchronized
    fun admit(ptsUs: Long): Boolean {
        if (ptsUs < 0 || (lastPTSUs != null && ptsUs <= lastPTSUs!!)) return false
        lastPTSUs = ptsUs
        return true
    }
}
