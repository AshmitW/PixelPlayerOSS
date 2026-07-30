package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * Three-state offline-download control for a whole collection (playlist, album, favorites,
 * library). Mirrors the per-song download toggle in [com.lostf1sh.pixelplayeross.presentation.components.SongInfoBottomSheet]
 * but works off aggregate progress instead of a single completion flag.
 */
sealed interface DownloadPinState {
    data object NotPinned : DownloadPinState
    data class InProgress(val completed: Int, val total: Int) : DownloadPinState
    data object Complete : DownloadPinState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadPinButton(
    state: DownloadPinState,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    when (state) {
        DownloadPinState.NotPinned -> {
            FilledTonalIconButton(
                onClick = onPin,
                modifier = modifier.size(size)
            ) {
                Icon(imageVector = Icons.Rounded.Download, contentDescription = contentDescription)
            }
        }

        is DownloadPinState.InProgress -> {
            // Tapping while a collection is mid-download is a no-op — cancellation isn't
            // wired up for collection-level pins, only the pin/unpin toggle is. Left enabled
            // (rather than enabled = false) so the progress indicator isn't dimmed by the
            // button's disabled-state alpha.
            // contentDescription is state-derived here (not the caller-provided static string)
            // since a screen reader needs the live completed/total count, not just "download".
            val progressDescription = stringResource(R.string.cd_download_progress, state.completed, state.total)
            FilledTonalIconButton(
                onClick = {},
                modifier = modifier
                    .size(size)
                    .semantics { this.contentDescription = progressDescription }
            ) {
                val indicatorSize = size * 0.5f
                if (state.total > 0) {
                    CircularWavyProgressIndicator(
                        progress = { state.completed / state.total.toFloat() },
                        modifier = Modifier.size(indicatorSize)
                    )
                } else {
                    CircularWavyProgressIndicator(modifier = Modifier.size(indicatorSize))
                }
            }
        }

        DownloadPinState.Complete -> {
            FilledTonalIconButton(
                onClick = onUnpin,
                modifier = modifier.size(size),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(imageVector = Icons.Rounded.DownloadDone, contentDescription = contentDescription)
            }
        }
    }
}
