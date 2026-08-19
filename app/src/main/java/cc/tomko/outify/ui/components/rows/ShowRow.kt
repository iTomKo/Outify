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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.ShowMediaType
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.utils.SharedElementKey

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowRow(
    show: Show,

    modifier: Modifier = Modifier,
    density: TrackRowDensity = TrackRowDensity.Default,
    trailingContent: @Composable (() -> Unit)? = null,

    // Interaction handlers
    onRowClick: (() -> Unit)? = null,
    onRowLongClick: (() -> Unit)? = null,
    onPublisherClick: (() -> Unit)? = null,

    contentDescription: String? = null,

    sharedTransitionScope: SharedTransitionScope? = null,
    sharedTransitionKey: String? = "${SharedElementKey.ALBUM_ARTWORK}_${show.getCover(CoverSize.MEDIUM)}",
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    .size(imageDp)
            ) {
                SmartImage(
                    url = show.getCover(CoverSize.MEDIUM)?.uri?.let { ALBUM_COVER_URL + it },
                    contentDescription = "Show artwork",
                    modifier = artworkModifier,
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
                    text = show.name,
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
                        .testTag("showrow.title")
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = show.publisher,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .then(
                                if (onPublisherClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onPublisherClick() },
                                        onLongClick = {}
                                    )
                                } else Modifier
                            )
                            .testTag("showrow.publisher")
                    )

                    val episodeCount = show.episodes.size
                    Text(
                        text = " · $episodeCount ${if (episodeCount == 1) "episode" else "episodes"}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (show.mediaType == ShowMediaType.VIDEO || show.mediaType == ShowMediaType.MIXED) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = "Video episodes available"
                    )
                }

                if (show.isExplicit) {
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