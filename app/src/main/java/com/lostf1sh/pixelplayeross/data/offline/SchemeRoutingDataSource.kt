package com.lostf1sh.pixelplayeross.data.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal enum class SchemeRoute { DOWNLOAD, STREAM, PLAIN }

/**
 * Pure routing decision: a completed download must be read only from the download cache
 * (it may be transcoded at a tier the streaming proxy does not serve), a non-downloaded
 * navidrome song still goes through the write-through stream cache for auto-caching, and
 * everything else bypasses both caches.
 */
internal fun routeFor(scheme: String?, isDownloaded: Boolean): SchemeRoute = when {
    scheme != "navidrome" -> SchemeRoute.PLAIN
    isDownloaded -> SchemeRoute.DOWNLOAD
    else -> SchemeRoute.STREAM
}

@OptIn(UnstableApi::class)
class SchemeRoutingDataSource(
    private val cachedFactory: DataSource.Factory,
    private val streamFactory: DataSource.Factory,
    private val plainFactory: DataSource.Factory,
    private val routeToDownloadCache: (String) -> Boolean
) : DataSource {

    @OptIn(UnstableApi::class)
    class Factory(
        private val cachedFactory: DataSource.Factory,
        private val streamFactory: DataSource.Factory,
        private val plainFactory: DataSource.Factory,
        private val routeToDownloadCache: (String) -> Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(cachedFactory, streamFactory, plainFactory, routeToDownloadCache)
    }

    private var delegate: DataSource? = null
    private val pendingListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        pendingListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host
        val isDownloaded = host != null && routeToDownloadCache(host)
        val source = when (routeFor(dataSpec.uri.scheme, isDownloaded)) {
            SchemeRoute.DOWNLOAD -> cachedFactory.createDataSource()
            SchemeRoute.STREAM -> streamFactory.createDataSource()
            SchemeRoute.PLAIN -> plainFactory.createDataSource()
        }
        pendingListeners.forEach { source.addTransferListener(it) }
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "DataSource not opened" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
