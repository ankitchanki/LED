package com.glowled.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory event log shown on the home screen, so users can report what happened. */
object DebugLog {
    // Enough for a few complete screen-off / wake sequences.
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    // Milliseconds: the screen-off / wake sequence plays out within a second.
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${fmt.format(Date())}  $msg")
        android.util.Log.d("GlowLED", msg)
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "No events yet." else lines.reversed().joinToString("\n")
}
