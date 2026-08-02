package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.NavidromeDao
import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity
import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity.PinType
import com.lostf1sh.pixelplayeross.data.database.PinnedDownloadsDao
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.offline.NavidromeOfflineManager
import com.lostf1sh.pixelplayeross.data.worker.NavidromeDownloadWorker
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.DownloadPinState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val NAVIDROME_URI_PREFIX = "navidrome://"

/** A pinned collection with a resolved display name where that's cheap to look up. */
data class PinnedCollectionSummary(
    val type: String,
    val targetId: String,
    /** Playlist/album title from the Navidrome cache; null for types resolved to a fixed label in the UI, or if the lookup missed. */
    val resolvedName: String?
)

/** Snapshot of the offline-download queue for the Downloads & Storage screen. */
data class DownloadQueueState(
    val isActive: Boolean,
    val completed: Int,
    val total: Int,
    val currentDone: Int,
    val currentTotal: Int
)

/**
 * Backs the collection-level [DownloadPinState] shown by `DownloadPinButton` on the playlist,
 * album, and Liked-tab surfaces. Deliberately thin: progress is always derived reactively from
 * [PinnedDownloadsDao.observeCompletedSongIds] against a collection's member id list rather than
 * observed from WorkManager, since the pinned-songs table already tracks completion per song
 * regardless of which worker run wrote it.
 */
