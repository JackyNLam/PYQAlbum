package com.pyqcr.ai

/**
 * In-memory progress state shared between the background AI rating worker
 * ([AiRatingWorker]) and the UI ([com.pyqcr.ui.screen.AiRatingScreen]).
 *
 * The worker updates it while it runs; the screen polls it every couple of
 * seconds (same cadence as [BackgroundDebugLog]) and renders a determinate
 * progress bar with "current/total images done" instead of an indeterminate
 * bar.
 *
 * Fields are @Volatile so the worker thread and the polling UI thread both see
 * coherent values without synchronization.
 */
object BackgroundProgress {

    const val PHASE_IDLE = "idle"
    const val PHASE_RESIZING = "resizing"
    const val PHASE_RATING = "rating"
    const val PHASE_SAVING = "saving"
    const val PHASE_DONE = "done"

    /** Current phase of the background run (one of the PHASE_* constants). */
    @Volatile var phase: String = PHASE_IDLE
    /** Number of images completed so far. */
    @Volatile var current: Int = 0
    /** Total number of images in the run. */
    @Volatile var total: Int = 0
    /** Short human-readable label for the phase, e.g. "Resizing images...". */
    @Volatile var label: String = ""

    fun update(phase: String, current: Int, total: Int, label: String) {
        this.phase = phase
        this.current = current
        this.total = total
        this.label = label
    }

    fun reset() {
        phase = PHASE_IDLE
        current = 0
        total = 0
        label = ""
    }

    val isActive: Boolean
        get() = phase != PHASE_IDLE
}