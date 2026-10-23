package com.dmanime.sniff

/** Monotonic-clock watchdog; one instance per source/episode, surviving URL refreshes. */
class PlaybackStallMonitor {
    enum class Action { Reconnect, RefreshAddress, SwitchSource }
    private var startedAt: Long? = null
    private var progressAt = 0L
    private var bufferedUntil = 0L
    private var attempts = 0
    var stallCount = 0
        private set
    var lastDurationMs = 0L
        private set

    fun resetWindow() { startedAt = null }

    fun sample(nowMs: Long, buffering: Boolean, bufferedPositionMs: Long): Action? {
        if (!buffering) {
            startedAt?.let { lastDurationMs = nowMs - it }
            resetWindow()
            return null
        }
        val start = startedAt ?: run {
            startedAt = nowMs
            progressAt = nowMs
            bufferedUntil = bufferedPositionMs
            stallCount++
            return null
        }
        if (bufferedPositionMs > bufferedUntil + 250) {
            bufferedUntil = bufferedPositionMs
            progressAt = nowMs
        }
        // Even a trickle of data must not keep the user waiting indefinitely.
        if (nowMs - progressAt < 20_000 && nowMs - start < 60_000) return null
        lastDurationMs = nowMs - start
        resetWindow()
        return nextAction()
    }

    /** Fatal player errors share the same bounded recovery budget as stalls. */
    fun nextAction(): Action? {
        resetWindow()
        return Action.entries.getOrNull(attempts++)
    }
}
