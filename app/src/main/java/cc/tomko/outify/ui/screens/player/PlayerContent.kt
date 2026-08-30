package cc.tomko.outify.ui.screens.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.playback.model.RepeatMode
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.components.AutoScrollingTextOnDemand
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.ToggleSegmentButton
import cc.tomko.outify.ui.components.WavyMusicSlider
import cc.tomko.outify.ui.model.player.PlayerAction
import cc.tomko.outify.ui.viewmodel.player.PlayerViewModel
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@Composable
fun PlayerContent(
   viewModel: PlayerViewModel,
   expansionFractionProvider: () -> Float,
   onShowQueue: () -> Unit,
   onArtistClick: (Artist) -> Unit,
   onShowClick: (Episode?) -> Unit,
   listState: LazyListState,
   paddingValues: PaddingValues,
   modifier: Modifier = Modifier,
) {
    val audio by viewModel.currentAudio.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isShuffling by viewModel.isShuffling.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle(initialValue = RepeatMode.NONE)
    val isFavorite by viewModel.isLiked.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val elapsedMs by viewModel.positionMs.collectAsState()
    val forwardMilliseconds by viewModel.forwardMilliseconds.collectAsState(15_000)

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gradientEdgeColor = MaterialTheme.colorScheme.primaryContainer
    val textColor = MaterialTheme.colorScheme.onSurface
    val artistTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val albumCoverSection: @Composable (Modifier) -> Unit = { modifier ->
        AlbumCoverContent(
            audio = audio,
            isPlaying = isPlaying,
            playbackSpeed = uiState.playbackSpeed,
            setPlaybackSpeed = viewModel::setPlaybackSpeed,
            modifier = modifier,
        )
    }

    val playerProgressSection: @Composable () -> Unit = {
        PlayerProgressContent(
            elapsed = elapsedMs,
            duration = audio?.duration ?: 0L,
            isPlaying = isPlaying,
            onSeek = {
                viewModel.onAction(PlayerAction.SeekTo(it))
            },
        )
    }

    val controlsSection: @Composable (height: Dp) -> Unit = { height ->
        PlayerControlsContent(
            isShuffleEnabled = isShuffling,
            repeatMode = repeatMode,
            isFavorite = isFavorite,
            onShuffleToggle = { viewModel.onAction(PlayerAction.ShuffleToggle) },
            onRepeatToggle = { viewModel.onAction(PlayerAction.RepeatToggle) },
            onFavoriteToggle = { viewModel.toggleFavorite() },
            height = height,
        )
    }

    val moreActions: @Composable () -> Unit = {
        MoreActionsSection(
            onQueueClick = { onShowQueue() },
            onLyricsClick = { GlobalPopupController.show(PopupSpec.Lyrics(audio!!.sourceTrack!!)) },
            onMoreClick = { GlobalPopupController.show(PopupSpec.TrackInfo(audio!!.sourceTrack!!, isLiked = isFavorite)) }
        )
    }

    val playbackControls: @Composable (height: Dp) -> Unit = { height ->
        PlaybackControls(
            onPrevious = { viewModel.onAction(PlayerAction.Previous) },
            onNext = { viewModel.onAction(PlayerAction.Next) },
            onRewind = {
                val position = (elapsedMs - forwardMilliseconds).coerceAtLeast(0)
                viewModel.onAction(PlayerAction.SeekTo(position))
            },
            onFastForward = {
                val position = (elapsedMs + forwardMilliseconds).coerceAtMost(uiState.totalLengthMs)
                viewModel.onAction(PlayerAction.SeekTo(position))
            },
            onPlayPause = { viewModel.onAction(PlayerAction.PlayPause) },
            canFastForward = forwardMilliseconds > 0,
            isBuffering = uiState.isBuffering,
            isPlaying = isPlaying,
            height = height,
        )
    }

    val trackMetadataSection: @Composable () -> Unit = {
        AudioMetadataSection(
            audio = audio,
            textColor = textColor,
            artistTextColor = artistTextColor,
            onArtistClick = onArtistClick,
            onShowClick = onShowClick,
            gradientEdgeColor = gradientEdgeColor,
            expansionFractionProvider = expansionFractionProvider,
            isPlaying = isPlaying,
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val itemHeight = maxHeight

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                val itemModifier = Modifier.height(itemHeight)
                val isEpisode = audio?.isEpisode() ?: false

                if (isLandscape) {
                    FullPlayerLandscapeContent(
                        paddingValues,
                        modifier = itemModifier,
                        albumCoverSection = albumCoverSection,
                        trackMetadataSection = trackMetadataSection,
                        playerProgressSection = playerProgressSection,
                        playbackControlsSection = playbackControls,
                        controlsSection = controlsSection,
                        moreActions = moreActions,
                        isEpisode = isEpisode
                    )
                } else {
                    FullPlayerPortraitContent(
                        paddingValues,
                        modifier = itemModifier,
                        albumCoverSection = albumCoverSection,
                        trackMetadataSection = trackMetadataSection,
                        playerProgressSection = playerProgressSection,
                        playbackControlsSection = playbackControls,
                        controlsSection = controlsSection,
                        moreActions = moreActions,
                        isEpisode = isEpisode
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumCoverContent(
    audio: PlayableAudio?,
    isPlaying: Boolean,
    playbackSpeed: Float,
    setPlaybackSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val artworkUrl = audio?.getCover(CoverSize.LARGE)?.uri?.let { ALBUM_COVER_URL + it }

    val imageScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "albumArtScale"
    )

    var showExtraSettings by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .clickable {
                showExtraSettings = !showExtraSettings
            },
        contentAlignment = Alignment.Center,
    ) {
        SmartImage(
            url = artworkUrl,
            monochrome = LocalUiSettings.current.monochromePlayer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = imageScale
                    scaleY = imageScale
                }
        )

        AnimatedVisibility(visible = showExtraSettings) {
            PlaybackSpeedControl(
                currentSpeed = playbackSpeed,
                onSpeedChange = setPlaybackSpeed,
            )
        }
    }
}

@Composable
private fun PlayerProgressContent(
   elapsed: Long,
   duration: Long,
   isPlaying: Boolean,
   onSeek: (Long) -> Unit,
) {
    fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    var isDragging by remember { mutableStateOf(false) }
    var targetSliderValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(elapsed, duration, isDragging) {
        if (!isDragging && duration > 0) {
            targetSliderValue = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        }
    }

    val animatedSliderValue by animateFloatAsState(
        targetValue = targetSliderValue,
        animationSpec = tween(durationMillis = 300),
        label = "sliderAnimation"
    )

    val currentValue = if (isDragging) targetSliderValue else animatedSliderValue

    val displayedMs = (currentValue * duration).toLong().coerceIn(0L, duration)

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(
                text = formatTime(displayedMs),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        WavyMusicSlider(
            value = currentValue,
            onValueChange = {
                isDragging = true
                targetSliderValue = it.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                onSeek((targetSliderValue * duration).toLong().coerceIn(0L, duration))
                isDragging = false
            },
            isPlaying = isPlaying
        )
    }
}

private enum class PlaybackIconState { Buffering, Playing, Paused }

private enum class PlaybackButtonType { NONE, REWIND, PREVIOUS, PLAY_PAUSE, NEXT, FAST_FORWARD }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerControlsContent(
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    isFavorite: Boolean,
    height: Dp,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    activeColorMain: Color = MaterialTheme.colorScheme.primary,
    onActiveColorMain: Color = MaterialTheme.colorScheme.onPrimary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    inactiveContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val rowCorners = 60.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier.height(height)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(
                        AbsoluteSmoothCornerShape(
                            cornerRadiusBL = rowCorners,
                            smoothnessAsPercentTR = 60,
                            cornerRadiusBR = rowCorners,
                            smoothnessAsPercentBL = 60,
                            cornerRadiusTL = rowCorners,
                            smoothnessAsPercentBR = 60,
                            cornerRadiusTR = rowCorners,
                            smoothnessAsPercentTL = 60
                        )
                    )
                    .background(containerColor)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val commonModifier = Modifier.weight(1f)

                ToggleSegmentButton(
                    modifier = commonModifier
                        .clip(AbsoluteSmoothCornerShape(
                            cornerRadiusBL = rowCorners,
                            cornerRadiusBR = 0.dp,
                            cornerRadiusTL = rowCorners,
                            cornerRadiusTR = 0.dp,
                        )),
                    active = isShuffleEnabled,
                    activeColor = activeColorMain,
                    activeCornerRadius = rowCorners,
                    activeContentColor = onActiveColorMain,
                    inactiveColor = inactiveColor,
                    inactiveContentColor = inactiveContentColor,
                    onClick = onShuffleToggle,
                    iconId = R.drawable.shuffle,
                    contentDesc = "Shuffle"
                )

                ToggleSegmentButton(
                    modifier = commonModifier
                        .clip(AbsoluteSmoothCornerShape(
                            cornerRadiusBL = 0.dp,
                            cornerRadiusBR = 0.dp,
                            cornerRadiusTL = 0.dp,
                            cornerRadiusTR = 0.dp,
                        )),
                    active = repeatMode != RepeatMode.NONE,
                    activeColor = activeColorMain,
                    activeCornerRadius = rowCorners,
                    activeContentColor = onActiveColorMain,
                    inactiveColor = inactiveColor,
                    inactiveContentColor = inactiveContentColor,
                    onClick = onRepeatToggle,
                    imageVector = if(!repeatMode.repeatTrack) Icons.Default.Repeat else Icons.Default.RepeatOne,
                    contentDesc = "Repeat"
                )

                ToggleSegmentButton(
                    modifier = commonModifier
                        .clip(AbsoluteSmoothCornerShape(
                            cornerRadiusBR = rowCorners,
                            cornerRadiusBL = 0.dp,
                            cornerRadiusTR = rowCorners,
                            cornerRadiusTL = 0.dp,
                        )),
                    active = isFavorite,
                    activeColor = activeColorMain,
                    activeCornerRadius = rowCorners,
                    activeContentColor = onActiveColorMain,
                    inactiveColor = inactiveColor,
                    inactiveContentColor = inactiveContentColor,
                    onClick = onFavoriteToggle,
                    imageVector = Icons.Default.Favorite,
                    contentDesc = "Favorite"
                )
            }
        }
    }
}

