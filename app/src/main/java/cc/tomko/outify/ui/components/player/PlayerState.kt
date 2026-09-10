package cc.tomko.outify.ui.components.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
    sheetState: PlayerSheetState,
    listState: LazyListState,
    miniContent: @Composable (progress: Float) -> Unit,
    fullContent: @Composable (progress: Float) -> Unit,
    modifier: Modifier = Modifier,
    miniPlayerHeight: Dp = 88.dp,
    collapsedBottomInset: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val miniHeightPx = with(density) { miniPlayerHeight.toPx() }
        val insetPx = with(density) { collapsedBottomInset.toPx() }
        val collapsedOffset = screenHeightPx - miniHeightPx - insetPx

        LaunchedEffect(collapsedOffset) {
            sheetState.draggableState.updateAnchors(
                DraggableAnchors {
                    PlayerSheetValue.Expanded at 0f
                    PlayerSheetValue.Collapsed at collapsedOffset
                }
            )
        }

        val progress = sheetState.progress
        val rawOffset = sheetState.draggableState.offset
            .takeIf { !it.isNaN() } ?: collapsedOffset

        val nestedScrollConnection = remember(sheetState, listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    if (delta > 0 &&
                        sheetState.isExpanded &&
                        listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    ) {
                        return Offset(0f, sheetState.draggableState.dispatchRawDelta(delta))
                    }
                    if (delta < 0 && !sheetState.isExpanded) {
                        return Offset(0f, sheetState.draggableState.dispatchRawDelta(delta))
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset, available: Offset, source: NestedScrollSource
                ): Offset {
                    if (available.y > 0) {
                        return Offset(0f, sheetState.draggableState.dispatchRawDelta(available.y))
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val midDrag = sheetState.progress > 0.01f && sheetState.progress < 0.99f
                    val fastFlingDown = available.y > 300f &&
                            sheetState.isExpanded &&
                            listState.firstVisibleItemIndex == 0

                    if (midDrag || fastFlingDown) {
                        val target = when {
                            available.y > 1200f -> PlayerSheetValue.Collapsed
                            available.y < -1200f -> PlayerSheetValue.Expanded
                            sheetState.progress >= 0.5f -> PlayerSheetValue.Expanded
                            else -> PlayerSheetValue.Collapsed
                        }
                        sheetState.draggableState.animateTo(target)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity
                ): Velocity {
                    val midDrag = sheetState.progress > 0.01f && sheetState.progress < 0.99f
                    if (midDrag) {
                        val target = if (sheetState.progress >= 0.5f)
                            PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed
                        sheetState.draggableState.animateTo(target)
                    }
                    return Velocity.Zero
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = rawOffset }
                .nestedScroll(nestedScrollConnection)
        ) {
            if (progress > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = progress }
                ) {
                    fullContent(progress)
                }
            }

            if (progress < 0.99f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = (1f - progress * 3f).coerceIn(0f, 1f) }
                        .pointerInput(sheetState) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    sheetState.draggableState.dispatchRawDelta(dragAmount)
                                },
                                onDragEnd = {
                                    scope.launch {
                                        val target = if (sheetState.progress >= 0.5f)
                                            PlayerSheetValue.Expanded
                                        else
                                            PlayerSheetValue.Collapsed
                                        sheetState.draggableState.animateTo(
                                            target, animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        sheetState.draggableState.animateTo(PlayerSheetValue.Collapsed)
                                    }
                                }
                            )
                        }
                ) {
                    miniContent(progress)
                }
            }
        }
    }
}
