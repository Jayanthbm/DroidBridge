package com.droidbridge.experiment.mirroring

import com.droidbridge.core.network.SecureSession

/**
 * Hands the authenticated session from the activity to the capture service.
 *
 * ## Why this exists, and why it is prototype-scoped
 *
 * The session must outlive the activity (the user starts mirroring and then leaves the app), but it
 * cannot be parcelled through an `Intent`: a live `SSLSocket` is not a serialisable value, and
 * reconnecting in the service would mean a second code comparison the user never sees.
 *
 * A process-wide holder is the smallest thing that works for a prototype, and it is deliberately
 * *not* the production design: ADR-023 puts the session inside a `connectedDevice` foreground
 * service, which owns it for the lifetime of the connection instead of borrowing it from an
 * activity. P14 is where that gets built; this holder is the seam it will replace.
 *
 * The holder is cleared when the activity closes the session, so a stopped session cannot be
 * started again by a stale reference.
 */
object MirrorSessionHolder {

    @Volatile
    var session: SecureSession? = null

    /** Human-readable peer label, for the notification. Never a secret. */
    @Volatile
    var peerLabel: String = "Android"

    fun clear() {
        session = null
    }
}
