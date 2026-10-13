package com.pyqcr.ai

/**
 * In-memory progress state shared between the background AI edit worker
 * ([AiEditWorker]) and the UI ([com.pyqcr.ui.screen.AiEditScreen]).
 *
 * Separate from [BackgroundProgress] (AI rating) so a rating job and an
 * edit job running at the same time never clobber each other's state.
 *
 * The worker updates it while it runs; the screen polls it every couple of
 * seconds and renders a determinate progress bar.
 *
 * Fields are @Volatile so the worker thread and the polling UI thread both
 * see coherent values without synchronization.
 */
object AiEditBackgroundProgress {

    const val PHASE_IDLE = "idle"
    const val PHASE_EDITING = "editing"
    const val PHASE_DONE = "done"

    @Volatile var phase: String = PHASE_IDLE
    @Volatile var current: Int = 0
    @Volatile var total: Int = 0
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