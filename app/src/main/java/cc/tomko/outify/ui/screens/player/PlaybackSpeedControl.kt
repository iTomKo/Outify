package cc.tomko.outify.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 4f

private fun Float.speedLabel(): String {
    val text = if (this == this.roundToInt().toFloat()) {
        this.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
    }
    return "${text}x"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedControl(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showCustomSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedCircle(
            speed = currentSpeed,
            onClick = { expanded = !expanded },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Start,
            ) + fadeIn(),
            exit = shrinkHorizontally(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SPEED_PRESETS.forEach { speed ->
                    SpeedPill(
                        label = speed.speedLabel(),
                        selected = speed == currentSpeed,
                        onClick = {
                            onSpeedChange(speed)
                            expanded = false
                        },
                    )
                }
                if(currentSpeed !in SPEED_PRESETS) {
                    CustomPill(
                        // Highlighted when current speed isn't one of the presets.
                        selected = currentSpeed !in SPEED_PRESETS,
                        onClick = {
                            showCustomSheet = true
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    if (showCustomSheet) {
        CustomSpeedBottomSheet(
            currentSpeed = currentSpeed,
            onDismiss = { showCustomSheet = false },
            onConfirm = { speed ->
                onSpeedChange(speed)
                showCustomSheet = false
            },
        )
    }
}

@Composable
private fun SpeedCircle(
    speed: Float,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = speed.speedLabel(),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SpeedPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = background,
        modifier = Modifier.height(36.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = content,
            )
        }
    }
}

@Composable
private fun CustomPill(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = background,
        modifier = Modifier.height(36.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Custom",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSpeedBottomSheet(
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var sliderValue by remember { mutableFloatStateOf(currentSpeed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    var textValue by rememberSaveable { mutableStateOf(currentSpeed.speedLabel().removeSuffix("x")) }
    var textIsInvalid by remember { mutableStateOf(false) }

    // Keep the text field following the slider without fighting user typing.
    LaunchedEffect(sliderValue) {
        val fromSlider = sliderValue.speedLabel().removeSuffix("x")
        if (fromSlider != textValue) textValue = fromSlider
    }

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun confirm() {
        val speed = sliderValue.roundToNearestQuarter().coerceIn(MIN_SPEED, MAX_SPEED)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm(speed) }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Playback speed",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = textValue,
                onValueChange = { input ->
                    textValue = input
                    val parsed = input.toFloatOrNull()
                    textIsInvalid = parsed == null || parsed < MIN_SPEED || parsed > MAX_SPEED
                    if (parsed != null && parsed in MIN_SPEED..MAX_SPEED) {
                        sliderValue = parsed
                    }
                },
                label = { Text("Speed") },
                suffix = { Text("x") },
                singleLine = true,
                isError = textIsInvalid,
                supportingText = {
                    if (textIsInvalid) {
                        Text("Enter a value between ${MIN_SPEED}x and ${MAX_SPEED.roundToInt()}x")
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.height(24.dp))

            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    sliderValue = value
                    textIsInvalid = false
                },
                valueRange = MIN_SPEED..MAX_SPEED,
                steps = ((MAX_SPEED - MIN_SPEED) / 0.05f).roundToInt() - 1,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${MIN_SPEED}x",
                    style = TextStyle(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${MAX_SPEED.roundToInt()}x",
                    style = TextStyle(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = ::confirm,
                enabled = !textIsInvalid,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Set speed \u2022 ${sliderValue.speedLabel()}")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Snaps to the nearest 0.05 so the confirmed value doesn't carry float noise. */
private fun Float.roundToNearestQuarter(): Float =
    (this / 0.05f).roundToInt() * 0.05f
