package cc.tomko.outify.ui.components.rows

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.AudioBarsIndicator
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.utils.SharedElementKey

enum class TrackRowDensity { Compact, Default, Spacious }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    title: String,
    artists: List<Artist>,
    artworkUrl: String?,

    modifier: Modifier = Modifier,
    isExplicit: Boolean = false,
    isLoaded: Boolean = false,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    density: TrackRowDensity = TrackRowDensity.Default,
    trailingContent: @Composable (() -> Unit)? = null,

    /**
     * Instead of showing artists names show the album name
     */
    showAlbumName: Boolean = false,
    albumName: String? = null,

    // Interaction handlers
    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onArtworkClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    onArtistClick: ((Artist) -> Unit)? = null,

    contentDescription: String? = null,

    sharedTransitionScope: SharedTransitionScope? = null,
    sharedTransitionKey: String? = "${SharedElementKey.ALBUM_ARTWORK}_${artworkUrl}",
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
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
            Surface(
                color = color,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    .size(imageDp)
            ) {
                SmartImage(
                    url = artworkUrl,
                    contentDescription = stringResource(R.string.common_artwork),
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

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
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
                        .testTag("trackrow.title")
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Artists
                if (!showAlbumName) {
                    Row(modifier = modifier) {
                        artists.forEachIndexed { index, artist ->

                            Text(
                                text = artist.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .then(
                                        if (onArtistClick != null) {
                                            Modifier.combinedClickable(
                                                onClick = { onArtistClick(artist) },
                                                onLongClick = {}
                                            )
                                        } else Modifier
                                    )
                                    .testTag("trackrow.artist.$index")
                            )

                            // Add comma separator except after last
                            if (index < artists.lastIndex) {
                                Text(
                                    text = ", ",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = albumName ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .then(
                                if (onArtworkClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onArtworkClick?.invoke() },
                                        onLongClick = {}
                                    )
                                } else Modifier
                            )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(end = 16.dp),
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

                if (isSelected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null
                    )
                }

                trailingContent?.invoke()
            }
        }
    }
}