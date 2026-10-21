package com.dmanime.rules.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpisodeUrlTest {

    @Test
    fun `resolves relative paths against base url`() {
        assertEquals("https://a.example/play/1", EpisodeUrl.normalize("https://a.example/", "/play/1"))
        assertEquals("https://a.example/play/1", EpisodeUrl.normalize("https://a.example/", "play/1"))
    }

    @Test
    fun `syncs scheme for same host`() {
        assertEquals("https://a.example/x", EpisodeUrl.normalize("https://a.example/", "http://a.example/x"))
    }

    @Test
    fun `keeps other hosts untouched`() {
        assertEquals("http://b.example/x", EpisodeUrl.normalize("https://a.example/", "http://b.example/x"))
    }

    @Test
    fun `strips trailing slash but keeps root`() {
        assertEquals("https://a.example/x", EpisodeUrl.normalize("https://a.example/", "https://a.example/x/"))
        assertEquals("https://a.example/", EpisodeUrl.normalize("https://a.example/", "https://a.example/"))
    }

    @Test
    fun `drops empty query and fragment`() {
        assertEquals("https://a.example/x", EpisodeUrl.normalize("https://a.example/", "https://a.example/x?#"))
    }

    @Test
    fun `is idempotent`() {
        val once = EpisodeUrl.normalize("https://a.example/", "/play/1/?a=1")
        val twice = EpisodeUrl.normalize("https://a.example/", once)
        assertEquals(once, twice)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", EpisodeUrl.normalize("https://a.example/", "   "))
    }
}
