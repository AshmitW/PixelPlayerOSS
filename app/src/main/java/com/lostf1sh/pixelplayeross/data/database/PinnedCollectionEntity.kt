package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-pinned collection (song, album, playlist, the Liked list, or the whole library)
 * marked for offline download. Composite PK: a collection is identified by its type + target id.
 */
@Entity(tableName = "pinned_collections", primaryKeys = ["type", "targetId"])
data class PinnedCollectionEntity(
    val type: String,
    val targetId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    object PinType {
        const val SONG = "SONG"
        const val ALBUM = "ALBUM"
        const val PLAYLIST = "PLAYLIST"
        const val FAVORITES = "FAVORITES"
        const val LIBRARY = "LIBRARY"
    }
}

/**
 * Per-song download state for the offline pin registry, keyed by Navidrome song id.
 * `refCount` tracks how many pinned collections resolve to this song, so a song stays
 * downloaded as long as at least one collection still references it. `completedAt` is null
 * until the download worker finishes writing the file.
 */
@Entity(tableName = "pinned_songs")
data class PinnedSongEntity(
    @PrimaryKey val navidromeId: String,
    val qualityTier: String,
    val refCount: Int,
    val completedAt: Long?,
    val sizeBytes: Long?
)
