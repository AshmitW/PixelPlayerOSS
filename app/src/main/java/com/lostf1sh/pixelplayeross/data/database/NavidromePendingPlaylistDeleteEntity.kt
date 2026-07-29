package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "navidrome_pending_playlist_deletes")
data class NavidromePendingPlaylistDeleteEntity(
    @PrimaryKey val serverId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0
)
