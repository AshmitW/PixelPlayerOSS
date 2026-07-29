package com.lostf1sh.pixelplayeross.data.navidrome.model

import java.util.Locale

data class NavidromeLyricsLine(val startMs: Long?, val value: String)

data class NavidromeLyricsEntry(
    val synced: Boolean,
    val offsetMs: Long,
    val lines: List<NavidromeLyricsLine>
)

internal fun pickBestNavidromeLyrics(entries: List<NavidromeLyricsEntry>): NavidromeLyricsEntry? {
    val withLines = entries.filter { it.lines.isNotEmpty() }
    return withLines.firstOrNull { it.synced } ?: withLines.firstOrNull()
}

internal fun navidromeLyricsToText(entry: NavidromeLyricsEntry): String {
    if (entry.synced) {
        val timed = entry.lines.filter { it.startMs != null }
        if (timed.isNotEmpty()) {
            return timed.joinToString("\n") { line ->
                val ms = (line.startMs!! + entry.offsetMs).coerceAtLeast(0L)
                val minutes = ms / 60_000
                val seconds = (ms % 60_000) / 1_000
                val centis = (ms % 1_000) / 10
                "[%02d:%02d.%02d]%s".format(Locale.ROOT, minutes, seconds, centis, line.value)
            }
        }
    }
    return entry.lines.joinToString("\n") { it.value }
}
