package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity
import org.junit.jupiter.api.Test

class PinReconciliationTest {

    private fun inputs(
        collections: List<Pair<String, String>> = emptyList(),
        playlistSongs: Map<String, List<String>> = emptyMap(),
        albumSongs: Map<String, List<String>> = emptyMap(),
        favoriteIds: List<String> = emptyList(),
        libraryIds: List<String> = emptyList()
    ) = PinInputs(collections, playlistSongs, albumSongs, favoriteIds, libraryIds)

    @Test
    fun `song pin contributes its own id`() {
        val result = materializePins(inputs(collections = listOf(PinnedCollectionEntity.PinType.SONG to "a")))
        assertThat(result).containsExactly("a", 1)
    }

    @Test
    fun `playlist pin contributes member ids`() {
        val result = materializePins(
            inputs(
                collections = listOf(PinnedCollectionEntity.PinType.PLAYLIST to "p1"),
                playlistSongs = mapOf("p1" to listOf("a", "b"))
            )
        )
        assertThat(result).containsExactly("a", 1, "b", 1)
    }

    @Test
    fun `album pin contributes member ids`() {
        val result = materializePins(
            inputs(
                collections = listOf(PinnedCollectionEntity.PinType.ALBUM to "al1"),
                albumSongs = mapOf("al1" to listOf("x"))
            )
        )
        assertThat(result).containsExactly("x", 1)
    }

    @Test
    fun `favorites pin contributes all favorite ids`() {
        val result = materializePins(
            inputs(collections = listOf(PinnedCollectionEntity.PinType.FAVORITES to ""), favoriteIds = listOf("a", "b"))
        )
        assertThat(result).containsExactly("a", 1, "b", 1)
    }

    @Test
    fun `library pin contributes all library ids`() {
        val result = materializePins(
            inputs(collections = listOf(PinnedCollectionEntity.PinType.LIBRARY to ""), libraryIds = listOf("a", "b", "c"))
        )
        assertThat(result).containsExactly("a", 1, "b", 1, "c", 1)
    }

    @Test
    fun `song in favorites and a pinned playlist has refcount two`() {
        val result = materializePins(
            inputs(
                collections = listOf(PinnedCollectionEntity.PinType.FAVORITES to "", PinnedCollectionEntity.PinType.PLAYLIST to "p1"),
                playlistSongs = mapOf("p1" to listOf("a")),
                favoriteIds = listOf("a")
            )
        )
        assertThat(result).containsExactly("a", 2)
    }

    @Test
    fun `duplicate id inside one collection counts once`() {
        val result = materializePins(
            inputs(
                collections = listOf(PinnedCollectionEntity.PinType.PLAYLIST to "p1"),
                playlistSongs = mapOf("p1" to listOf("a", "a"))
            )
        )
        assertThat(result).containsExactly("a", 1)
    }

    @Test
    fun `unknown playlist contributes nothing`() {
        val result = materializePins(inputs(collections = listOf(PinnedCollectionEntity.PinType.PLAYLIST to "ghost")))
        assertThat(result).isEmpty()
    }

    @Test
    fun `diff computes additions and removals`() {
        val diff = diffPins(current = setOf("a", "b"), desired = mapOf("b" to 1, "c" to 2))
        assertThat(diff.toAdd).containsExactly("c")
        assertThat(diff.toRemove).containsExactly("a")
    }

    @Test
    fun `diff carries refcounts through`() {
        val diff = diffPins(current = emptySet(), desired = mapOf("a" to 3))
        assertThat(diff.refCounts).containsExactly("a", 3)
    }

    @Test
    fun `estimate uses song bitrate when below cap`() {
        assertThat(estimateBytes(durationMs = 60_000, bitRateKbps = 128, tierCapKbps = 320))
            .isEqualTo(60L * 128 * 125)
    }

    @Test
    fun `estimate caps at tier`() {
        assertThat(estimateBytes(durationMs = 60_000, bitRateKbps = 1411, tierCapKbps = 320))
            .isEqualTo(60L * 320 * 125)
    }

    @Test
    fun `estimate defaults unknown bitrate to 320`() {
        assertThat(estimateBytes(durationMs = 60_000, bitRateKbps = null, tierCapKbps = null))
            .isEqualTo(60L * 320 * 125)
    }

    @Test
    fun `estimate of zero duration is zero`() {
        assertThat(estimateBytes(durationMs = 0, bitRateKbps = 320, tierCapKbps = null)).isEqualTo(0)
    }
}
