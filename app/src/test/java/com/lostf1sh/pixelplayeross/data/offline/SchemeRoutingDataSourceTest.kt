package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SchemeRoutingDataSourceTest {

    @Test
    fun `completed navidrome download routes to download cache`() {
        assertThat(routeFor("navidrome", isDownloaded = true)).isEqualTo(SchemeRoute.DOWNLOAD)
    }

    @Test
    fun `non-downloaded navidrome song routes to stream cache`() {
        assertThat(routeFor("navidrome", isDownloaded = false)).isEqualTo(SchemeRoute.STREAM)
    }

    @Test
    fun `jellyfin and http schemes bypass both caches regardless of downloaded flag`() {
        assertThat(routeFor("jellyfin", isDownloaded = true)).isEqualTo(SchemeRoute.PLAIN)
        assertThat(routeFor("jellyfin", isDownloaded = false)).isEqualTo(SchemeRoute.PLAIN)
        assertThat(routeFor("http", isDownloaded = false)).isEqualTo(SchemeRoute.PLAIN)
        assertThat(routeFor("content", isDownloaded = false)).isEqualTo(SchemeRoute.PLAIN)
    }

    @Test
    fun `null scheme bypasses both caches regardless of downloaded flag`() {
        assertThat(routeFor(null, isDownloaded = true)).isEqualTo(SchemeRoute.PLAIN)
        assertThat(routeFor(null, isDownloaded = false)).isEqualTo(SchemeRoute.PLAIN)
    }
}
