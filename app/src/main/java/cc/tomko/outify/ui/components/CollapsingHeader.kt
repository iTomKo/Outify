package cc.tomko.outify.ui.components

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollapsingHeader(
    state: CollapsingHeaderState,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    titleContent: @Composable ColumnScope.() -> Unit,
    fabContent: @Composable (() -> Unit)? = null,
    actionButtonContent: @Composable (() -> Unit)? = null,
) {
    val collapseFraction = state.collapseFraction

    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundAlpha = collapseFraction
    val headerContentAlpha = 1f - (collapseFraction * 2).coerceAtMost(1f)

    val titleScale = lerp(1f, 0.75f, collapseFraction)
    val titlePaddingStart = lerp(24.dp, 58.dp, collapseFraction)
    val titleContainerHeight = lerp(88.dp, 56.dp, collapseFraction)
    val titleVerticalBias = lerp(1f, -1f, collapseFraction)
    val animatedTitleAlignment =
        BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val heightPx = state.height.roundToInt()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = heightPx, maxHeight = heightPx)
                )
                layout(placeable.width, heightPx) {
                    placeable.place(0, 0)
                }
            }
            .onGloballyPositioned { coords ->
                Log.d("GapDebug", "Header bottom in window: ${coords.positionInWindow().y + coords.size.height}, isRefreshing=?")
            }
//            .background(surfaceColor.copy(alpha = backgroundAlpha))
            .background(Color.Magenta)
    ) {

        // Background layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = headerContentAlpha }
                .background(Color.Green)
        ) {
            backgroundContent()
        }

        // Foreground
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(Color.Blue)
        ) {

            FilledIconButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 4.dp),
                onClick = onBackPressed
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            actionButtonContent?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 4.dp)
                ) {
                    it()
                }
            }

            Box(
                modifier = Modifier
                    .align(animatedTitleAlignment)
                    .height(titleContainerHeight)
                    .fillMaxWidth()
                    .background(Color.Red)
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = titlePaddingStart, end = 120.dp)
                        .graphicsLayer {
                            scaleX = titleScale
                            scaleY = titleScale
                        }
                        .background(Color.Yellow)
                ) {
                    titleContent()
                }
            }

            fabContent?.let {
                val fabScale = 1f - collapseFraction

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            scaleX = fabScale
                            scaleY = fabScale
                            alpha = fabScale
                        }
                ) {
                    it()
                }
            }
        }
    }
}

class CollapsingHeaderState(
    val minHeightPx: Float,
    val maxHeightPx: Float,
    private val scope: CoroutineScope
) {

    var height by mutableFloatStateOf(maxHeightPx)
        private set

    val collapseFraction: Float
        get() = 1f - (
                (height - minHeightPx) /
                        (maxHeightPx - minHeightPx)
                ).coerceIn(0f, 1f)

    var canExpand: Boolean = false

    val nestedScrollConnection = object : NestedScrollConnection {

        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            val delta = available.y

            // Only expand when the list is scrolled all the way to the top
            if (delta > 0 && !canExpand) return Offset.Zero

            val previous = height

            val newHeight = (previous + delta)
                .coerceIn(minHeightPx, maxHeightPx)

            val consumed = newHeight - previous

            if (consumed != 0f) {
                height = newHeight
            }

            return Offset(0f, consumed)
        }
    }

    suspend fun snapIfNeeded(canExpand: Boolean) {
        val midpoint = (minHeightPx + maxHeightPx) / 2f
        val target = if (height > midpoint && canExpand) maxHeightPx else minHeightPx
        if (height == target) return

        Animatable(height).animateTo(target, spring(stiffness = Spring.StiffnessMedium)) {
            height = value
        }
    }
}

@Composable
fun rememberCollapsingHeaderState(
    minHeight: Dp = 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    maxHeight: Dp = 300.dp
): CollapsingHeaderState {

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val minPx = with(density) { minHeight.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }

    return remember(minPx, maxPx) {
        CollapsingHeaderState(minPx, maxPx, scope)
    }
}