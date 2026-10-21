package com.dmanime.sniff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SniffSessionTest {

    @Test
    fun `accepts bridge messages and ignores noise`() {
        val session = SniffSession()

        session.accept("""{"url":"https://a.example/seg1.ts","from":"xhr"}""")
        session.accept("""{"url":"https://a.example/play.m3u8","from":"fetch"}""")

        assertEquals(1, session.candidates.size)
        assertEquals("https://a.example/play.m3u8", session.candidates[0].url)
        assertEquals("fetch", session.candidates[0].from)
        assertTrue(session.shouldStop())
    }

    @Test
    fun `malformed messages are ignored instead of crashing`() {
        val session = SniffSession()
        session.accept("not json")
        session.accept("""{"from":"xhr"}""")
        session.accept("""{"url":""}""")
        assertTrue(session.candidates.isEmpty())
    }

    @Test
    fun `mse only pages are detectable`() {
        val session = SniffSession()
        session.accept("""{"url":"mse:video/mp4; codecs=avc1","from":"mse"}""")

        assertTrue(session.candidates.isEmpty())
        assertTrue(session.hasMseOnly())
        assertFalse(session.shouldStop())
    }

    @Test
    fun `reset clears everything`() {
        val session = SniffSession()
        session.accept("""{"url":"https://a.example/play.m3u8"}""")
        session.reset()
        assertTrue(session.candidates.isEmpty())
    }
}
