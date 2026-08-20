package cc.tomko.outify.ui.components.rows

import android.annotation.SuppressLint
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalEpisodeSwipeActionHandler
import cc.tomko.outify.data.setting.LocalSwipeActionHandler
import cc.tomko.outify.data.setting.LocalSwipeGestureSettings
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.data.setting.buildLongPressAction
import cc.tomko.outify.data.setting.buildLongPressActionForEpisode
import cc.tomko.outify.data.setting.buildSwipeGesturesForEpisode
import cc.tomko.outify.ui.components.AudioBarsIndicator
import cc.tomko.outify.ui.components.SkeletonBox
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.rows.EpisodeRow
import java.util.concurrent.TimeUnit

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun SharedTransitionScope.SwipeableEpisodeRowConfigured(
    episode: Episode?,
    modifier: Modifier = Modifier,

    isLiked: Boolean = false,
    isLoaded: Boolean = false,
    isPlaybackPlaying: Boolean = false,
    isSelected: Boolean = false,

    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onArtworkClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onShowNameClick: (() -> Unit)? = null,

    trailingContent: @Composable (() -> Unit)? = null,

    startGestures: List<SwipeGesture>? = null,
    endGestures: List<SwipeGesture>? = null,
) {
    if (episode == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonBox(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp),
                    shape = RoundedCornerShape(4.dp)
                )
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp),
                )
            }
        }
        return
    }

    val artworkUrl = remember(episode.uri) {
        episode.getCover(CoverSize.MEDIUM)?.uri?.let { ALBUM_COVER_URL + it }
    }

    BoxWithConstraints(modifier = modifier) {
        val (start, end) = if (startGestures != null && endGestures != null) {
            startGestures to endGestures
        } else {
            rememberEpisodeGestures(episode, isLiked)
        }

        val settings = LocalSwipeGestureSettings.current
        val handler = LocalEpisodeSwipeActionHandler.current

        val longPressAction = remember(settings, episode, startGestures, endGestures) {
            if (startGestures != null && endGestures != null) null
            else buildLongPressActionForEpisode(settings, handler, episode)
        }

        SwipeableRowWithGestures(
            startGestures = start,
            endGestures = end,
            modifier = Modifier
        ) {
            EpisodeRow(
                title = episode.name,
                showName = episode.showName,
                artworkUrl = artworkUrl,
                duration = episode.duration,
                publishTime = episode.publishTime,
                isExplicit = episode.isExplicit,
                isLoaded = isLoaded,
                isPlaying = isPlaybackPlaying,
                isSelected = isSelected,
                onRowClick = onRowClick,
                onRowLongClick = {
                    if (onRowLongClick != null) {
                        onRowLongClick.invoke()
                    } else {
                        longPressAction?.invoke()
                    }
                },
                onArtworkClick = onArtworkClick,
                onTitleClick = onTitleClick,
                onShowNameClick = onShowNameClick,
                trailingContent = trailingContent
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.EpisodeRow(
    title: String,
    showName: String,
    artworkUrl: String?,
    duration: Long,
    publishTime: Long,

    modifier: Modifier = Modifier,
    isExplicit: Boolean = false,
    isLoaded: Boolean = false,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    density: TrackRowDensity = TrackRowDensity.Default,
    trailingContent: @Composable (() -> Unit)? = null,

    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onArtworkClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onShowNameClick: (() -> Unit)? = null,

    contentDescription: String? = null,

    sharedTransitionKey: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer

    val imageDp: Dp = when (density) {
        TrackRowDensity.Compact -> 40.dp
        TrackRowDensity.Default -> 56.dp
        TrackRowDensity.Spacious -> 72.dp
    }

    val combinedModifier = if (onRowClick != null || onRowLongClick != null) {
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onRowClick?.invoke() },
                onLongClick = { onRowLongClick?.invoke() }
            )
    } else {
        modifier.fillMaxWidth()
    }

    val artworkModifier =
        if (sharedTransitionKey != null) {
            Modifier.sharedBounds(
                rememberSharedContentState(sharedTransitionKey),
                animatedVisibilityScope = LocalNavAnimatedContentScope.current
            )
        } else Modifier

    Surface(
        color = tertiaryContainer.copy(alpha = 0.12f),
        modifier = combinedModifier.semantics {
            contentDescription?.let { this.contentDescription = it }
        },
    ) {
        Row(
            modifier = modifier
                .padding(
                    horizontal = 12.dp, vertical = when (density) {
                        TrackRowDensity.Compact -> 6.dp
                        TrackRowDensity.Default -> 8.dp
                        TrackRowDensity.Spacious -> 12.dp
                    }
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    .size(imageDp)
            ) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(imageDp)
                ) {
                    SmartImage(
                        url = artworkUrl,
                        contentDescription = "Episode artwork",
                        modifier = artworkModifier
                            .then(
                                if (onArtworkClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onArtworkClick() },
                                        onLongClick = {}
                                    )
                                } else Modifier
                            ),
                        monochrome = LocalUiSettings.current.monochromeTracks
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(tertiary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Podcasts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = showName.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    ),
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onShowNameClick != null) {
                                Modifier.combinedClickable(
                                    onClick = { onShowNameClick() },
                                    onLongClick = {}
                                )
                            } else Modifier
                        )
                )

                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = when (density) {
                            TrackRowDensity.Compact -> 14.sp
                            TrackRowDensity.Default -> 16.sp
                            TrackRowDensity.Spacious -> 18.sp
                        }
                    ),
                    fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isLoaded) tertiary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onTitleClick != null) {
                                Modifier.combinedClickable(
                                    onClick = { onTitleClick() },
                                    onLongClick = {}
                                )
                            } else Modifier
                        )
                        .testTag("episoderow.title")
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = formatEpisodeMeta(publishTime, duration),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("episoderow.meta")
                )
            }

            Row(
                modifier = Modifier.padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoaded) {
                    AudioBarsIndicator(
                        isPlaying = isPlaying,
                        barCount = 4,
                        barWidth = 3.dp,
                        barHeight = 12.dp,
                        spacing = 3.dp,
                        color = LocalContentColor.current
                    )
                }

                if (isExplicit) {
                    Icon(
                        Icons.Default.Explicit,
                        contentDescription = null
                    )
                }

                trailingContent?.invoke()
            }
        }
    }
}

private fun formatEpisodeMeta(publishTimeMs: Long, durationMs: Long): String {
    val now = System.currentTimeMillis()
    val daysAgo = TimeUnit.MILLISECONDS.toDays(now - publishTimeMs)
    val whenStr = when {
        daysAgo <= 0 -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo < 30 -> "${daysAgo}d ago"
        else -> "${daysAgo / 30}mo ago"
    }
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val durationStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    return "$whenStr · $durationStr"
}

@Composable
fun rememberEpisodeGestures(
    episode: Episode,
    isFavorited: Boolean = false
): Pair<List<SwipeGesture>, List<SwipeGesture>> {
    val settings = LocalSwipeGestureSettings.current
    val handler = LocalEpisodeSwipeActionHandler.current
    val colorscheme = MaterialTheme.colorScheme

    return remember(settings, episode, isFavorited) {
        buildSwipeGesturesForEpisode(settings, handler, episode, colorscheme, isFavorited)
    }
}