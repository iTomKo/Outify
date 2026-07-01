package cc.tomko.outify.ui.components.player

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.reccobeats.RecommendationConfig
import cc.tomko.outify.ui.components.bottomsheet.EmojiSlider
import cc.tomko.outify.ui.components.bottomsheet.RecommendationConfigBottomSheet
import cc.tomko.outify.ui.components.rows.SwipeGesture
import cc.tomko.outify.ui.components.rows.SwipeableRowWithGestures
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.player.MultiQueueViewModel
import cc.tomko.outify.ui.viewmodel.player.QueueEntry
import cc.tomko.outify.ui.viewmodel.player.QueueViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.QueueBottomSheet(
    sheetState: SheetState,
    viewModel: QueueViewModel,
    multiQueueViewModel: MultiQueueViewModel,
    onDismissRequest: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    onArtworkClick: (Track) -> Unit,
) {
    fun interleaveQueueTracks(
        realTracks: List<QueueEntry>,
        recsEnabled: Boolean,
        recTracks: List<Track>,
        ratio: Float,
    ): List<QueueEntry> {
        if (!recsEnabled || recTracks.isEmpty()) return realTracks
        val step = (1f / ratio).toInt().coerceAtLeast(1)
        var recId = -1L
        val iter = recTracks.iterator()
        val result = mutableListOf<QueueEntry>()
        var count = 0
        for (qt in realTracks) {
            result.add(qt)
            count++
            if (count >= step && iter.hasNext()) {
                result.add(QueueEntry(recId--, iter.next(), isRecommendation = true))
                count = 0
            }
        }
        return result
    }

    val hapticFeedback = LocalHapticFeedback.current
    val queueState by viewModel.queueState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val spirc = viewModel.spirc

    val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val currentTrack by viewModel.currentTrack.collectAsState(initial = null)
    val likedTracksId by viewModel.likedTrackIds.collectAsState()

    val flipQueueGestures by viewModel.flipQueueGestures.collectAsState()

    val activeQueueId by multiQueueViewModel.activeQueueId.collectAsState()
    val savedQueues by multiQueueViewModel.queues.collectAsState()
    val activeQueueName = remember(activeQueueId, savedQueues) {
        savedQueues.find { it.id == activeQueueId }?.name
    }

    var showSwitcher by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val showRecommendations by viewModel.queueRecommendationsEnabled.collectAsState()
    val recommendationRatio by viewModel.queueRecommendationRatio.collectAsState()
    val recommendationConfig by viewModel.queueRecommendationConfig.collectAsState()

    val recommendationTracks by viewModel.recommendationTracks.collectAsState()

    var showConfigSheet by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel, activeQueueId) {
        viewModel.loadQueue(currentTrack)
    }

    var isDragging by remember { mutableStateOf(false) }

    var displayTracks by remember {
        val initialReal = queueState.tracks.filter { !it.isRecommendation }
        mutableStateOf(
            interleaveQueueTracks(initialReal, showRecommendations, recommendationTracks, recommendationRatio)
        )
    }

    LaunchedEffect(queueState.tracks) {
        if (!isDragging) {
            val realFromState = queueState.tracks.filter { !it.isRecommendation }
            displayTracks = interleaveQueueTracks(realFromState, showRecommendations, recommendationTracks, recommendationRatio)
        }
    }

    LaunchedEffect(showRecommendations, recommendationTracks, recommendationRatio) {
        if (!isDragging) {
            val real = displayTracks.filter { !it.isRecommendation }
            displayTracks = interleaveQueueTracks(real, showRecommendations, recommendationTracks, recommendationRatio)
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (from.index in displayTracks.indices && to.index in displayTracks.indices) {
                displayTracks = displayTracks.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
                isDragging = true
            }
        },
        lazyListState = listState
    )

    LaunchedEffect(listState, isDragging) {
        snapshotFlow {
            if (isDragging) null
            else {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                first to last
            }
        }
            .distinctUntilChanged()
            .collect { indices ->
                indices?.let { (first, last) ->
                    val queueFirst = displayTracks.take(first).count { !it.isRecommendation }
                    val queueLast = displayTracks.take(last).count { !it.isRecommendation } - 1
                    viewModel.onScrollPositionChanged(
                        maxOf(0, queueFirst),
                        maxOf(0, queueLast),
                        currentTrack
                    )
                }
            }
    }

    LaunchedEffect(queueState.tracks, queueState.currentIndex) {
        if (queueState.tracks.isNotEmpty() && !queueState.isLoading) {
            val scrollIndex = displayTracks.indexOfFirst { it.track.uri == currentTrack?.uri }
                .coerceAtLeast(0)
            listState.scrollToItem(scrollIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Queue",
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                        .size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Queue",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        fontWeight = FontWeight.Black,
                    )
                    if (!queueState.isLoading && queueState.totalSize > 0) {
                        Text(
                            text = "${queueState.totalSize} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    activeQueueName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                ) {
                    IconButton(onClick = {
                        if (showRecommendations) {
                            viewModel.setRecsEnabled(false)
                        } else {
                            showConfigSheet = true
                        }
                    }) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Enable recommendations",
                            tint = if (showRecommendations)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = { showSaveDialog = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Save queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = { showSwitcher = true }) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = "Saved queues",
                        tint = if (activeQueueId != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (queueState.isLoadingPrevious) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                queueState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading queue…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                queueState.tracks.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "The queue is empty",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val currentTrackUri = currentTrack?.uri
                        for (entry in displayTracks) {
                            val isRec = entry.isRecommendation
                            val isCurrent = entry.track.uri == currentTrackUri

                            item(key = entry.id) {
                                ReorderableItem(reorderState, key = entry.id) { isDraggingItem ->
                                    val elevation by animateDpAsState(
                                        targetValue = if (isDraggingItem) 4.dp else 0.dp,
                                        label = "elevation"
                                    )
                                    Surface(
                                        shadowElevation = elevation,
                                        shape = RoundedCornerShape(8.dp),
                                        color = when {
                                            isDraggingItem -> MaterialTheme.colorScheme.surfaceVariant
                                            isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            isRec -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                            else -> Color.Transparent
                                        },
                                        modifier = Modifier.animateItem(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            IconButton(
                                                modifier = Modifier.draggableHandle(
                                                    onDragStarted = {
                                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        isDragging = true
                                                    },
                                                        onDragStopped = {
                                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            isDragging = false
                                                            val real = displayTracks.filter { !it.isRecommendation }
                                                            viewModel.setQueueEntries(real)
                                                            viewModel.debouncedSaveToRepository(real)
                                                        }
                                                ),
                                                onClick = {}
                                            ) {
                                                Icon(
                                                    Icons.Default.DragIndicator,
                                                    contentDescription = "Reorder",
                                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            val playNextGesture = listOf(
                                                SwipeGesture(
                                                    thresholdFraction = 0.25f,
                                                    icon = { Icon(Icons.Default.MoveUp, contentDescription = null) },
                                                    onTrigger = {
                                                        val currentUri = currentTrack?.uri
                                                        val mutable = displayTracks.toMutableList()
                                                        mutable.removeAll { it.track.uri == entry.track.uri && it.isRecommendation == entry.isRecommendation }
                                                        val currentIdx = mutable.indexOfFirst { it.track.uri == currentUri }
                                                        val insertAt = (currentIdx + 1).coerceIn(0, mutable.size)
                                                        mutable.add(insertAt, QueueEntry(entry.id, entry.track, entry.isRecommendation))
                                                        val newCurrentIdx = mutable.indexOfFirst { it.track.uri == currentUri }
                                                            .coerceAtLeast(0)
                                                        viewModel.setQueueEntries(mutable, newCurrentIdx)
                                                        viewModel.debouncedSaveToRepository(mutable)
                                                    }
                                                ),
                                            )
                                            val removeFromQueueGesture = listOf(
                                                SwipeGesture(
                                                    thresholdFraction = 0.25f,
                                                    icon = { Icon(Icons.Default.RemoveCircle, contentDescription = null) },
                                                    onTrigger = {
                                                        val currentUri = currentTrack?.uri
                                                        val mutable = displayTracks.toMutableList()
                                                        mutable.removeAll { it.track.uri == entry.track.uri && it.isRecommendation == entry.isRecommendation }
                                                        val newCurrentIdx = mutable.indexOfFirst { it.track.uri == currentUri }
                                                            .coerceAtLeast(0)
                                                        viewModel.setQueueEntries(mutable, newCurrentIdx)
                                                        viewModel.debouncedSaveToRepository(mutable)
                                                    }
                                                )
                                            )

                                            SwipeableTrackRowConfigured(
                                                startGestures = if(flipQueueGestures) removeFromQueueGesture else playNextGesture,
                                                endGestures = if(flipQueueGestures) playNextGesture else removeFromQueueGesture,
                                                track = entry.track,
                                                currentTrack = currentTrack,
                                                isPlaybackPlaying = isPlaybackPlaying,
                                                onRowClick = { },
                                                isLiked = entry.track.id in likedTracksId,
                                                onArtistClick = { onArtistClick(it) },
                                                onArtworkClick = { onArtworkClick(entry.track) },
                                                trailingContent = if (isRec) {
                                                    {
                                                        Icon(
                                                            Icons.Default.AutoAwesome,
                                                            contentDescription = null,
                                                            modifier = Modifier.padding(end = 8.dp),
                                                            tint = MaterialTheme.colorScheme.tertiary,
                                                        )
                                                    }
                                                } else null
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (queueState.isLoadingNext) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            queueState.error?.let { error ->
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showSwitcher) {
        QueueSwitcherBottomSheet(
            viewModel = multiQueueViewModel,
            currentTrack = currentTrack,
            onDismiss = { showSwitcher = false },
        )
    }

    if (showSaveDialog) {
        QueueNameDialog(
            title = "Save queue",
            confirmLabel = "Save",
            onConfirm = { name ->
                multiQueueViewModel.saveCurrentQueue(name, currentTrack)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    if (showConfigSheet) {
        val seeds = remember(queueState.tracks, queueState.currentIndex, currentTrack) {
            val range = 0..5

            range.mapNotNull { offset ->
                when (offset) {
                    0 -> currentTrack
                    else -> queueState.tracks.getOrNull(queueState.currentIndex + offset)?.track
                }
            }
        }

        RecommendationConfigBottomSheet(
            onDismiss = { showConfigSheet = false },
            onSubmit = { config ->
                viewModel.setRecConfig(config)
                viewModel.setRecsEnabled(true)
                val seedUris = seeds.mapNotNull { track ->
                    track.uri.substringAfter("spotify:track:").ifEmpty { null }
                }
                if (seedUris.isNotEmpty()) {
                    viewModel.fetchQueueRecommendations(seedUris, config)
                }
                showConfigSheet = false
            },
            seeds = seeds,
            extraContent = {
                EmojiSlider(
                    text = "Recommendation Ratio",
                    value = recommendationRatio,
                    onValueChange = { ratio ->
                        viewModel.setRecRatio(ratio)
                    },
                    minEmoji = "🧊",
                    maxEmoji = "🔥",
                    range = 0.05f..1.0f
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberQueueBottomSheetState(): QueueBottomSheetController {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val visible = remember { mutableStateOf(false) }
    return remember { QueueBottomSheetController(sheetState, visible) }
}

class QueueBottomSheetController @OptIn(ExperimentalMaterial3Api::class) constructor(
    val sheetState: SheetState,
    val visible: MutableState<Boolean>,
) {
    fun show() {
        visible.value = true
    }

    fun hide() {
        visible.value = false
    }
}