@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val pinnedDownloadsDao: PinnedDownloadsDao,
    private val navidromeOfflineManager: NavidromeOfflineManager,
    private val navidromeDao: NavidromeDao,
    private val musicDao: MusicDao,
    private val favoritesDao: FavoritesDao,
    private val workManager: WorkManager
) : ViewModel() {

    private val pinnedCollections: Flow<Set<Pair<String, String>>> = pinnedDownloadsDao.observeCollections()
        .map { collections -> collections.map { it.type to it.targetId }.toSet() }

    private val completedNavidromeIds: Flow<Set<String>> = pinnedDownloadsDao.observeCompletedSongIds()
        .map { it.toSet() }

    /** Songs downloaded so far, for the dashboard card summary. */
    val downloadedSongCount: StateFlow<Int> = completedNavidromeIds
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _storageRefreshTrigger = MutableStateFlow(0)

    /** Re-reads [NavidromeOfflineManager.downloadCacheBytes]/[NavidromeOfflineManager.streamCacheBytes]. */
    fun refreshStorageStats() {
        _storageRefreshTrigger.update { it + 1 }
    }

    /** Downloaded bytes: the registry's own SUM when populated, else the cache's on-disk size. */
    val downloadedCacheBytes: StateFlow<Long> = combine(
        pinnedDownloadsDao.totalPinnedBytes(),
        _storageRefreshTrigger
    ) { totalPinnedBytes, _ -> totalPinnedBytes ?: navidromeOfflineManager.downloadCacheBytes() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val streamCacheBytes: StateFlow<Long> = _storageRefreshTrigger
        .map { navidromeOfflineManager.streamCacheBytes() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Pinned collections with a best-effort resolved display name (see [PinnedCollectionSummary]). */
    val pinnedCollectionSummaries: StateFlow<ImmutableList<PinnedCollectionSummary>> =
        pinnedDownloadsDao.observeCollections()
            .map { collections -> collections.map { resolveCollectionSummary(it) }.toImmutableList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())

    /** X of N pinned songs completed, plus the active drain pass's own progress if one is running. */
    val downloadQueueState: StateFlow<DownloadQueueState> = combine(
        pinnedDownloadsDao.observePinnedSongIds(),
        completedNavidromeIds,
        workManager.getWorkInfosForUniqueWorkFlow(NavidromeOfflineManager.WORK_NAME)
    ) { pinnedIds, completed, workInfos ->
        val activeWork = workInfos.firstOrNull { !it.state.isFinished }
        DownloadQueueState(
            isActive = activeWork != null,
            completed = completed.size,
            total = pinnedIds.size,
            currentDone = activeWork?.progress?.getInt(NavidromeDownloadWorker.KEY_DONE, 0) ?: 0,
            currentTotal = activeWork?.progress?.getInt(NavidromeDownloadWorker.KEY_TOTAL, 0) ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadQueueState(false, 0, 0, 0, 0))

    private suspend fun resolveCollectionSummary(entity: PinnedCollectionEntity): PinnedCollectionSummary {
        val resolvedName = when (entity.type) {
            PinType.PLAYLIST -> navidromeDao.getPlaylistById(entity.targetId)?.name
            PinType.ALBUM -> navidromeDao.getSongsByAlbumIdOnce(entity.targetId).firstOrNull()?.album
            else -> null
        }
        return PinnedCollectionSummary(entity.type, entity.targetId, resolvedName)
    }

    fun unpinCollection(type: String, targetId: String) {
        viewModelScope.launch { navidromeOfflineManager.unpinCollection(type, targetId) }
    }

    /** Size estimate at the current quality tier, for the pre-pin confirmation dialog. */
    suspend fun estimateCollectionBytes(memberNavidromeIds: List<String>): Long =
        navidromeOfflineManager.estimateCollectionBytes(memberNavidromeIds)

    /** One-shot snapshot of favorited Navidrome song ids, for the Liked-tab pre-pin estimate. */
    suspend fun currentFavoriteNavidromeIds(): List<String> = favoriteNavidromeIds().first()

    fun removeAllDownloads() {
        viewModelScope.launch {
            navidromeOfflineManager.removeAllDownloads()
            refreshStorageStats()
        }
    }

    fun forPlaylist(navidromePlaylistId: String, memberIds: Flow<List<String>>): Flow<DownloadPinState> =
        collectionState(PinType.PLAYLIST, navidromePlaylistId, memberIds)

    fun pinPlaylist(navidromePlaylistId: String) = pin(PinType.PLAYLIST, navidromePlaylistId)

    fun unpinPlaylist(navidromePlaylistId: String) = unpin(PinType.PLAYLIST, navidromePlaylistId)

    /**
     * Album pin/unpin/state all resolve the *raw server* album id from the album's own songs
     * rather than taking it as a parameter — see the report for why: [PinnedDownloadsDao]'s
     * pinned_collections targetId for ALBUM is later fed straight into
     * `NavidromeDao.getSongsByAlbumIdOnce`, which filters `navidrome_songs.album_id` (the raw
     * Subsonic album id string). The unified negative Long album id used everywhere else in the
     * UI does not match that column, so it must not be used as the targetId here.
     */
    fun forAlbum(songs: Flow<List<Song>>): Flow<DownloadPinState> {
        val memberIds = songs.map { list -> list.mapNotNull(Song::navidromeId) }.distinctUntilChanged()
        val rawAlbumId = memberIds.map { resolveAlbumNavidromeId(it) }.distinctUntilChanged()
        return combine(rawAlbumId, memberIds, pinnedCollections, completedNavidromeIds) { albumId, ids, pins, completed ->
            toState(albumId != null && (PinType.ALBUM to albumId) in pins, ids, completed)
        }
    }

    fun pinAlbum(songs: List<Song>) {
        viewModelScope.launch {
            val rawAlbumId = resolveAlbumNavidromeId(songs.mapNotNull(Song::navidromeId)) ?: return@launch
            navidromeOfflineManager.pinCollection(PinType.ALBUM, rawAlbumId)
        }
    }

    fun unpinAlbum(songs: List<Song>) {
        viewModelScope.launch {
            val rawAlbumId = resolveAlbumNavidromeId(songs.mapNotNull(Song::navidromeId)) ?: return@launch
            navidromeOfflineManager.unpinCollection(PinType.ALBUM, rawAlbumId)
        }
    }

    /** FAVORITES ignores targetId, and derives its own membership — no caller-supplied flow needed. */
    fun forFavorites(): Flow<DownloadPinState> =
        combine(favoriteNavidromeIds(), pinnedCollections, completedNavidromeIds) { members, pins, completed ->
            toState((PinType.FAVORITES to "") in pins, members, completed)
        }

    fun pinFavorites() = pin(PinType.FAVORITES, "")

    fun unpinFavorites() = unpin(PinType.FAVORITES, "")

    private fun collectionState(type: String, targetId: String, memberIds: Flow<List<String>>): Flow<DownloadPinState> =
        combine(pinnedCollections, completedNavidromeIds, memberIds) { pins, completed, members ->
            toState((type to targetId) in pins, members, completed)
        }

    private fun toState(isPinned: Boolean, members: List<String>, completed: Set<String>): DownloadPinState {
        if (!isPinned) return DownloadPinState.NotPinned
        val total = members.size
        val done = members.count { it in completed }
        return if (total == 0 || done >= total) DownloadPinState.Complete else DownloadPinState.InProgress(done, total)
    }

    private fun pin(type: String, targetId: String) {
        viewModelScope.launch { navidromeOfflineManager.pinCollection(type, targetId) }
    }

    private fun unpin(type: String, targetId: String) {
        viewModelScope.launch { navidromeOfflineManager.unpinCollection(type, targetId) }
    }

    private suspend fun resolveAlbumNavidromeId(memberSongIds: List<String>): String? {
        for (navidromeId in memberSongIds) {
            navidromeDao.getSongByNavidromeId(navidromeId)?.albumId?.let { return it }
        }
        return null
    }

    /**
     * Any favorite toggle (local, Navidrome, or Jellyfin) re-triggers this, then re-derives the
     * Navidrome-only favorited id set — mirrors the source-filtered lookup
     * [NavidromeOfflineManager.gatherPinInputs] already does for the FAVORITES pin type.
     */
    private fun favoriteNavidromeIds(): Flow<List<String>> =
        favoritesDao.getFavoriteSongIds().map {
            musicDao.getFavoriteContentUrisBySource(SourceType.NAVIDROME)
                .filter { uri -> uri.startsWith(NAVIDROME_URI_PREFIX) }
                .map { uri -> uri.removePrefix(NAVIDROME_URI_PREFIX) }
        }
}
