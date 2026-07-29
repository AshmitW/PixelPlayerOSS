package com.lostf1sh.pixelplayeross.data.navidrome

enum class PlaylistSyncAction { PULL, PUSH, CREATE_ON_SERVER, RECREATE_ON_SERVER, REMOVE_LOCAL }

data class LocalPlaylistSyncState(val dirty: Boolean, val pendingCreate: Boolean)

internal fun decidePlaylistSyncAction(
    local: LocalPlaylistSyncState?,
    existsOnServer: Boolean
): PlaylistSyncAction? = when {
    local == null -> if (existsOnServer) PlaylistSyncAction.PULL else null
    local.pendingCreate -> PlaylistSyncAction.CREATE_ON_SERVER
    local.dirty -> if (existsOnServer) PlaylistSyncAction.PUSH else PlaylistSyncAction.RECREATE_ON_SERVER
    else -> if (existsOnServer) PlaylistSyncAction.PULL else PlaylistSyncAction.REMOVE_LOCAL
}

internal fun toServerSongIds(
    orderedUnifiedIds: List<String>,
    contentUriByUnifiedId: Map<String, String>
): List<String> = orderedUnifiedIds.mapNotNull { unifiedId ->
    contentUriByUnifiedId[unifiedId]
        ?.takeIf { it.startsWith("navidrome://") }
        ?.removePrefix("navidrome://")
}
