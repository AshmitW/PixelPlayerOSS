package com.lostf1sh.pixelplayeross.data.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheKeyFactory

@OptIn(UnstableApi::class)
object NavidromeCacheKeys {
    const val KEY_PREFIX = "nav:"

    fun cacheKeyFor(navidromeId: String): String = "$KEY_PREFIX$navidromeId"

    fun cacheKeyForUri(uri: Uri): String? =
        if (uri.scheme == "navidrome") uri.host?.let { cacheKeyFor(it) } else null

    val CACHE_KEY_FACTORY: CacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.key ?: cacheKeyForUri(dataSpec.uri) ?: dataSpec.uri.toString()
    }
}
