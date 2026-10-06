package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.imageLoader
import coil.request.ImageRequest
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.NavidromeDao
import com.lostf1sh.pixelplayeross.data.database.NavidromeSongEntity
import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity
import com.lostf1sh.pixelplayeross.data.database.PinnedDownloadsDao
import com.lostf1sh.pixelplayeross.data.database.PinnedSongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.database.toSong
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.navidrome.NavidromeRepository
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.LyricsRepository
import com.lostf1sh.pixelplayeross.data.worker.NavidromeDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the offline pin registry: which collections (songs, albums, playlists,
 * favorites, the whole library) are pinned, the derived per-song download rows,
 * and the download cache those rows resolve to on disk.
 */
@OptIn(UnstableApi::class)
@Singleton
class NavidromeOfflineManager @Inject constructor(
    private val pinnedDownloadsDao: PinnedDownloadsDao,
    private val navidromeDao: NavidromeDao,
    private val musicDao: MusicDao,
    private val navidromeRepository: NavidromeRepository,
    @DownloadCache private val downloadCache: SimpleCache,
    @StreamCache private val streamCache: SimpleCache,
    private val workManager: WorkManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val lyricsRepository: LyricsRepository,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NavidromeOfflineMgr"
        const val WORK_NAME = "navidrome_downloads"
        private const val NAVIDROME_URI_PREFIX = "navidrome://"

        // Room binds SQLite parameters as a flat list for an IN (:ids) clause; SQLite's
        // default limit is ~999 bound variables per statement, so any id list wider than
        // this is split into chunks before being handed to the DAO.
        private const val ID_CHUNK_SIZE = 900

        private const val TIER_HIGH = "HIGH"
        private const val TIER_MEDIUM = "MEDIUM"
        private const val TIER_LOW = "LOW"

        /** Subsonic maxBitRate (kbps) for a download quality tier; 0 = server default / no cap. */
        internal fun maxBitRateForTier(tier: String): Int = when (tier) {
            TIER_HIGH -> 320
            TIER_MEDIUM -> 192
            TIER_LOW -> 128
            else -> 0
        }

        private val KNOWN_PIN_TYPES = setOf(
            PinnedCollectionEntity.PinType.SONG,
            PinnedCollectionEntity.PinType.ALBUM,
            PinnedCollectionEntity.PinType.PLAYLIST,
            PinnedCollectionEntity.PinType.FAVORITES,
            PinnedCollectionEntity.PinType.LIBRARY
        )
    }

    private val drainMutex = Mutex()

    // Serializes reconcile() against itself only — pin/unpin calls and post-sync reconcile
    // hooks can arrive concurrently from unrelated triggers (UI, sync workers), and without
    // this an overlapping pass could read a pre-diff pinned-songs snapshot while another
    // pass is mid-write, corrupting the add/remove diff. Deliberately not drainMutex: a
    // reconcile must be able to run (and enqueue new work) while a drain is in progress.
    private val reconcileMutex = Mutex()

    suspend fun pinCollection(type: String, targetId: String) = withContext(Dispatchers.IO) {
        pinnedDownloadsDao.upsertCollection(PinnedCollectionEntity(type = type, targetId = targetId))
        reconcile()
    }

    suspend fun unpinCollection(type: String, targetId: String) = withContext(Dispatchers.IO) {
        pinnedDownloadsDao.deleteCollection(type, targetId)
        reconcile()
    }

    suspend fun isPinned(type: String, targetId: String): Boolean =
        pinnedDownloadsDao.isCollectionPinned(type, targetId)

    fun observeCollections(): Flow<List<PinnedCollectionEntity>> = pinnedDownloadsDao.observeCollections()

    /**
     * Recomputes the pinned-songs registry from the currently pinned collections and
     * applies the add/remove diff. A `pinned_collections` row whose type falls outside
     * [KNOWN_PIN_TYPES] must never be allowed to drive a deletion — it may be data this
     * build doesn't understand yet — so the removal phase is skipped whenever one is
     * present; additions still go through normally.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        reconcileMutex.withLock {
            val collections = pinnedDownloadsDao.getCollectionsOnce()
            val hasUnknownType = collections.any { it.type !in KNOWN_PIN_TYPES }
            if (hasUnknownType) {
                Timber.tag(TAG).w("Unknown pinned_collections type present; skipping removal phase this reconcile")
            }

            val desired = materializePins(gatherPinInputs(collections))
            val currentIds = pinnedDownloadsDao.getPinnedSongsOnce().map { it.navidromeId }.toSet()
            val diff = diffPins(currentIds, desired)
            val tier = userPreferencesRepository.downloadQualityTierFlow.first()

            if (diff.toAdd.isNotEmpty()) {
                val newRows = diff.toAdd.map { id ->
                    PinnedSongEntity(
                        navidromeId = id,
                        qualityTier = tier,
                        refCount = desired[id] ?: 1,
                        completedAt = null,
                        sizeBytes = null
                    )
                }
                pinnedDownloadsDao.upsertPinnedSongs(newRows)
                newRows.forEach { streamCache.removeResource(NavidromeCacheKeys.cacheKeyFor(it.navidromeId)) }
            }

            (desired.keys intersect currentIds).forEach { id ->
                pinnedDownloadsDao.updateRefCount(id, desired[id] ?: 1)
            }

            if (!hasUnknownType && diff.toRemove.isNotEmpty()) {
                // Purge before deleting registry rows: death mid-purge must leave "row still present, no bytes" (healable), not "row gone, stale bytes" (resume-splice corruption).
                diff.toRemove.forEach { downloadCache.removeResource(NavidromeCacheKeys.cacheKeyFor(it)) }
                deletePinnedSongsChunked(diff.toRemove)
            }

            // Self-heal: a row left with a stale qualityTier (process death between
            // changeQualityTier's DB reset and its pref write) would otherwise never be
            // corrected, since nothing else re-derives qualityTier from the pref.
            val mismatchedTierRows = pinnedDownloadsDao.getPinnedSongsOnce().filter { it.qualityTier != tier }
            if (mismatchedTierRows.isNotEmpty()) {
                pinnedDownloadsDao.resetMismatchedQualityTier(tier)
                mismatchedTierRows.forEach { downloadCache.removeResource(NavidromeCacheKeys.cacheKeyFor(it.navidromeId)) }
            }

            // Self-heal: a COMPLETED row whose cached bytes are gone (process death mid-purge
            // in changeQualityTier, an older build's toRemove ordering, or cache eviction)
            // would otherwise be treated as a finished download with zero bytes and never
            // redownloaded.
            val staleCompletedRows = pinnedDownloadsDao.getPinnedSongsOnce().filter { row ->
                row.completedAt != null &&
                    downloadCache.getCachedBytes(NavidromeCacheKeys.cacheKeyFor(row.navidromeId), 0, -1) <= 0
            }
            if (staleCompletedRows.isNotEmpty()) {
                resetCompletedRowsChunked(staleCompletedRows.map { it.navidromeId })
            }

            if (pinnedDownloadsDao.getIncompleteOnce().isNotEmpty()) {
                scheduleDownloads()
            }
        }
    }

    private suspend fun gatherPinInputs(collections: List<PinnedCollectionEntity>): PinInputs {
        val playlistIds = collections
            .filter { it.type == PinnedCollectionEntity.PinType.PLAYLIST }
            .map { it.targetId }
            .distinct()
        val albumIds = collections
            .filter { it.type == PinnedCollectionEntity.PinType.ALBUM }
            .map { it.targetId }
            .distinct()

        val playlistSongs = playlistIds.associateWith { id ->
            navidromeDao.getSongsByPlaylistOnce(id).map { it.navidromeId }
        }
        val albumSongs = albumIds.associateWith { id ->
            navidromeDao.getSongsByAlbumIdOnce(id).map { it.navidromeId }
        }
        val favoriteIds = musicDao.getFavoriteContentUrisBySource(SourceType.NAVIDROME)
            .map { it.removePrefix(NAVIDROME_URI_PREFIX) }
        val libraryIds = navidromeDao.getLibraryNavidromeIds()

        return PinInputs(
            collections = collections.map { it.type to it.targetId },
            playlistSongs = playlistSongs,
            albumSongs = albumSongs,
            favoriteIds = favoriteIds,
            libraryIds = libraryIds
        )
    }

    /**
     * Wi-Fi constraint is read from the pref right now, at schedule time. Toggling the
     * setting afterwards only changes the constraint on the *next* call to this
     * function — it does not retroactively touch a request already enqueued.
     */
    suspend fun scheduleDownloads() {
        val wifiOnly = userPreferencesRepository.downloadWifiOnlyFlow.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<NavidromeDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a drain already in flight re-queries the incomplete list on its own
        // loop and will pick up newly pinned rows, so replacing it would only cancel
        // useful in-progress work for no benefit.
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Whether any pinned song still needs its bytes downloaded, for the app-start scheduling gate. */
    suspend fun hasIncompleteDownloads(): Boolean = pinnedDownloadsDao.countIncompleteOnce() > 0

    /**
     * Drains the incomplete-downloads queue: resolves a fresh stream URL per song,
     * writes it into the download cache, then best-effort prefetches lyrics and
     * artwork. Mutex-serialized like [com.lostf1sh.pixelplayeross.data.navidrome.NavidromePlaylistSyncManager]'s
     * drain so overlapping worker runs cannot race on the same cache writes.
     *
     * Loops until a full pass makes zero progress (all remaining rows failed) or the
     * incomplete list is empty — this also lets newly pinned songs join an in-flight
     * drain since the list is re-queried at the top of every pass.
     */
    suspend fun drainDownloads(
        onProgress: suspend (done: Int, total: Int, currentTitle: String?) -> Unit
    ): Boolean = drainMutex.withLock {
        if (!navidromeRepository.isLoggedIn) return@withLock true

        val cacheDataSource = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setCacheKeyFactory(NavidromeCacheKeys.CACHE_KEY_FACTORY)
            .createDataSourceForDownloading()

        var madeProgressThisPass = true
        while (madeProgressThisPass) {
            val incomplete = pinnedDownloadsDao.getIncompleteOnce()
            if (incomplete.isEmpty()) return@withLock true

            madeProgressThisPass = false
            val total = incomplete.size
            incomplete.forEachIndexed { index, row ->
                val song = resolveUnifiedSong(row.navidromeId)
                onProgress(index, total, song?.title)
                try {
                    downloadOne(cacheDataSource, row.navidromeId, row.qualityTier)
                    madeProgressThisPass = true
                    if (song != null) prefetchLyricsAndArtwork(song)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Download failed for ${row.navidromeId}, will retry")
                }
                onProgress(index + 1, total, song?.title)
            }
        }

        pinnedDownloadsDao.getIncompleteOnce().isEmpty()
    }

    private suspend fun downloadOne(cacheDataSource: CacheDataSource, navidromeId: String, qualityTier: String) {
        // qualityTier is the tier snapshotted from the row *before* this fetch started; it
        // travels through to markCompleted below so a concurrent changeQualityTier reset
        // can't have this write silently mark the row completed for the wrong tier's bytes.
        val url = navidromeRepository.getStreamUrl(navidromeId, maxBitRateForTier(qualityTier))
        val cacheKey = NavidromeCacheKeys.cacheKeyFor(navidromeId)
        val dataSpec = DataSpec.Builder()
            .setUri(url)
            .setKey(cacheKey)
            .build()
        // CacheWriter.cache() blocks on network I/O; keep it off the worker's default dispatcher.
        withContext(Dispatchers.IO) {
            CacheWriter(cacheDataSource, dataSpec, null, null).cache()
        }

        val currentRow = pinnedDownloadsDao.getPinnedSongOnce(navidromeId)
        if (currentRow == null || currentRow.qualityTier != qualityTier) {
            // Unpinned or quality-changed mid-download: the bytes we just wrote belong to a
            // registry state that no longer exists, so they'd otherwise sit as orphans.
            Timber.tag(TAG).d("Dropping stale download for $navidromeId (tier changed or unpinned mid-download)")
            downloadCache.removeResource(cacheKey)
            return
        }

        val bytesCached = downloadCache.getCachedBytes(cacheKey, 0, -1)
        val updatedRows = pinnedDownloadsDao.markCompleted(navidromeId, qualityTier, System.currentTimeMillis(), bytesCached)
        if (updatedRows > 0) {
            // The stream cache may hold stale original-tier bytes under this same cache key
            // from earlier playback; purge them so a completed, possibly-transcoded download
            // can never be shadowed by them (SchemeRoutingDataSource routes completed ids to
            // the download cache exclusively, but this keeps the stream cache from holding
            // dead weight for a song that no longer needs auto-caching).
            streamCache.removeResource(cacheKey)
        }
    }

    private suspend fun resolveUnifiedSong(navidromeId: String): Song? {
        val unifiedId = musicDao.getSongIdByContentUri("$NAVIDROME_URI_PREFIX$navidromeId") ?: return null
        return musicDao.getSongByIdOnce(unifiedId)?.toSong()
    }

    private suspend fun prefetchLyricsAndArtwork(song: Song) {
        try {
            val sourcePreference = userPreferencesRepository.lyricsSourcePreferenceFlow.first()
            lyricsRepository.getLyrics(song, sourcePreference, forceRefresh = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).d("Lyrics prefetch failed for ${song.navidromeId}: ${e.message}")
        }
        try {
            song.albumArtUriString?.let { uri ->
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(uri).build())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).d("Artwork prefetch failed for ${song.navidromeId}: ${e.message}")
        }
    }

    /**
     * Cancels the download work first so WorkManager stops scheduling further drain passes,
     * then takes [drainMutex] around the cache purge, tier reset, and pref write.
     *
     * Cancellation does NOT abort an in-flight [CacheWriter]: [CacheWriter.cache] is
     * blocking I/O with no cancellation checkpoint, so if a download is mid-write when this
     * runs, acquiring [drainMutex] here blocks until that write returns — i.e. until the
     * current song finishes downloading (or fails) — before the reset can proceed. That
     * wait is the entire point of taking the mutex: it guarantees no writer for the old
     * tier can land bytes after the purge below runs. The tier-conditional `markCompleted`
     * (see [PinnedDownloadsDao.markCompleted]) self-heals the one remaining edge case: a
     * write already past its last tier check when cancellation was requested.
     */
    suspend fun changeQualityTier(newTier: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME)
        drainMutex.withLock {
            val pinned = pinnedDownloadsDao.getPinnedSongsOnce()
            // Purge before resetting rows: death mid-purge must leave "completed row, no bytes" (healable), not "incomplete row, stale bytes" (resume-splice corruption).
            pinned.forEach { downloadCache.removeResource(NavidromeCacheKeys.cacheKeyFor(it.navidromeId)) }
            pinnedDownloadsDao.resetAllForQuality(newTier)
            userPreferencesRepository.setDownloadQualityTier(newTier)
        }
        scheduleDownloads()
    }

    suspend fun removeAllDownloads() = withContext(Dispatchers.IO) {
        pinnedDownloadsDao.clearAll()
        removeAllNavResources(downloadCache)
    }

    /**
     * Pinned-song count and a total size estimate at [newTier], for the requality
     * confirmation dialog in Settings. Uses each pinned song's *current* bitrate/duration
     * from the Navidrome cache, not the tier it's currently downloaded at.
     */
    suspend fun estimateRequalifyBytes(newTier: String): Pair<Int, Long> {
        val pinned = pinnedDownloadsDao.getPinnedSongsOnce()
        if (pinned.isEmpty()) return 0 to 0L
        val tierCap = maxBitRateForTier(newTier).takeIf { it > 0 }
        val songs = getSongsByNavidromeIdsChunked(pinned.map { it.navidromeId })
        val bytes = songs.sumOf { estimateBytes(it.duration, it.bitRate, tierCap) }
        return pinned.size to bytes
    }

    /**
     * Size estimate for pinning a not-yet-pinned collection (playlist/album/Liked) at the
     * currently selected quality tier, for the pre-pin confirmation dialog. Mirrors
     * [estimateLibraryBytes] but takes the caller's own member-id list rather than the
     * whole library.
     */
    suspend fun estimateCollectionBytes(memberNavidromeIds: List<String>): Long {
        if (memberNavidromeIds.isEmpty()) return 0L
        val tier = userPreferencesRepository.downloadQualityTierFlow.first()
        val tierCap = maxBitRateForTier(tier).takeIf { it > 0 }
        val songs = getSongsByNavidromeIdsChunked(memberNavidromeIds.distinct())
        return songs.sumOf { estimateBytes(it.duration, it.bitRate, tierCap) }
    }

    /** Size estimate for pinning the whole library at the currently selected quality tier. */
    suspend fun estimateLibraryBytes(): Long {
        val libraryIds = navidromeDao.getLibraryNavidromeIds()
        if (libraryIds.isEmpty()) return 0L
        val tier = userPreferencesRepository.downloadQualityTierFlow.first()
        val tierCap = maxBitRateForTier(tier).takeIf { it > 0 }
        val songs = getSongsByNavidromeIdsChunked(libraryIds)
        return songs.sumOf { estimateBytes(it.duration, it.bitRate, tierCap) }
    }

    suspend fun clearStreamCache() = withContext(Dispatchers.IO) {
        removeAllNavResources(streamCache)
    }

    fun downloadCacheBytes(): Long = downloadCache.cacheSpace

    fun streamCacheBytes(): Long = streamCache.cacheSpace

    suspend fun onLogout() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME)
        // Mirrors changeQualityTier: takes drainMutex around the registry clear and cache
        // purges so a drain pass that is mid-write when logout fires cannot land a write
        // after the purge runs and resurrect a "downloaded" resource for a session that no
        // longer has credentials.
        drainMutex.withLock {
            pinnedDownloadsDao.clearAll()
            removeAllNavResources(downloadCache)
            removeAllNavResources(streamCache)
        }
    }

    private fun removeAllNavResources(cache: SimpleCache) {
        cache.keys.filter { it.startsWith(NavidromeCacheKeys.KEY_PREFIX) }.forEach { key ->
            cache.removeResource(key)
        }
    }

    /** Chunks an IN (:ids) lookup below SQLite's bound-variable limit; see [ID_CHUNK_SIZE]. */
    private suspend fun getSongsByNavidromeIdsChunked(ids: List<String>): List<NavidromeSongEntity> =
        ids.chunked(ID_CHUNK_SIZE).flatMap { navidromeDao.getSongsByNavidromeIds(it) }

    /** Chunks an IN (:ids) delete below SQLite's bound-variable limit; see [ID_CHUNK_SIZE]. */
    private suspend fun deletePinnedSongsChunked(ids: List<String>) {
        ids.chunked(ID_CHUNK_SIZE).forEach { pinnedDownloadsDao.deletePinnedSongs(it) }
    }

    /** Chunks an IN (:ids) reset below SQLite's bound-variable limit; see [ID_CHUNK_SIZE]. */
    private suspend fun resetCompletedRowsChunked(ids: List<String>) {
        ids.chunked(ID_CHUNK_SIZE).forEach { pinnedDownloadsDao.resetCompletedForIds(it) }
    }
}
