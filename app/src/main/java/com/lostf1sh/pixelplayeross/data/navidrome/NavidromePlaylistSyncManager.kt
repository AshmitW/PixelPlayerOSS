package com.lostf1sh.pixelplayeross.data.navidrome

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lostf1sh.pixelplayeross.data.database.LocalPlaylistDao
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.NavidromeDao
import com.lostf1sh.pixelplayeross.data.database.PlaylistEntity
import com.lostf1sh.pixelplayeross.data.network.navidrome.NavidromeApiService
import com.lostf1sh.pixelplayeross.data.worker.NavidromePlaylistPushWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Outbox for local playlist changes on Navidrome-backed playlists: pushes
 * pending deletes, creates, and content/name edits to the server.
 */
@Singleton
class NavidromePlaylistSyncManager @Inject constructor(
    private val api: NavidromeApiService,
    private val navidromeDao: NavidromeDao,
    private val localPlaylistDao: LocalPlaylistDao,
    private val musicDao: MusicDao,
    private val workManager: WorkManager
) {
    companion object {
        private const val TAG = "NavidromePlSync"
        const val PUSH_WORK_NAME = "navidrome_playlist_push"
        const val PENDING_CREATE_PREFIX = "navidrome_pending:"
        const val SERVER_PREFIX = "navidrome_playlist:"
        const val MAX_DELETE_ATTEMPTS = 8
        private const val SONG_ID_CHUNK_SIZE = 900
    }

    suspend fun markDirtyAndSchedule(playlistId: String) {
        localPlaylistDao.setNavidromeSyncFlags(playlistId, dirty = true, pendingCreate = false)
        schedulePush()
    }

    fun schedulePush() {
        val request = OneTimeWorkRequestBuilder<NavidromePlaylistPushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(PUSH_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Order: deletes, creates, dirty pushes. Returns true when everything pending was delivered. */
    suspend fun drainPendingPlaylistOps(): Boolean {
        if (!api.hasCredentials()) return true
        var drained = true

        navidromeDao.getPendingPlaylistDeletesOnce().forEach { op ->
            val result = api.deletePlaylist(op.serverId)
            val notFound = result.exceptionOrNull()?.message?.contains("API Error 70") == true
            when {
                result.isSuccess || notFound -> navidromeDao.deletePendingPlaylistDelete(op.serverId)
                op.attempts + 1 >= MAX_DELETE_ATTEMPTS -> {
                    Timber.tag(TAG).w("Dropping playlist delete for ${op.serverId} after $MAX_DELETE_ATTEMPTS attempts")
                    navidromeDao.deletePendingPlaylistDelete(op.serverId)
                }
                else -> {
                    rethrowIfCancellation(result.exceptionOrNull())
                    navidromeDao.incrementPendingPlaylistDeleteAttempts(op.serverId)
                    drained = false
                }
            }
        }

        localPlaylistDao.getDirtyNavidromePlaylistsOnce().forEach { entity ->
            val delivered = if (entity.navidromePendingCreate) pushCreate(entity) else pushDirty(entity)
            if (!delivered) drained = false
        }
        return drained
    }

    private suspend fun pushCreate(entity: PlaylistEntity): Boolean {
        val songIds = serverSongIdsFor(entity.id)
        val result = api.createPlaylist(entity.name, songIds)
        return result.fold(
            onSuccess = { serverId ->
                localPlaylistDao.rekeyPlaylist(entity.id, "$SERVER_PREFIX$serverId")
                localPlaylistDao.setNavidromeSyncFlags("$SERVER_PREFIX$serverId", dirty = false, pendingCreate = false)
                true
            },
            onFailure = { error ->
                rethrowIfCancellation(error)
                Timber.tag(TAG).w("Playlist create push failed for ${entity.name}: ${error.message}")
                false
            }
        )
    }

    private suspend fun pushDirty(entity: PlaylistEntity): Boolean {
        val serverId = entity.id.removePrefix(SERVER_PREFIX)
        if (serverId == entity.id) return true
        val songIds = serverSongIdsFor(entity.id)
        val replace = api.replacePlaylistSongs(serverId, songIds)
        if (replace.isFailure) {
            rethrowIfCancellation(replace.exceptionOrNull())
            Timber.tag(TAG).w("Playlist push failed for ${entity.name}: ${replace.exceptionOrNull()?.message}")
            return false
        }
        val cachedName = navidromeDao.getPlaylistById(serverId)?.name
        if (cachedName != null && cachedName != entity.name) {
            val rename = api.renamePlaylist(serverId, entity.name)
            if (rename.isFailure) {
                rethrowIfCancellation(rename.exceptionOrNull())
                return false
            }
        }
        localPlaylistDao.setNavidromeSyncFlags(entity.id, dirty = false, pendingCreate = false)
        return true
    }

    private suspend fun serverSongIdsFor(playlistId: String): List<String> {
        val ordered = localPlaylistDao.getPlaylistSongsOnce(playlistId).map { it.songId }
        val longIds = ordered.mapNotNull { it.toLongOrNull() }
        val uriById = longIds.chunked(SONG_ID_CHUNK_SIZE)
            .flatMap { musicDao.getSongIdUris(it) }
            .associate { it.id.toString() to it.contentUriString }
        return toServerSongIds(ordered, uriById)
    }

    private fun rethrowIfCancellation(error: Throwable?) {
        if (error is CancellationException) throw error
    }
}
