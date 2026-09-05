package cc.tomko.outify.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.viewmodel.player.MiniPlayerViewModel
import cc.tomko.outify.utils.SharedElementKey
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private val TAB_HEIGHT = 20.dp
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    viewModel: MiniPlayerViewModel,
    onDismiss: () -> Unit,
    showQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentAudio by viewModel.currentAudio.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val isBuffering by viewModel.isBuffering().collectAsState(initial = false)
    val isActiveDevice by viewModel.isActiveDevice().collectAsState(initial = true)
    val currentTime by viewModel.positionMs.collectAsState(initial = 0L)
    val spirc = viewModel.spirc

    val totalTime = currentAudio?.duration ?: 0L

    val imageSize = 44.dp
    val artworkUrl =
        currentAudio?.getCover(CoverSize.SMALL)?.uri?.let { ALBUM_COVER_URL + it }

    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) {
                var totalDragX = 0f
                var totalDragY = 0f

                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        coroutineScope.launch {
                            val target = max(0f, offsetY.value + dragAmount.y)
                            offsetY.snapTo(target)
                        }
                    },
                    onDragEnd = {
                        val horizontalThreshold = 100.dp.toPx()
                        val verticalThreshold = 40.dp.toPx()

                        when {
                            totalDragX > horizontalThreshold -> spirc.playerPrevious()
                            totalDragX < -horizontalThreshold -> spirc.playerNext()
                        }

                        if (totalDragY > verticalThreshold) {
                            onDismiss()
                        }

                        coroutineScope.launch {
                            offsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 300)
                            )
                        }

                        totalDragX = 0f
                        totalDragY = 0f
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            offsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 300)
                            )
                        }
                        totalDragX = 0f
                        totalDragY = 0f
                    }
                )
            }
            .offset { IntOffset(x = 0, y = offsetY.value.roundToInt()) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = onExpand != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentWidth()
                .offset(y = -TAB_HEIGHT, x = (-55).dp)
        ) {
            Surface(
                modifier = Modifier
                    .height(TAB_HEIGHT)
                    .clickable { onExpand?.invoke() },
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandLess,
                        contentDescription = "Expand",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable { onClick?.invoke() },
            shape = RoundedCornerShape(32.dp),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 10.dp)
            ) {
                // Album artwork
                Box(
                    modifier = Modifier
                        .size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .size(imageSize)
                    ) {
                        SmartImage(
                            url = artworkUrl,
                            contentDescription = "Artwork",
                            modifier = Modifier
                                .fillMaxSize(),
                            monochrome = LocalUiSettings.current.monochromePlayer
                        )
                    }

                    if (isBuffering) {
                        ContainedLoadingIndicator(
                            modifier = Modifier
                                .size(imageSize)
                                .alpha(0.5f)
                                .align(Alignment.Center),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentAudio?.name ?: "Nothing playing",
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    val subtitle = currentAudio?.artists?.joinToString { it.name }
                        ?: currentAudio?.showName
                        ?: "Unknown source"

                    Text(
                        text = buildString {
                            append(subtitle)
                            append(" · ")
                            append(formatTime(currentTime))
                            append(" / ")
                            append(formatTime(totalTime))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = if (isActiveDevice) Icons.Default.PhoneIphone else Icons.Default.Cast

                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier
                            .clickable {
                                viewModel.openDevices()
                            }
                            .padding(6.dp)
                            .size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    IconButton(
                        onClick = showQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "See queue",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = MaterialShapes.Cookie4Sided.toShape(),
                    ) {
                        IconButton(onClick = {
                            viewModel.setAudio(currentAudio)
                            spirc.playerPlayPause()
                        }) {
                            if (isPlaying) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%01d:%02d".format(minutes, seconds)
}