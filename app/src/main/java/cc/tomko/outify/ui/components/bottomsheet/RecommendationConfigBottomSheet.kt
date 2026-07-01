package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.reccobeats.RecommendationConfig

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecommendationConfigBottomSheet(
    onDismiss: () -> Unit,
    onSubmit: (RecommendationConfig) -> Unit,
    seeds: List<Track> = emptyList(),
    extraContent: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()

    var energy by remember { mutableStateOf<Float?>(null) }
    var danceability by remember { mutableStateOf<Float?>(null) }
    var valence by remember { mutableStateOf<Float?>(null) }
    var tempo by remember { mutableStateOf<Float?>(null) }

    var acousticness by remember { mutableStateOf<Float?>(null) }
    var instrumentalness by remember { mutableStateOf<Float?>(null) }
    var liveness by remember { mutableStateOf<Float?>(null) }
    var loudness by remember { mutableStateOf<Float?>(null) }
    var speechiness by remember { mutableStateOf<Float?>(null) }
    var featureWeight by remember { mutableStateOf<Float?>(null) }

    var advancedExpanded by remember { mutableStateOf(false) }
    val advancedActiveCount = listOf(
        acousticness, instrumentalness, liveness, loudness, speechiness, featureWeight
    ).count { it != null }

    val expandRotation by animateFloatAsState(
        targetValue = if (advancedExpanded) 180f else 0f,
        animationSpec = spring(),
        label = "expand_rotation"
    )

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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                        acousticness, danceability, energy, instrumentalness,
                                        liveness, loudness, speechiness, tempo, valence, featureWeight
                                    )
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Apply")
                        }
                    }

                    Text(
                        text = if (seeds.isNotEmpty()) {
                            "Based on ${seeds.size} selected track${if (seeds.size != 1) "s" else ""} - nudge the sliders to shape the vibe"
                        } else {
                            "Adjust the sliders below to shape the kind of tracks you'll get"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                EmojiSlider(
                    text = "Energy",
                    subtitle = "How intense and fast-paced the tracks feel",
                    value = energy,
                    onValueChange = { energy = it },
                    onReset = { energy = null },
                    minEmoji = "🛌",
                    maxEmoji = "⚡",
                )
            }

            item {
                EmojiSlider(
                    text = "Danceability",
                    subtitle = "How suited the tracks are to dancing",
                    value = danceability,
                    onValueChange = { danceability = it },
                    onReset = { danceability = null },
                    minEmoji = "🕴️",
                    maxEmoji = "💃",
                )
            }

            item {
                EmojiSlider(
                    text = "Mood",
                    subtitle = "Musical positivity — sad and moody vs. upbeat and cheerful",
                    value = valence,
                    onValueChange = { valence = it },
                    onReset = { valence = null },
                    minEmoji = "😭",
                    maxEmoji = "☀️",
                )
            }

            item {
                EmojiSlider(
                    text = "Tempo",
                    subtitle = "Roughly how fast the beat is, in BPM",
                    value = tempo,
                    onValueChange = { tempo = it },
                    onReset = { tempo = null },
                    minEmoji = "🐢",
                    maxEmoji = "🐇",
                    range = 50f..220f
                )
            }

            item {
                AdvancedSectionToggle(
                    expanded = advancedExpanded,
                    activeCount = advancedActiveCount,
                    rotation = expandRotation,
                    onClick = { advancedExpanded = !advancedExpanded }
                )
            }

            item {
                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = expandVertically(animationSpec = spring()) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring()) + fadeOut(),
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            EmojiSlider(
                                text = "Acousticness",
                                subtitle = "Unplugged and organic vs. electric and produced",
                                value = acousticness,
                                onValueChange = { acousticness = it },
                                onReset = { acousticness = null },
                                minEmoji = "🎸",
                                maxEmoji = "🎻",
                            )

                            EmojiSlider(
                                text = "Instrumentalness",
                                subtitle = "How likely a track has no vocals at all",
                                value = instrumentalness,
                                onValueChange = { instrumentalness = it },
                                onReset = { instrumentalness = null },
                                minEmoji = "🎤",
                                maxEmoji = "🎹",
                            )

                            EmojiSlider(
                                text = "Liveness",
                                subtitle = "Studio recording vs. captured in front of a crowd",
                                value = liveness,
                                onValueChange = { liveness = it },
                                onReset = { liveness = null },
                                minEmoji = "🎛️",
                                maxEmoji = "🏟️",
                            )

                            EmojiSlider(
                                text = "Loudness",
                                subtitle = "Overall volume of the track, in decibels",
                                value = loudness,
                                onValueChange = { loudness = it },
                                onReset = { loudness = null },
                                minEmoji = "🤫",
                                maxEmoji = "📢",
                                range = -60f..2f
                            )

                            EmojiSlider(
                                text = "Speechiness",
                                subtitle = "Musical vs. spoken-word heavy, like a podcast",
                                value = speechiness,
                                onValueChange = { speechiness = it },
                                onReset = { speechiness = null },
                                minEmoji = "🎶",
                                maxEmoji = "🗣️",
                            )

                            EmojiSlider(
                                text = "Feature Weight",
                                subtitle = "How strongly these sliders steer the recommendations",
                                value = featureWeight,
                                onValueChange = { featureWeight = it },
                                onReset = { featureWeight = null },
                                minEmoji = "🍃",
                                maxEmoji = "🏋️",
                                range = 1f..5f,
                            )
                        }
                    }
                }
            }

            if (extraContent != null) {
                item {
                    extraContent()
                }
            }
        }
    }
}

/**
 * Clickable row that reveals/hides the advanced, less commonly used sliders.
 */
@Composable
private fun AdvancedSectionToggle(
    expanded: Boolean,
    activeCount: Int,
    rotation: Float,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = "Advanced tuning",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Acoustic detail, loudness, and more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = activeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse advanced tuning" else "Expand advanced tuning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation)
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
    subtitle: String? = null,
    onReset: (() -> Unit)? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val isValueSet = value != null
    val sliderValue = value ?: ((range.start + range.endInclusive) / 2f)

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isValueSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isValueSet && value != null) {
                    Text(
                        text = String.format("%.2f", value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (onReset != null) {
                        IconButton(
                            onClick = onReset,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear $text",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(2.dp))

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
                value = sliderValue,
                onValueChange = onValueChange,
                valueRange = range,
                modifier = Modifier.weight(1f),
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
                        interactionSource = remember { MutableInteractionSource() },
                        modifier = Modifier.alpha(if (isValueSet) 1f else 0.35f),
                        colors = if (isValueSet) SliderDefaults.colors() else SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.outlineVariant)
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
                                activeTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
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