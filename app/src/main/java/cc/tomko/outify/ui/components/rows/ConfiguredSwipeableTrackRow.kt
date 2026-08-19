@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH")

package cc.tomko.outify.ui.components.rows

import android.annotation.SuppressLint
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.sharedTransitionKey
import cc.tomko.outify.data.setting.LocalSwipeActionHandler
import cc.tomko.outify.data.setting.LocalSwipeGestureSettings
import cc.tomko.outify.data.setting.buildLongPressAction
import cc.tomko.outify.data.setting.buildSwipeGesturesForTrack
import cc.tomko.outify.ui.components.SkeletonBox

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun SharedTransitionScope.SwipeableTrackRowConfigured(
    track: Track?,
    modifier: Modifier = Modifier,
    currentAudio: PlayableAudio? = null,

    isLiked: Boolean = false,
    isPlaybackPlaying: Boolean = false,
    isTransitioning: Boolean = false,
    isSelected: Boolean = false,

    /**
     * Instead of showing artists names show the album name
     */
    showAlbumName: Boolean = false,

    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onArtworkClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onArtistClick: ((Artist) -> Unit)? = null,

    trailingContent: @Composable (() -> Unit)? = null,

    startGestures: List<SwipeGesture>? = null,
    endGestures: List<SwipeGesture>? = null,
) {
    if (track == null) {
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

    val artworkUrl = remember(track.uri) {
        val albumUrl = track.album?.getCover(CoverSize.SMALL)?.uri ?: ""
        if (albumUrl.startsWith("https://")) {
            albumUrl
        } else {
            ALBUM_COVER_URL + albumUrl
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val (start, end) = if (startGestures != null && endGestures != null) {
            startGestures to endGestures
        } else {
            rememberTrackGestures(track, isLiked)
        }

        val settings = LocalSwipeGestureSettings.current
        val handler = LocalSwipeActionHandler.current
        val longPressAction = remember(settings, track, startGestures, endGestures) {
            if (startGestures != null && endGestures != null) null
            else buildLongPressAction(settings, handler, track)
        }

        SwipeableRowWithGestures(
            startGestures = start,
            endGestures = end,
            modifier = Modifier
        ) {
            TrackRow(
                title = track.name,
                artists = track.artists,
                artworkUrl = artworkUrl,
                isExplicit = track.explicit,
                isLoaded = currentAudio?.uri.equals(track.uri),
                isPlaying = isPlaybackPlaying,
                isSelected = isSelected,

                showAlbumName = showAlbumName,
                albumName = track.album?.name,

                onRowClick = onRowClick,
                onRowLongClick = {
                    if (onRowLongClick != null) {
                        onRowLongClick.invoke()
                    } else {
                        longPressAction?.invoke()
                    }
                },
                onArtistClick = onArtistClick,
                onArtworkClick = onArtworkClick,
                onTitleClick = onTitleClick,

                trailingContent = {
                    // Liked indicator
                    if (isLiked) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Liked"
                        )
                    }

                    trailingContent?.invoke()

                    Text(
                        text = formatDuration(track.duration.toInt()),
                        style = MaterialTheme.typography.bodySmall
                    )
                },

                sharedTransitionScope = this@SwipeableTrackRowConfigured,
                sharedTransitionKey = if (isTransitioning)
                    track.album?.sharedTransitionKey() else null,
                modifier = modifier
            )
        }
    }
}

@Composable
fun rememberTrackGestures(
    track: Track,
    isLiked: Boolean = false
): Pair<List<SwipeGesture>, List<SwipeGesture>> {
    val settings = LocalSwipeGestureSettings.current
    val handler = LocalSwipeActionHandler.current

    val colorscheme = MaterialTheme.colorScheme

    val (start, end) = remember(settings, track, isLiked) {
        buildSwipeGesturesForTrack(settings, handler, track, colorscheme, isLiked)
    }

    return start to end
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}