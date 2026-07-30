package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the offline pin registry: which collections are pinned and the
 * per-song download state that pinning resolves to.
 */
@Dao
interface PinnedDownloadsDao {

    // ─── Pinned collections ──────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: PinnedCollectionEntity)

    @Query("DELETE FROM pinned_collections WHERE type = :type AND targetId = :targetId")
    suspend fun deleteCollection(type: String, targetId: String)

    @Query("SELECT * FROM pinned_collections")
    suspend fun getCollectionsOnce(): List<PinnedCollectionEntity>

    @Query("SELECT * FROM pinned_collections")
    fun observeCollections(): Flow<List<PinnedCollectionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM pinned_collections WHERE type = :type AND targetId = :targetId)")
    suspend fun isCollectionPinned(type: String, targetId: String): Boolean

    // ─── Pinned songs ─────────────────────────────────────────────────

    @Query("DELETE FROM pinned_songs")
    suspend fun clearPinnedSongs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPinnedSongs(songs: List<PinnedSongEntity>)

    /**
     * Atomically replaces the pinned-songs set — clear+insert as two separate calls
     * leaves the registry empty if the process dies in between.
     */
    @Transaction
    suspend fun replacePinnedSongs(songs: List<PinnedSongEntity>) {
        clearPinnedSongs()
        upsertPinnedSongs(songs)
    }

    @Query("DELETE FROM pinned_songs WHERE navidromeId IN (:ids)")
    suspend fun deletePinnedSongs(ids: List<String>)

    @Query("SELECT * FROM pinned_songs")
    suspend fun getPinnedSongsOnce(): List<PinnedSongEntity>

    @Query("SELECT navidromeId FROM pinned_songs WHERE completedAt IS NOT NULL")
    fun observeCompletedSongIds(): Flow<List<String>>

    @Query("SELECT navidromeId FROM pinned_songs")
    fun observePinnedSongIds(): Flow<List<String>>

    @Query("SELECT * FROM pinned_songs WHERE completedAt IS NULL")
    suspend fun getIncompleteOnce(): List<PinnedSongEntity>

    @Query("UPDATE pinned_songs SET completedAt = :completedAt, sizeBytes = :sizeBytes WHERE navidromeId = :navidromeId")
    suspend fun markCompleted(navidromeId: String, completedAt: Long, sizeBytes: Long)

    @Query("UPDATE pinned_songs SET qualityTier = :newTier, completedAt = NULL, sizeBytes = NULL")
    suspend fun resetAllForQuality(newTier: String)

    @Query("SELECT SUM(sizeBytes) FROM pinned_songs")
    fun totalPinnedBytes(): Flow<Long?>

    @Query("DELETE FROM pinned_collections")
    suspend fun clearAllCollections()

    /**
     * Wipes both tables of the pin registry — used when the user disables offline
     * downloads entirely.
     */
    @Transaction
    suspend fun clearAll() {
        clearAllCollections()
        clearPinnedSongs()
    }
}
