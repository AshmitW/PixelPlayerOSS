package com.lostf1sh.pixelplayeross.data.navidrome

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NavidromePlaylistReconciliationTest {

    @Test
    fun `clean local with server copy pulls`() {
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = false, pendingCreate = false), existsOnServer = true))
            .isEqualTo(PlaylistSyncAction.PULL)
    }

    @Test
    fun `dirty local with server copy pushes`() {
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = true, pendingCreate = false), existsOnServer = true))
            .isEqualTo(PlaylistSyncAction.PUSH)
    }

    @Test
    fun `dirty local deleted on server recreates`() {
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = true, pendingCreate = false), existsOnServer = false))
            .isEqualTo(PlaylistSyncAction.RECREATE_ON_SERVER)
    }

    @Test
    fun `clean local deleted on server removes local mirror`() {
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = false, pendingCreate = false), existsOnServer = false))
            .isEqualTo(PlaylistSyncAction.REMOVE_LOCAL)
    }

    @Test
    fun `pending create wins regardless of server state`() {
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = false, pendingCreate = true), existsOnServer = false))
            .isEqualTo(PlaylistSyncAction.CREATE_ON_SERVER)
        assertThat(decidePlaylistSyncAction(LocalPlaylistSyncState(dirty = true, pendingCreate = true), existsOnServer = true))
            .isEqualTo(PlaylistSyncAction.CREATE_ON_SERVER)
    }

    @Test
    fun `server playlist with no local mirror pulls`() {
        assertThat(decidePlaylistSyncAction(local = null, existsOnServer = true))
            .isEqualTo(PlaylistSyncAction.PULL)
    }

    @Test
    fun `no local and no server yields null`() {
        assertThat(decidePlaylistSyncAction(local = null, existsOnServer = false)).isNull()
    }

    @Test
    fun `server song ids strip prefix and preserve order`() {
        val uris = mapOf(
            "-9000000000001" to "navidrome://abc",
            "-9000000000002" to "navidrome://def"
        )
        assertThat(toServerSongIds(listOf("-9000000000002", "-9000000000001"), uris))
            .containsExactly("def", "abc").inOrder()
    }

    @Test
    fun `non navidrome songs are skipped preserving order`() {
        val uris = mapOf(
            "-9000000000001" to "navidrome://abc",
            "42" to "content://media/external/audio/media/42",
            "-9000000000002" to "navidrome://def"
        )
        assertThat(toServerSongIds(listOf("-9000000000001", "42", "-9000000000002"), uris))
            .containsExactly("abc", "def").inOrder()
    }

    @Test
    fun `unknown ids are skipped`() {
        assertThat(toServerSongIds(listOf("-9000000000001"), emptyMap())).isEmpty()
    }
}
