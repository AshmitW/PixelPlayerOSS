package com.lostf1sh.pixelplayeross.presentation.navidrome.dashboard

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.database.PinnedCollectionEntity.PinType
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DownloadQueueState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.OfflineViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PinnedCollectionSummary
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Standalone offline-storage management screen: storage split (downloads vs. stream cache),
 * live download-queue progress, the list of pinned collections with per-collection unpin, and
 * a remove-all-downloads action. Reads/writes go through [OfflineViewModel] (Task 8), extended
 * with the state this screen needs rather than introducing a second ViewModel for one screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsStorageScreen(
    viewModel: OfflineViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val downloadedBytes by viewModel.downloadedCacheBytes.collectAsStateWithLifecycle()
    val streamCacheBytes by viewModel.streamCacheBytes.collectAsStateWithLifecycle()
    val queueState by viewModel.downloadQueueState.collectAsStateWithLifecycle()
    val pinnedCollections by viewModel.pinnedCollectionSummaries.collectAsStateWithLifecycle()

    // The dashboard-card idiom's collectAsStateWithLifecycle instances only refresh from
    // their own flows; the two byte-count reads inside NavidromeOfflineManager are plain
    // synchronous cache lookups with no Flow backing them, so re-trigger them explicitly
    // whenever this screen becomes visible.
    LaunchedEffect(Unit) {
        viewModel.refreshStorageStats()
    }

    var pendingUnpin by remember { mutableStateOf<PinnedCollectionSummary?>(null) }
    var showRemoveAllConfirm by remember { mutableStateOf(false) }

    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 20.dp, cornerRadiusTL = 20.dp,
        cornerRadiusBR = 20.dp, cornerRadiusBL = 20.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.downloads_storage_title),
                        fontFamily = RoundedSans,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "storage_split") {
                StorageSplitCard(
                    downloadedBytes = downloadedBytes,
                    streamCacheBytes = streamCacheBytes,
                    cardShape = cardShape
                )
            }

            item(key = "queue") {
                QueueCard(queueState = queueState, cardShape = cardShape)
            }

            item(key = "collections_header") {
                Text(
                    text = stringResource(R.string.downloads_collections_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = RoundedSans,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            if (pinnedCollections.isEmpty()) {
                item(key = "collections_empty") {
                    Text(
                        text = stringResource(R.string.downloads_collections_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = RoundedSans,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(
                    items = pinnedCollections,
                    key = { "${it.type}_${it.targetId}" }
                ) { summary ->
                    PinnedCollectionRow(
                        summary = summary,
                        onUnpinClick = { pendingUnpin = summary },
                        cardShape = cardShape
                    )
                }
            }

            item(key = "remove_all") {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { showRemoveAllConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.download_remove_all), fontFamily = RoundedSans)
                }
            }
        }
    }

    pendingUnpin?.let { summary ->
        AlertDialog(
            onDismissRequest = { pendingUnpin = null },
            title = { Text(stringResource(R.string.download_remove_confirm_title)) },
            text = { Text(stringResource(R.string.download_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unpinCollection(summary.type, summary.targetId)
                    pendingUnpin = null
                }) { Text(stringResource(R.string.delete_action), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpin = null }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showRemoveAllConfirm) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showRemoveAllConfirm = false },
            title = { Text(stringResource(R.string.download_remove_all_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.download_remove_all_confirm_body,
                        Formatter.formatShortFileSize(context, downloadedBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeAllDownloads()
                    showRemoveAllConfirm = false
                }) { Text(stringResource(R.string.delete_action), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllConfirm = false }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }
}

@Composable
private fun StorageSplitCard(
    downloadedBytes: Long,
    streamCacheBytes: Long,
    cardShape: AbsoluteSmoothCornerShape
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            StorageMetric(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.downloads_storage_downloads_label),
                value = Formatter.formatShortFileSize(context, downloadedBytes),
                modifier = Modifier.weight(1f)
            )
            StorageMetric(
                icon = Icons.Rounded.Storage,
                label = stringResource(R.string.downloads_storage_cache_label),
                value = Formatter.formatShortFileSize(context, streamCacheBytes),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StorageMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = RoundedSans,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = RoundedSans,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QueueCard(
    queueState: DownloadQueueState,
    cardShape: AbsoluteSmoothCornerShape
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.downloads_queue_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = RoundedSans,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (queueState.total > 0) {
                    stringResource(R.string.downloads_queue_progress, queueState.completed, queueState.total)
                } else {
                    stringResource(R.string.downloads_queue_idle)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = RoundedSans,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (queueState.isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                val progress = if (queueState.currentTotal > 0) {
                    queueState.currentDone.toFloat() / queueState.currentTotal.toFloat()
                } else {
                    0f
                }
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                )
            }
        }
    }
}

@Composable
private fun PinnedCollectionRow(
    summary: PinnedCollectionSummary,
    onUnpinClick: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    val label = when (summary.type) {
        PinType.PLAYLIST -> summary.resolvedName ?: stringResource(R.string.downloads_collection_type_playlist)
        PinType.ALBUM -> summary.resolvedName ?: stringResource(R.string.downloads_collection_type_album)
        PinType.FAVORITES -> stringResource(R.string.downloads_collection_type_favorites)
        PinType.LIBRARY -> stringResource(R.string.downloads_collection_type_library)
        else -> stringResource(R.string.downloads_collection_type_song)
    }
    val typeLabel = when (summary.type) {
        PinType.PLAYLIST -> stringResource(R.string.downloads_collection_type_playlist)
        PinType.ALBUM -> stringResource(R.string.downloads_collection_type_album)
        PinType.FAVORITES -> stringResource(R.string.downloads_collection_type_favorites)
        PinType.LIBRARY -> stringResource(R.string.downloads_collection_type_library)
        else -> stringResource(R.string.downloads_collection_type_song)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                CollectionTypeIcon(type = summary.type, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = RoundedSans,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (summary.resolvedName != null) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = RoundedSans,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FilledTonalIconButton(
                onClick = onUnpinClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.cd_remove),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CollectionTypeIcon(type: String, tint: Color) {
    when (type) {
        PinType.PLAYLIST -> Icon(
            painterResource(R.drawable.rounded_queue_music_24),
            contentDescription = null,
            tint = tint
        )
        PinType.ALBUM -> Icon(Icons.Rounded.Album, contentDescription = null, tint = tint)
        PinType.FAVORITES -> Icon(Icons.Rounded.Favorite, contentDescription = null, tint = tint)
        PinType.LIBRARY -> Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = tint)
        else -> Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = tint)
    }
}
