package com.droidbridge.core.network

/**
 * Decides when a reconnect attempt is worth making.
 *
 * A retry budget only means something if the attempts can reach the network. Measured 2026-11-26 on
 * the S24 Ultra: with the display off the socket died, the phone then spent all ten attempts before
 * ever reaching the LAN, and the listener logged no incoming connection at all while it sat listening
 * for six minutes. The budget burned out in roughly 150 s and the user came back to a terminal
 * "gave up" state for a Mac that had never left.
 *
 * So time spent unable to reach the network is not charged to the budget. The implementation the app
 * supplies decides what "usable" means; the controller only requires that [awaitUsable] returns once
 * an attempt has a chance, and suspends otherwise.
 *
 * It must be cancellation-aware: the controller cancels it when the user stops the session, and a
 * gate that ignores cancellation would leave a cancelled worker suspended forever.
 */
fun interface RetryGate {
    /** Returns immediately when a retry has a chance; suspends until it does. */
    suspend fun awaitUsable()
}
