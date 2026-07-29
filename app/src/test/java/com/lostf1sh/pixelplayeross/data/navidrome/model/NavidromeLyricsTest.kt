package com.lostf1sh.pixelplayeross.data.navidrome.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NavidromeLyricsTest {

    private fun syncedEntry(vararg lines: Pair<Long?, String>, offsetMs: Long = 0L) =
        NavidromeLyricsEntry(
            synced = true,
            offsetMs = offsetMs,
            lines = lines.map { NavidromeLyricsLine(startMs = it.first, value = it.second) }
        )

    @Test
    fun `synced entry renders lrc lines with zero padded timestamps`() {
        val entry = syncedEntry(0L to "first line", 61_500L to "second line")

        val text = navidromeLyricsToText(entry)

        assertThat(text).isEqualTo("[00:00.00]first line\n[01:01.50]second line")
    }

    @Test
    fun `offset shifts line times`() {
        val entry = syncedEntry(1_000L to "line", offsetMs = 2_000L)

        assertThat(navidromeLyricsToText(entry)).isEqualTo("[00:03.00]line")
    }

    @Test
    fun `negative resulting time clamps to zero`() {
        val entry = syncedEntry(1_000L to "line", offsetMs = -5_000L)

        assertThat(navidromeLyricsToText(entry)).isEqualTo("[00:00.00]line")
    }

    @Test
    fun `synced entry skips lines without timestamps`() {
        val entry = syncedEntry(0L to "timed", null to "untimed", 2_000L to "timed two")

        assertThat(navidromeLyricsToText(entry))
            .isEqualTo("[00:00.00]timed\n[00:02.00]timed two")
    }

    @Test
    fun `synced entry with no timestamps at all falls back to plain text`() {
        val entry = syncedEntry(null to "one", null to "two")

        assertThat(navidromeLyricsToText(entry)).isEqualTo("one\ntwo")
    }

    @Test
    fun `unsynced entry joins values as plain text`() {
        val entry = NavidromeLyricsEntry(
            synced = false,
            offsetMs = 0L,
            lines = listOf(
                NavidromeLyricsLine(null, "hello"),
                NavidromeLyricsLine(null, "world")
            )
        )

        assertThat(navidromeLyricsToText(entry)).isEqualTo("hello\nworld")
    }

    @Test
    fun `minutes over an hour still format`() {
        val entry = syncedEntry(3_723_450L to "late line")

        assertThat(navidromeLyricsToText(entry)).isEqualTo("[62:03.45]late line")
    }

    @Test
    fun `pick best prefers synced entry with lines`() {
        val unsynced = NavidromeLyricsEntry(false, 0L, listOf(NavidromeLyricsLine(null, "plain")))
        val synced = syncedEntry(0L to "timed")

        assertThat(pickBestNavidromeLyrics(listOf(unsynced, synced))).isEqualTo(synced)
    }

    @Test
    fun `pick best falls back to first entry with lines`() {
        val empty = NavidromeLyricsEntry(true, 0L, emptyList())
        val unsynced = NavidromeLyricsEntry(false, 0L, listOf(NavidromeLyricsLine(null, "plain")))

        assertThat(pickBestNavidromeLyrics(listOf(empty, unsynced))).isEqualTo(unsynced)
    }

    @Test
    fun `pick best returns null when nothing has lines`() {
        val empty = NavidromeLyricsEntry(true, 0L, emptyList())

        assertThat(pickBestNavidromeLyrics(listOf(empty))).isNull()
        assertThat(pickBestNavidromeLyrics(emptyList())).isNull()
    }
}
