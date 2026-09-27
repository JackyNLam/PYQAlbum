package com.pyqcr.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory debug log shared between the background AI rating worker
 * ([AiRatingWorker]) and the UI ([com.pyqcr.ui.screen.AiRatingScreen]).
 *
 * The worker appends timestamped entries while it runs; the screen polls
 * [lines] and renders them in the debug log card, just like the foreground
 * rating flow. Kept on a worker-thread-safe list (bounded to 500 lines).
 */
object BackgroundDebugLog {

    private val lock = Any()
    private val _lines = mutableListOf<String>()

    /** Snapshot of all log lines recorded so far. */
    val lines: List<String>
        get() = synchronized(lock) { _lines.toList() }

    /** Append a timestamped line (safe to call from the WorkManager worker). */
    fun add(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg"
        synchronized(lock) {
            _lines.add(line)
            if (_lines.size > 500) _lines.removeAt(0)
        }
    }

    /** Reset the log (called when a new background session is enqueued). */
    fun clear() {
        synchronized(lock) { _lines.clear() }
    }
}