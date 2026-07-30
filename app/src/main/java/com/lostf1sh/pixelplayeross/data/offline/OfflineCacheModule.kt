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
    ): SimpleCache = SimpleCache(
        File(context.cacheDir, "navidrome_stream_cache"),
        LeastRecentlyUsedCacheEvictor(DEFAULT_STREAM_CACHE_BYTES),
        databaseProvider
    )
}
