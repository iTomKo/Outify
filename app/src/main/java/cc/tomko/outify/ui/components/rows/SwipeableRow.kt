package cc.tomko.outify.ui.components.rows

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import cc.tomko.outify.ui.Haptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

data class SwipeGesture(
    val thresholdFraction: Float,
    val icon: @Composable BoxScope.() -> Unit,
    val onTrigger: () -> Unit,
    val dismissOnTrigger: Boolean = false,
    val backgroundColor: Color? = null
) {
    init {
        require(thresholdFraction in 0f..1f) { "thresholdFraction must be between 0 and 1" }
    }
}

@Composable
fun SwipeableRowWithGestures(
    modifier: Modifier = Modifier,
    startGestures: List<SwipeGesture> = emptyList(),
    endGestures: List<SwipeGesture> = emptyList(),
    minIconSize: Dp = 14.dp,
    iconMaxSizeFraction: Float = 0.5f, // max icon size relative to content height
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) } // px
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var contentHeightPx by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val view = LocalView.current
    var lastArmedGesture by remember { mutableStateOf<SwipeGesture?>(null) }

    val density = LocalDensity.current
    val widthPx = containerWidthPx.toFloat().coerceAtLeast(1f)

    val sortedStart = remember(startGestures) { startGestures.sortedBy { it.thresholdFraction } }
    val sortedEnd = remember(endGestures) { endGestures.sortedBy { it.thresholdFraction } }

    val maxRightPx = remember(sortedEnd, widthPx) {
        if (sortedEnd.isEmpty()) 0f
        else if (sortedEnd.any { it.dismissOnTrigger }) widthPx
        else sortedEnd.maxOf { it.thresholdFraction * widthPx }
    }
    val maxLeftPx = remember(sortedStart, widthPx) {
        if (sortedStart.isEmpty()) 0f
        else if (sortedStart.any { it.dismissOnTrigger }) widthPx
        else sortedStart.maxOf { it.thresholdFraction * widthPx }
    }

    // helper: choose gesture that would be triggered by current offset
    fun chosenGestureForOffset(offset: Float): Pair<SwipeGesture, Float>? {
        return when {
            offset > 0f && sortedEnd.isNotEmpty() -> {
                val px = offset
                val chosen = sortedEnd
                    .filter { it.thresholdFraction * widthPx <= px }
                    .maxByOrNull { it.thresholdFraction }
                chosen?.let { it to (it.thresholdFraction * widthPx) }
            }

            offset < 0f && sortedStart.isNotEmpty() -> {
                val px = abs(offset)
                val chosen = sortedStart
                    .filter { it.thresholdFraction * widthPx <= px }
                    .maxByOrNull { it.thresholdFraction }
                chosen?.let { it to (it.thresholdFraction * widthPx) }
            }

            else -> null
        }
    }

    // Scaling gesture icon
    val contentHeightDp: Dp = with(density) { (contentHeightPx.coerceAtLeast(0)).toDp() }
    val maxIconSizeDp = contentHeightDp * iconMaxSizeFraction

    val iconTargetFraction = remember(offsetX.value, sortedStart, sortedEnd, widthPx) {
        if (offsetX.value > 0f && sortedEnd.isNotEmpty()) {
            val smallest = sortedEnd.first().thresholdFraction * widthPx
            val candidate = sortedEnd
                .filter { it.thresholdFraction * widthPx <= offsetX.value }
                .maxByOrNull { it.thresholdFraction }
            val target = candidate?.thresholdFraction?.times(widthPx) ?: smallest
            (offsetX.value / max(1f, target)).coerceIn(0f, 1f)
        } else if (offsetX.value < 0f && sortedStart.isNotEmpty()) {
            val smallest = sortedStart.first().thresholdFraction * widthPx
            val candidate = sortedStart
                .filter { it.thresholdFraction * widthPx <= abs(offsetX.value) }
                .maxByOrNull { it.thresholdFraction }
            val target = candidate?.thresholdFraction?.times(widthPx) ?: smallest
            (abs(offsetX.value) / max(1f, target)).coerceIn(0f, 1f)
        } else 0f
    }

    val iconTargetDp =
        lerp(minIconSize, maxIconSizeDp.coerceAtLeast(minIconSize), iconTargetFraction)
    val animatedIconDp by animateFloatAsState(
        targetValue = with(density) { iconTargetDp.toPx() },
        animationSpec = tween(120)
    )

    val animatedIconDpFinal: Dp = with(density) { animatedIconDp.toDp() }

    fun applyResistance(raw: Float): Float {
        val sign = sign(raw)
        val absRaw = abs(raw)
        return if (raw >= 0f) {
            if (absRaw <= maxRightPx) raw
            else {
                // past maxRightPx — dampen the extra movement
                val extra = absRaw - maxRightPx
                maxRightPx + extra * 0.35f
            } * sign
        } else {
            if (absRaw <= maxLeftPx) raw
            else {
                val extra = absRaw - maxLeftPx
                -(maxLeftPx + extra * 0.35f)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
    ) {
        val chosenPair =
            chosenGestureForOffset(offsetX.value) // returns gesture and its thresholdPx
        val activeGesture = chosenPair?.first

        if (activeGesture != null && contentHeightPx > 0) {
            val isRightSwipe = offsetX.value > 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp),
                contentAlignment = if (isRightSwipe) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                // background behind the icon area
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            activeGesture.backgroundColor
                                ?: if (isRightSwipe) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer
                        )
                )

                // Icon container
                val visibleWidthPx = abs(offsetX.value)
                val iconCenterOffsetPx = visibleWidthPx / 2f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(contentHeightDp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(animatedIconDpFinal)
                            .offset {
                                val iconHalf = animatedIconDp / 2f
                                val x = if (isRightSwipe) {
                                    iconCenterOffsetPx - iconHalf
                                } else {
                                    // from right side
                                    (containerWidthPx - iconCenterOffsetPx - iconHalf)
                                }
                                IntOffset(x.roundToInt(), 0)
                            }
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.Center
                    ) {
                        activeGesture.icon(this)
                    }
                }
            }
        }

        // Foreground row content
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(sortedStart, sortedEnd, containerWidthPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            val proposed = offsetX.value + dragAmount

                            val allowed = when {
                                proposed > 0f && sortedEnd.isEmpty() -> offsetX.value
                                proposed < 0f && sortedStart.isEmpty() -> offsetX.value
                                else -> applyResistance(proposed)
                            }

                            scope.launch { offsetX.snapTo(allowed) }

                            val armed = chosenGestureForOffset(allowed)?.first
                            if (armed != null) {
                                if (armed !== lastArmedGesture) {
                                    Haptics.swipeArm(context, view)
                                    lastArmedGesture = armed
                                }
                            } else {
                                lastArmedGesture = null
                            }
                        },
                        onDragEnd = {
                            lastArmedGesture = null
                            scope.launch {
                                val final = offsetX.value
                                if (final == 0f) return@launch

                                val sign = sign(final)
                                val absFinal = abs(final)

                                val smallestThresholdPx = when {
                                    final > 0f && sortedEnd.isNotEmpty() -> sortedEnd.first().thresholdFraction * widthPx
                                    final < 0f && sortedStart.isNotEmpty() -> sortedStart.first().thresholdFraction * widthPx
                                    else -> Float.MAX_VALUE
                                }

                                if (absFinal < smallestThresholdPx) {
                                    offsetX.animateTo(
                                        0f,
                                        animationSpec = tween(durationMillis = 120)
                                    )
                                    return@launch
                                }

                                val chosen = if (final > 0f && sortedEnd.isNotEmpty()) {
                                    sortedEnd.filter { it.thresholdFraction * widthPx <= final }
                                        .maxByOrNull { it.thresholdFraction }
                                } else if (final < 0f && sortedStart.isNotEmpty()) {
                                    sortedStart.filter { it.thresholdFraction * widthPx <= absFinal }
                                        .maxByOrNull { it.thresholdFraction }
                                } else null

                                val overshootFraction = 0.12f
                                val overshootAmount = widthPx * overshootFraction

                                val enterSpring = spring<Float>(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessHigh
                                )

                                val settleSpring = spring<Float>(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )

                                if (chosen != null) {
                                    // There is a gesture that was crossed
                                    Haptics.confirm(context, view)
                                    if (chosen.dismissOnTrigger) {
                                        val offScreen = sign * widthPx
                                        offsetX.animateTo(offScreen, animationSpec = enterSpring)
                                        chosen.onTrigger()
                                        offsetX.snapTo(0f)
                                    } else {
                                        val target = chosen.thresholdFraction * widthPx * sign
                                        val overshootTarget = target + sign * overshootAmount
                                        offsetX.animateTo(
                                            overshootTarget,
                                            animationSpec = enterSpring
                                        )
                                        chosen.onTrigger()
                                        offsetX.animateTo(0f, animationSpec = settleSpring)
                                    }
                                } else {
                                    val dynamicOvershoot = minOf(overshootAmount, absFinal * 0.35f)
                                    val overshootTarget = final + sign * dynamicOvershoot

                                    offsetX.animateTo(overshootTarget, animationSpec = enterSpring)
                                    offsetX.animateTo(0f, animationSpec = settleSpring)
                                }
                            }
                        },
                        onDragCancel = {
                            lastArmedGesture = null
                            scope.launch { offsetX.animateTo(0f, animationSpec = tween(200)) }
                        }
                    )
                }
                .fillMaxWidth()
                .onSizeChanged { contentHeightPx = it.height }
        ) {
            Surface(
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                content()
            }
        }
    }
}