@Composable
private fun MoreActionsSection(
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(containerColor)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onLyricsClick) {
                Icon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = "Lyrics",
                    tint = contentColor
                )
            }

            IconButton(onClick = onQueueClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    tint = contentColor
                )
            }

            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More Information",
                    tint = contentColor
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackControls(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onPlayPause: () -> Unit,
    canFastForward: Boolean,
    isBuffering: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    baseWeight: Float = 1f,
    expansionWeight: Float = 1.1f,
    compressionWeight: Float = 0.65f,
    pressAnimationSpec: AnimationSpec<Float> = spring(
        Spring.DampingRatioMediumBouncy,
        Spring.StiffnessMediumLow
    ),
) {
    val iconState = when {
        isBuffering -> PlaybackIconState.Buffering
        isPlaying -> PlaybackIconState.Playing
        else -> PlaybackIconState.Paused
    }

    var lastClicked by remember {
        mutableStateOf<PlaybackButtonType?>(null)
    }

    var clickTrigger by remember {
        mutableIntStateOf(0)
    }

    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(lastClicked, clickTrigger) {
        lastClicked = null
    }

    fun weightFor(button: PlaybackButtonType): Float =
        when (lastClicked) {
            button -> expansionWeight
            null -> baseWeight
            else -> compressionWeight
        }

    val playPauseScale = 1.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height, max = height * playPauseScale)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous
        val prevWeight by animateFloatAsState(
            targetValue = weightFor(PlaybackButtonType.PREVIOUS),
            animationSpec = pressAnimationSpec,
            label = "prevWeight"
        )

        IconButton(
            onClick = {
                lastClicked = PlaybackButtonType.PREVIOUS
                clickTrigger++

                coroutineScope.launch {
                    onPrevious()
                }
            },
            modifier = Modifier
                .weight(prevWeight)
                .fillMaxHeight()
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous"
            )
        }

        // Rewind
        if (canFastForward) {
            val rewindWeight by animateFloatAsState(
                targetValue = weightFor(PlaybackButtonType.REWIND),
                animationSpec = pressAnimationSpec,
                label = "rewindWeight"
            )

            IconButton(
                onClick = {
                    lastClicked = PlaybackButtonType.REWIND
                    clickTrigger++

                    coroutineScope.launch {
                        onRewind()
                    }
                },
                modifier = Modifier
                    .weight(rewindWeight)
                    .fillMaxHeight()
            ) {
                Icon(
                    Icons.Default.FastRewind,
                    contentDescription = "FR 15s",
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        // Play / Pause
        val playWeight by animateFloatAsState(
            targetValue = weightFor(PlaybackButtonType.PLAY_PAUSE),
            animationSpec = pressAnimationSpec,
            label = "playWeight"
        )

        FilledIconButton(
            onClick = {
                lastClicked = PlaybackButtonType.PLAY_PAUSE
                clickTrigger++

                hapticFeedback.performHapticFeedback(
                    HapticFeedbackType.TextHandleMove
                )

                onPlayPause()
            },
            shape = MaterialShapes.Cookie9Sided.toShape(),
            modifier = Modifier
                .size(height * playPauseScale)
                .align(Alignment.CenterVertically),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            AnimatedContent(
                targetState = iconState,
                transitionSpec = {
                    (
                            scaleIn(
                                spring(
                                    Spring.DampingRatioMediumBouncy,
                                    Spring.StiffnessMediumLow
                                )
                            ) + fadeIn()
                            ) togetherWith (
                            scaleOut() + fadeOut()
                            )
                },
                label = "playPauseIcon"
            ) { state ->
                val padding = 8.dp
                val iconSize = 56.dp

                when (state) {
                    PlaybackIconState.Buffering -> {
                        LoadingIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    PlaybackIconState.Playing -> {
                        Icon(
                            Icons.Outlined.Pause,
                            contentDescription = "Pause",
                            modifier = Modifier
                                .padding(padding)
                                .size(iconSize)
                        )
                    }

                    PlaybackIconState.Paused -> {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier
                                .padding(padding)
                                .size(iconSize)
                        )
                    }
                }
            }
        }

        // Fast forward
        if (canFastForward) {
            val ffWeight by animateFloatAsState(
                targetValue = weightFor(PlaybackButtonType.FAST_FORWARD),
                animationSpec = pressAnimationSpec,
                label = "ffWeight"
            )

            IconButton(
                onClick = {
                    lastClicked = PlaybackButtonType.FAST_FORWARD
                    clickTrigger++

                    coroutineScope.launch {
                        onFastForward()
                    }
                },
                modifier = Modifier
                    .weight(ffWeight)
                    .fillMaxHeight()
            ) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = "FF 15s",
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        // Next
        val nextWeight by animateFloatAsState(
            targetValue = weightFor(PlaybackButtonType.NEXT),
            animationSpec = pressAnimationSpec,
            label = "nextWeight"
        )

        IconButton(
            onClick = {
                lastClicked = PlaybackButtonType.NEXT
                clickTrigger++

                coroutineScope.launch {
                    onNext()
                }
            },
            modifier = Modifier
                .weight(nextWeight)
                .fillMaxHeight()
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next"
            )
        }
    }
}

@Composable
private fun AudioMetadataSection(
    audio: PlayableAudio?,
    textColor: Color,
    artistTextColor: Color,
    onArtistClick: (Artist) -> Unit,
    onShowClick: (Episode?) -> Unit,
    gradientEdgeColor: Color,
    expansionFractionProvider: () -> Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val titleStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        color = textColor
    )

    val artistStyle = MaterialTheme.typography.titleMedium.copy(
        letterSpacing = 0.sp,
        color = artistTextColor
    )

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val fraction = expansionFractionProvider()
                alpha = fraction
                translationY = (1f - fraction) * 24f
            }
    ) {

        AutoScrollingTextOnDemand(
            text = audio?.name ?: "Not playing",
            style = titleStyle,
            gradientEdgeColor = gradientEdgeColor,
            expansionFractionProvider = expansionFractionProvider,
            canScroll = isPlaying,
            modifier = Modifier
                .fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(2.dp))

        val subtitle = audio?.artists?.joinToString { it.name }
            ?: audio?.showName
            ?: "Unknown source"

        if(audio?.isEpisode() ?: true) {
            AutoScrollingTextOnDemand(
                text = subtitle,
                style = artistStyle,
                gradientEdgeColor = gradientEdgeColor,
                expansionFractionProvider = expansionFractionProvider,
                canScroll = isPlaying,
                modifier = Modifier
                    .clickable {
                        onShowClick(audio?.sourceEpisode)
                    }
                    .fillMaxWidth(),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                audio.artists?.forEachIndexed { index, artist ->
                    Text(
                        text = artist.name + if (index < (audio.artists.size - 1)) ", " else "",
                        style = artistStyle,
                        modifier = Modifier.clickable {
                            onArtistClick(artist)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullPlayerLandscapeContent(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    albumCoverSection: @Composable (Modifier) -> Unit,
    trackMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    playbackControlsSection: @Composable (height: Dp) -> Unit,
    controlsSection: @Composable (height: Dp) -> Unit,
    moreActions: @Composable () -> Unit,
    isEpisode: Boolean,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                albumCoverSection(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .padding(top = 16.dp)
                )
            }

            Spacer(Modifier.weight(0.25f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                trackMetadataSection()
                playerProgressSection()
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight(),
            ) {
                playbackControlsSection(50.dp)

                Spacer(Modifier.weight(0.25f))

                controlsSection(50.dp)
            }
        }
    }
}

@Composable
private fun FullPlayerPortraitContent(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    albumCoverSection: @Composable (Modifier) -> Unit,
    trackMetadataSection: @Composable () -> Unit,
    playerProgressSection: @Composable () -> Unit,
    playbackControlsSection: @Composable (height: Dp) -> Unit,
    controlsSection: @Composable (height: Dp) -> Unit,
    moreActions: @Composable () -> Unit,
    isEpisode: Boolean,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
    ) {
        val horizontalPadding = maxWidth * 0.06f
        val outerVerticalPadding = maxHeight * 0.04f
        val topPadding = maxHeight * 0.035f
        val playbackControlsHeight = maxHeight * 0.105f
        val segmentedControlsHeight = maxHeight * 0.09f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = outerVerticalPadding)
                .padding(top = topPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                albumCoverSection(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding * 0.65f)
                        .padding(top = topPadding * 0.5f)
                )
            }

            Spacer(Modifier.weight(0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding * 0.65f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                trackMetadataSection()
                playerProgressSection()
            }

            Spacer(Modifier.weight(0.05f))

            playbackControlsSection(playbackControlsHeight)

            Spacer(Modifier.weight(1f))

            controlsSection(segmentedControlsHeight)

            if(!isEpisode) {
                moreActions()
            }
        }
    }
}
