package cc.tomko.outify.ui.components.rows

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.utils.SharedElementKey

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(
    playlist: Playlist,
    artworkUrl: String?,

    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    density: TrackRowDensity = TrackRowDensity.Default,
    trailingContent: @Composable (() -> Unit)? = null,

    // Interaction handlers
    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onArtworkClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,

    contentDescription: String? = null,

    sharedTransitionScope: SharedTransitionScope? = null,
    sharedTransitionKey: String? = "${SharedElementKey.PLAYLIST_ARTWORK}_${playlist.uri}",
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val tertiary = MaterialTheme.colorScheme.tertiaryFixedDim

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
        if (sharedTransitionScope != null && sharedTransitionKey != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    rememberSharedContentState(sharedTransitionKey),
                    animatedVisibilityScope = LocalNavAnimatedContentScope.current
                )
            }
        } else Modifier

    Surface(
        modifier = combinedModifier.semantics {
            contentDescription?.let { this.contentDescription = it }
        },
    ) {
        Row(
            modifier = Modifier
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
            // Container for artwork + badge icon
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
                        contentDescription = "Artwork",
                        modifier = artworkModifier
                            .then(
                                if (onArtworkClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onArtworkClick() },
                                        onLongClick = {}
                                    )
                                } else Modifier
                            ),
                        monochrome = LocalUiSettings.current.monochromePlaylists
                    )
                }

                // Playlist badge overlay
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
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryFixed,
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
                    text = playlist.attributes.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = when (density) {
                            TrackRowDensity.Compact -> 14.sp
                            TrackRowDensity.Default -> 16.sp
                            TrackRowDensity.Spacious -> 18.sp
                        }
                    ),
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
                        .testTag("playlistrow.title")
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = playlist.attributes.description.ifEmpty { "Playlist · ${playlist.contents.size} tracks" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .then(
                            if (onArtistClick != null) {
                                Modifier.combinedClickable(
                                    onClick = { onArtistClick() },
                                    onLongClick = {}
                                )
                            } else Modifier
                        )
                        .testTag("playlistrow.artist")
                )
            }

            if (trailingContent != null) {
                Box(
                    modifier = Modifier.wrapContentWidth(Alignment.End),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    trailingContent()
                }
            } else {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Playing",
                        modifier = Modifier.size(20.dp)
                    )
                } else if (isSelected) {
                    Checkbox(checked = true, onCheckedChange = null)
                }
            }
        }
    }
}
