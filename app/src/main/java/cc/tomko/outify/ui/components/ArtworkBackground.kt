package cc.tomko.outify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.data.setting.LocalUiSettings

@Composable
fun ArtworkBackground(
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    fallback: @Composable (() -> Unit)? = null,
    bottomFade: Boolean = true,
    topFade: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (artworkUrl != null) {
            SmartImage(
                url = artworkUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = stringResource(R.string.common_artwork),
                monochrome = LocalUiSettings.current.monochromeHeaders
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                fallback?.invoke()
            }
        }

        // Bottom fade
        if (bottomFade) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.4f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }

        // Top fade
        if (topFade) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}