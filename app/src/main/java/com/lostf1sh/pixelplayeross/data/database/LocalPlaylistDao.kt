package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalPlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY last_modified DESC")
    fun observePlaylistsWithSongs(): Flow<List<PlaylistWithSongsEntity>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun observePlaylistWithSongs(playlistId: String): Flow<PlaylistWithSongsEntity?>

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    fun observePlaylistSongs(playlistId: String): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(entity: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistSongs(entities: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: String)

    @Query("DELETE FROM playlist_songs")
    suspend fun clearAllPlaylistSongs()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    @Transaction
    suspend fun replacePlaylistSongs(playlistId: String, songIds: List<String>) {
        clearPlaylistSongs(playlistId)
        if (songIds.isEmpty()) return
        val rows = songIds.mapIndexed { index, songId ->
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                sortOrder = index
            )
        }
        upsertPlaylistSongs(rows)
    }

    @Transaction
    suspend fun replaceAllPlaylistsTransactional(playlists: List<Pair<PlaylistEntity, List<String>>>) {
        clearAllPlaylistSongs()
        clearAllPlaylists()
        playlists.forEach { (entity, songIds) ->
            upsertPlaylist(entity)
            replacePlaylistSongs(entity.id, songIds)
        }
    }

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    suspend fun getPlaylistSongsOnce(playlistId: String): List<PlaylistSongEntity>

    @Query("UPDATE playlists SET navidrome_dirty = :dirty, navidrome_pending_create = :pendingCreate WHERE id = :playlistId")
    suspend fun setNavidromeSyncFlags(playlistId: String, dirty: Boolean, pendingCreate: Boolean)

    @Query("SELECT * FROM playlists WHERE source = 'NAVIDROME' AND (navidrome_dirty = 1 OR navidrome_pending_create = 1)")
    suspend fun getDirtyNavidromePlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE navidrome_pending_create = 1")
    suspend fun getPendingCreatePlaylistsOnce(): List<PlaylistEntity>

    @Query("UPDATE playlists SET id = :newId WHERE id = :oldId")
    suspend fun updatePlaylistId(oldId: String, newId: String)

    @Query("UPDATE playlist_songs SET playlist_id = :newId WHERE playlist_id = :oldId")
    suspend fun updatePlaylistSongsPlaylistId(oldId: String, newId: String)

    @Transaction
    suspend fun rekeyPlaylist(oldId: String, newId: String) {
        updatePlaylistId(oldId, newId)
        updatePlaylistSongsPlaylistId(oldId, newId)
    }
}
