package com.lostf1sh.pixelplayeross.data.offline

import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity

data class PinInputs(
    val collections: List<Pair<String, String>>,
    val playlistSongs: Map<String, List<String>>,
    val albumSongs: Map<String, List<String>>,
    val favoriteIds: List<String>,
    val libraryIds: List<String>
)

data class PinDiff(
    val toAdd: List<String>,
    val toRemove: List<String>,
    val refCounts: Map<String, Int>
)

internal fun materializePins(inputs: PinInputs): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    fun contribute(ids: List<String>) {
        ids.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    inputs.collections.forEach { (type, targetId) ->
        when (type) {
            PinnedCollectionEntity.PinType.SONG -> contribute(listOf(targetId))
            PinnedCollectionEntity.PinType.PLAYLIST -> contribute(inputs.playlistSongs[targetId].orEmpty())
            PinnedCollectionEntity.PinType.ALBUM -> contribute(inputs.albumSongs[targetId].orEmpty())
            PinnedCollectionEntity.PinType.FAVORITES -> contribute(inputs.favoriteIds)
            PinnedCollectionEntity.PinType.LIBRARY -> contribute(inputs.libraryIds)
            else -> Unit
        }
    }
    // Defensive copy: callers must not be able to mutate the internal counts map.
    return counts.toMap()
}

internal fun diffPins(current: Set<String>, desired: Map<String, Int>): PinDiff = PinDiff(
    toAdd = (desired.keys - current).toList(),
    toRemove = (current - desired.keys).toList(),
    refCounts = desired
)

internal fun estimateBytes(durationMs: Long, bitRateKbps: Int?, tierCapKbps: Int?): Long {
    if (durationMs == 0L) return 0L
    val effectiveKbps = minOf(bitRateKbps ?: 320, tierCapKbps ?: Int.MAX_VALUE)
    return durationMs / 1000 * effectiveKbps * 125
}
