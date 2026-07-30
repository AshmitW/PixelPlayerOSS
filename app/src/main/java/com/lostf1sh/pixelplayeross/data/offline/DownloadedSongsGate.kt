package com.lostf1sh.pixelplayeross.data.offline

import com.lostf1sh.pixelplayeross.data.database.PinnedDownloadsDao
import com.lostf1sh.pixelplayeross.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Live membership test for "this Navidrome song has a fully completed download."
 *
 * Playback must only read cached bytes from the download cache for ids in this set — see
 * [SchemeRoutingDataSource]. A song downloaded at a tier other than ORIGINAL that is only
 * partially written would otherwise have [androidx.media3.datasource.cache.CacheDataSource]
 * splice its cached (transcoded) spans with upstream — the streaming proxy, which serves
 * the original file — producing corrupted audio.
 */
@Singleton
class DownloadedSongsGate @Inject constructor(
    pinnedDownloadsDao: PinnedDownloadsDao,
    @AppScope scope: CoroutineScope
) {
    @Volatile
    private var completed: Set<String> = emptySet()

    init {
        scope.launch {
            pinnedDownloadsDao.observeCompletedSongIds().collect { ids ->
                completed = ids.toSet()
            }
        }
    }

    fun isDownloaded(navidromeId: String): Boolean = navidromeId in completed
}
