package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamCache

@OptIn(UnstableApi::class)
@Module
@InstallIn(SingletonComponent::class)
object OfflineCacheModule {

    const val DEFAULT_STREAM_CACHE_BYTES = 1024L * 1024 * 1024

    /**
     * Plain SharedPreferences (not DataStore) mirror of the stream-cache-size pref.
     * [provideStreamCache] below needs the value synchronously at cache construction time,
     * which a suspending DataStore read can't provide from a Hilt @Provides function, so
     * `UserPreferencesRepository.setStreamCacheLimitBytes` writes here in addition to
     * DataStore whenever the user changes the setting.
     */
    const val STREAM_CACHE_PREFS_NAME = "offline_cache_prefs"
    const val STREAM_CACHE_PREFS_KEY = "stream_cache_limit_bytes"

    @Singleton
    @Provides
    fun provideCacheDatabaseProvider(@ApplicationContext context: Context): StandaloneDatabaseProvider =
        StandaloneDatabaseProvider(context)

    @DownloadCache
    @Singleton
    @Provides
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: StandaloneDatabaseProvider
    ): SimpleCache = SimpleCache(
        File(context.filesDir, "navidrome_downloads"),
        NoOpCacheEvictor(),
        databaseProvider
    )

    @StreamCache
    @Singleton
    @Provides
    fun provideStreamCache(
        @ApplicationContext context: Context,
        databaseProvider: StandaloneDatabaseProvider
    ): SimpleCache {
        // Construction-time-only read: the LRU evictor's byte budget is fixed for the
        // cache's lifetime, so a size change made in Settings only takes effect on the
        // next app start, once this @Provides function runs again from scratch.
        val limitBytes = context.getSharedPreferences(STREAM_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(STREAM_CACHE_PREFS_KEY, DEFAULT_STREAM_CACHE_BYTES)
        return SimpleCache(
            File(context.cacheDir, "navidrome_stream_cache"),
            LeastRecentlyUsedCacheEvictor(limitBytes),
            databaseProvider
        )
    }
}
