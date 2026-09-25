package cc.tomko.outify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R

data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float
)

fun Color.toHsv(): HsvColor {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    return HsvColor(
        hue = hsv[0],
        saturation = hsv[1],
        value = hsv[2],
        alpha = alpha
    )
}

fun HsvColor.toColor(): Color {
    return Color(
        android.graphics.Color.HSVToColor(
            (alpha * 255).toInt(),
            floatArrayOf(hue, saturation, value)
        )
    )
}

@Composable
fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    Slider(
        value = hue,
        onValueChange = onHueChange,
        valueRange = 0f..360f,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent
        ),
        modifier = Modifier
            .height(32.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                ),
            )
    )
}

@Composable
private fun NormalSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    Column {
        Text(label)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f
        )
    }
}

@Composable
fun ColorPicker(
    initial: Color,
    onColorChanged: (Color) -> Unit,
    preview: Boolean = true,
) {
    var hsv by remember { mutableStateOf(initial.toHsv()) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        // Hue bar (top rainbow)
        HueBar(
            hue = hsv.hue,
            onHueChange = {
                hsv = hsv.copy(hue = it)
                onColorChanged(hsv.toColor())
            }
        )

        // Saturation
        NormalSlider(
            label = stringResource(R.string.color_saturation),
            value = hsv.saturation,
            onChange = {
                hsv = hsv.copy(saturation = it)
                onColorChanged(hsv.toColor())
            }
        )

        // Value / Lightness
        NormalSlider(
            label = stringResource(R.string.color_lightness),
            value = hsv.value,
            onChange = {
                hsv = hsv.copy(value = it)
                onColorChanged(hsv.toColor())
            }
        )

        if (preview) {
            // Preview
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(hsv.toColor(), CircleShape)
                    .border(1.dp, Color.Gray, CircleShape)
            )
        }
    }
}