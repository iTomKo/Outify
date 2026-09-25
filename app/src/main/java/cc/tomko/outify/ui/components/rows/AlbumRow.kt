package cc.tomko.outify.ui.components.rows

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
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
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.utils.SharedElementKey

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.AlbumRow(
    album: Album,
    artworkUrl: String?,

    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    density: TrackRowDensity = TrackRowDensity.Default,
    trailingContent: @Composable (() -> Unit)? = null,

    // Interaction handlers
    onRowClick: (() -> Unit)? = null,

    contentDescription: String? = null,

    sharedTransitionKey: String? = "${SharedElementKey.ALBUM_ARTWORK}_${album.uri}",
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val imageDp: Dp = when (density) {
        TrackRowDensity.Compact -> 40.dp
        TrackRowDensity.Default -> 56.dp
        TrackRowDensity.Spacious -> 72.dp
    }

    val combinedModifier = if (onRowClick != null) {
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onRowClick.invoke() },
            )
    } else {
        modifier.fillMaxWidth()
    }

    // Morphing the album cover only if sharedTransitionKey != null
    val modifierWithSharedBounds = if (sharedTransitionKey != null) {
        modifier.sharedBounds(
            rememberSharedContentState(sharedTransitionKey),
            animatedVisibilityScope = LocalNavAnimatedContentScope.current
        )
    } else {
        modifier
    }

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
            SmartImage(
                url = artworkUrl,
                contentDescription = stringResource(R.string.common_artwork),
                modifier = modifierWithSharedBounds
                    .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    .size(imageDp),
                shape = MaterialShapes.Square.toShape(),
                monochrome = LocalUiSettings.current.monochromeAlbums
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = album.name,
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
                        .testTag("albumrow.title")
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
                    // Playing indicator
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.common_playing),
                        modifier = Modifier.size(20.dp)
                    )
                } else if (isSelected) {
                    Checkbox(checked = true, onCheckedChange = null)
                }
            }
        }
    }
}
