// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
package com.droidbridge.experiment.backgroundsession

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory evidence store for one P14 run.
 *
 * Everything here is also written to logcat under [TAG] so a scripted run can read the result without
 * scraping the screen, exactly like the other experiments. Nothing is persisted: the counters describe
 * one process lifetime, which is what the survival matrix is about.
 */
object SessionLog {

    const val TAG = "P14Session"
    private const val MAX_ENTRIES = 240

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val entries = ArrayDeque<String>()

    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var heartbeats: Int = 0
        private set

    @Volatile
    var pongs: Int = 0
        private set

    @Volatile
    var reconnects: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun setState(newState: String) {
        state = newState
        record("state=$newState")
    }

    @Synchronized
    fun record(line: String) {
        val stamp = timeFormat.format(Date())
        val entry = "$stamp $line"
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        Log.i(TAG, entry)
        ServiceNotifications.update()
    }

    @Synchronized
    fun countHeartbeat() {
        heartbeats += 1
        Log.i(TAG, "HEARTBEAT n=$heartbeats")
    }

    @Synchronized
    fun countPong() {
        pongs += 1
        Log.i(TAG, "PONG n=$pongs")
    }

    @Synchronized
    fun countReconnect() {
        reconnects += 1
        Log.i(TAG, "RECONNECT n=$reconnects")
    }

    fun fail(message: String) {
        lastError = message
        record("FAIL $message")
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    /**
     * Counters that survive into the next line of a long run.
     *
     * A survival-matrix row is judged by whether heartbeats keep arriving, so the summary line is
     * emitted at each state change rather than only at the end of an eight-hour run.
     */
    @Synchronized
    fun summary(): String =
        "heartbeats=$heartbeats pongs=$pongs reconnects=$reconnects state=$state" +
            (lastError?.let { " error=$it" } ?: "")
}
