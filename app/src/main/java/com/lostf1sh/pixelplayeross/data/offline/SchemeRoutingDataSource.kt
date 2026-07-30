package com.lostf1sh.pixelplayeross.data.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal fun routesToCache(scheme: String?): Boolean = scheme == "navidrome"

@OptIn(UnstableApi::class)
class SchemeRoutingDataSource(
    private val cachedFactory: DataSource.Factory,
    private val plainFactory: DataSource.Factory
) : DataSource {

    @OptIn(UnstableApi::class)
    class Factory(
        private val cachedFactory: DataSource.Factory,
        private val plainFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(cachedFactory, plainFactory)
    }

    private var delegate: DataSource? = null
    private val pendingListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        pendingListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (routesToCache(dataSpec.uri.scheme)) {
            cachedFactory.createDataSource()
        } else {
            plainFactory.createDataSource()
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
