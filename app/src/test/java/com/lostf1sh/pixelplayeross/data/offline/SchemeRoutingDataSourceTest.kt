package com.lostf1sh.pixelplayeross.data.offline

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SchemeRoutingDataSourceTest {

    @Test
    fun `navidrome scheme routes to cache`() {
        assertThat(routesToCache("navidrome")).isTrue()
    }

    @Test
    fun `jellyfin and http schemes bypass cache`() {
        assertThat(routesToCache("jellyfin")).isFalse()
        assertThat(routesToCache("http")).isFalse()
        assertThat(routesToCache("content")).isFalse()
    }

    @Test
    fun `null scheme bypasses cache`() {
        assertThat(routesToCache(null)).isFalse()
    }
}
