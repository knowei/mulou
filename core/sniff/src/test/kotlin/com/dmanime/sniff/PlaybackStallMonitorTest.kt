package com.dmanime.sniff

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaybackStallMonitorTest {
    @Test fun `stalled data reconnects then refreshes then switches only once`() {
        val monitor = PlaybackStallMonitor()
        for ((index, action) in PlaybackStallMonitor.Action.entries.withIndex()) {
            val start = index * 21_000L
            assertNull(monitor.sample(start, true, 1000))
            assertNull(monitor.sample(start + 19_999, true, 1000))
            assertEquals(action, monitor.sample(start + 20_000, true, 1000))
        }
        assertNull(monitor.nextAction())
        assertEquals(3, monitor.stallCount)
    }
    @Test fun `loading progress extends grace period but not beyond one minute`() {
        val monitor = PlaybackStallMonitor()
        for (i in 0..5) assertNull(monitor.sample(i * 10_000L, true, i * 1000L))
        assertEquals(PlaybackStallMonitor.Action.Reconnect, monitor.sample(60_000, true, 6000))
    }
    @Test fun `pause background and seek reset timer without resetting budget`() {
        val monitor = PlaybackStallMonitor()
        monitor.sample(0, true, 0)
        monitor.resetWindow()
        assertNull(monitor.sample(100_000, true, 0))
        assertEquals(PlaybackStallMonitor.Action.Reconnect, monitor.sample(120_000, true, 0))
        monitor.resetWindow()
        assertEquals(PlaybackStallMonitor.Action.RefreshAddress, monitor.nextAction())
    }
    @Test fun `normal playback records recovery and cancels timeout`() {
        val monitor = PlaybackStallMonitor()
        monitor.sample(0, true, 0)
        assertNull(monitor.sample(5000, false, 30_000))
        assertEquals(5000L, monitor.lastDurationMs)
        assertNull(monitor.sample(90_000, false, 30_000))
        assertNull(monitor.sample(100_000, true, 30_000))
    }
    @Test fun `fatal errors use same retry budget as buffering`() {
        val monitor = PlaybackStallMonitor()
        assertEquals(PlaybackStallMonitor.Action.Reconnect, monitor.nextAction())
        monitor.sample(0, true, 0)
        assertEquals(PlaybackStallMonitor.Action.RefreshAddress, monitor.sample(20_000, true, 0))
        assertEquals(PlaybackStallMonitor.Action.SwitchSource, monitor.nextAction())
        assertNull(monitor.nextAction())
    }
}
