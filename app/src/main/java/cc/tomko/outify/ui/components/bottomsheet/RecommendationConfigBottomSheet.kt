package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.reccobeats.RecommendationConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecommendationConfigBottomSheet(
    onDismiss: () -> Unit,
    onSubmit: (RecommendationConfig) -> Unit,
    seeds: List<Track> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()

    var acousticness by remember { mutableStateOf<Float?>(null) }
    var danceability by remember { mutableStateOf<Float?>(null) }
    var energy by remember { mutableStateOf<Float?>(null) }
    var instrumentalness by remember { mutableStateOf<Float?>(null) }
    var liveness by remember { mutableStateOf<Float?>(null) }
    var loudness by remember { mutableStateOf<Float?>(null) }
    var speechiness by remember { mutableStateOf<Float?>(null) }
    var tempo by remember { mutableStateOf<Float?>(null) }
    var valence by remember { mutableStateOf<Float?>(null) }
    var featureWeight by remember { mutableStateOf<Float?>(null) }

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tune Recommendations",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = {
                            onSubmit(
                                RecommendationConfig(
                                    acousticness,
                                    danceability,
                                    energy,
                                    instrumentalness,
                                    liveness,
                                    loudness,
                                    speechiness,
                                    tempo,
                                    valence,
                                    featureWeight
                                )
                            )
                        },
                    ) {
                        Text("Apply")
                    }
                }
            }

            item {
                EmojiSlider(
                    text = "Acousticness",
                    value = acousticness,
                    onValueChange = { acousticness = it },
                    minEmoji = "🎸",
                    maxEmoji = "🎻",
                )
            }

            item {
                EmojiSlider(
                    text = "Danceability",
                    value = danceability,
                    onValueChange = { danceability = it },
                    minEmoji = "🕴️",
                    maxEmoji = "💃",
                )
            }

            item {
                EmojiSlider(
                    text = "Energy",
                    value = energy,
                    onValueChange = { energy = it },
                    minEmoji = "🛌",
                    maxEmoji = "⚡",
                )
            }

            item {
                EmojiSlider(
                    text = "Instrumentalness",
                    value = instrumentalness,
                    onValueChange = { instrumentalness = it },
                    minEmoji = "🎤",
                    maxEmoji = "🎹",
                )
            }

            item {
                EmojiSlider(
                    text = "Liveness",
                    value = liveness,
                    onValueChange = { liveness = it },
                    minEmoji = "🎛️",
                    maxEmoji = "🏟️",
                )
            }

            item {
                EmojiSlider(
                    text = "Loudness",
                    value = loudness,
                    onValueChange = { loudness = it },
                    minEmoji = "🤫",
                    maxEmoji = "📢",
                    range = -60f..2f
                )
            }

            item {
                EmojiSlider(
                    text = "Speechiness",
                    value = speechiness,
                    onValueChange = { speechiness = it },
                    minEmoji = "🎶",
                    maxEmoji = "🗣️",
                )
            }

            item {
                EmojiSlider(
                    text = "Tempo",
                    value = tempo,
                    onValueChange = { tempo = it },
                    minEmoji = "🐢",
                    maxEmoji = "🐇",
                    range = 50f..220f
                )
            }

            item {
                EmojiSlider(
                    text = "Valence (Mood)",
                    value = valence,
                    onValueChange = { valence = it },
                    minEmoji = "😭",
                    maxEmoji = "☀️",
                )
            }

            item {
                EmojiSlider(
                    text = "Feature Weight",
                    value = featureWeight,
                    onValueChange = { featureWeight = it },
                    minEmoji = "🍃",
                    maxEmoji = "🏋️",
                    range = 1f..5f,
                )
            }

            item {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSlider(
    text: String,
    value: Float?,
    onValueChange: (Float) -> Unit,
    minEmoji: String,
    maxEmoji: String,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val isValueSet = value != null
    val sliderValue = value ?: ((range.start + range.endInclusive) / 2f)

    val state = remember(range) {
        SliderState(
            value = sliderValue,
            trackRange = range
        )
    }

    LaunchedEffect(sliderValue) {
        if (state.value != sliderValue) {
            state.value = sliderValue
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isValueSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isValueSet && value != null) {
                Text(
                    text = String.format("%.2f", value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = minEmoji,
                fontSize = 22.sp,
                modifier = Modifier.alpha(if (isValueSet) 1f else 0.35f)
            )

            Slider(
                state = state,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = true,
                interactionSource = interactionSource,
                colors = if (isValueSet) {
                    SliderDefaults.colors()
                } else {
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        activeTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                },
                thumb = { sliderState ->
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        modifier = Modifier.alpha(if (isValueSet) 1f else 0.35f),
                        colors = if (isValueSet) SliderDefaults.colors() else SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.padding(vertical = 4.dp),
                        colors = if (isValueSet) {
                            SliderDefaults.colors()
                        } else {
                            SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.2f
                                ),
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.2f
                                )
                            )
                        }
                    )
                }
            )

            Text(
                text = maxEmoji,
                fontSize = 22.sp,
                modifier = Modifier.alpha(if (isValueSet) 1f else 0.35f)
            )
        }
    }
}