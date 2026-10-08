package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R
import cc.tomko.outify.data.repository.PlaybackSettings
import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.ui.components.DropdownOption
import cc.tomko.outify.ui.components.DropdownPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.components.TextInputPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.PlaybackSettingViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingScreen(
    viewModel: PlaybackSettingViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState(initial = PlaybackSettings.Default)
    val restartNeeded by viewModel.needsRestart.collectAsState()
    val romanizeLyrics by viewModel.romanizeLyrics.collectAsState(initial = false)
    val lyricsFallbackEnabled by viewModel.lyricsFallbackEnabled.collectAsState(initial = false)
    val savedClientId by viewModel.clientId.collectAsState(initial = null)
    val savedClientSecret by viewModel.clientSecret.collectAsState(initial = null)

    var crossfadeSecs by remember(settings.crossfadeMillis / 1_000) {
        mutableFloatStateOf(settings.crossfadeMillis.toFloat() / 1000f)
    }
    var ffSeconds by remember(settings.forwardMilliseconds) {
        mutableFloatStateOf(settings.forwardMilliseconds.toFloat() / 1000f)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (advancedExpanded) 180f else 0f,
        label = "advanced_arrow"
    )
    val restartHighlight by animateColorAsState(
        targetValue = if (restartNeeded) MaterialTheme.colorScheme.tertiaryContainer
        else Color.Transparent,
        label = "restart_highlight"
    )

    SettingsScaffold(
        title = stringResource(R.string.common_playback),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.section_audio_settings),
                icon = Icons.Default.Headphones
            ) {
                entry {
                    DropdownPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_bitrate)) },
                        description = stringResource(R.string.settings_bitrate_desc),
                        icon = { Icon(Icons.Default.HighQuality, contentDescription = null) },
                        options = listOf(
                            DropdownOption(
                                Bitrate.KBPS320,
                                stringResource(
                                    R.string.settings_bitrate_value, 320,
                                    stringResource(R.string.bitrate_very_high)
                                )
                            ),
                            DropdownOption(
                                Bitrate.KBPS160,
                                stringResource(
                                    R.string.settings_bitrate_value, 160,
                                    stringResource(R.string.bitrate_high)
                                )
                            ),
                            DropdownOption(
                                Bitrate.KBPS96,
                                stringResource(
                                    R.string.settings_bitrate_value, 96,
                                    stringResource(R.string.bitrate_normal)
                                )
                            ),
                        ),
                        selectedValue = settings.bitrate,
                        onValueChange = { viewModel.setBitrate(it) }
                    )
                }

                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_normalize)) },
                        description = stringResource(R.string.settings_normalize_desc),
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null) },
                        onCheckedChange = { viewModel.setNormalizeAudio(it) },
                        isChecked = settings.normalizeAudio
                    )
                }

                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_gapless)) },
                        description = stringResource(R.string.settings_gapless_desc),
                        icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                        onCheckedChange = { viewModel.setGaplessPlayback(it) },
                        isChecked = settings.gapless
                    )
                }

                entry {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_crossfade)) },
                        description = stringResource(R.string.settings_crossfade_desc),
                        icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                        content = {
                            Text(
                                text = stringResource(R.string.player_speed_seconds, crossfadeSecs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            val sliderState = rememberSliderState(
                                value = crossfadeSecs,
                                steps = 59,
                                trackRange = 0f..30f
                            )

                            Slider(
                                state = sliderState,
                                onValueChangeFinished = {
                                    crossfadeSecs = sliderState.value
                                    viewModel.setCrossfade((sliderState.value * 1000).toInt())
                                }
                            )
                        },
                    )
                }

                entry {
                    // Tinted while a restart is pending; the Surface clips it to the segment shape
                    Box(modifier = Modifier.background(restartHighlight)) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.settings_restart_spirc)) },
                            description = stringResource(R.string.settings_restart_spirc_desc),
                            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                            onClick = { viewModel.restartSpirc() },
                            trailingContent = {
                                AnimatedVisibility(
                                    visible = restartNeeded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                                        Text("!")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.section_controls_behavior),
                icon = Icons.Default.Tune
            ) {
                entry {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_fast_forward_desc)) },
                        description = stringResource(R.string.settings_ff_seconds, ffSeconds.roundToInt()),
                        icon = { Icon(Icons.Default.FastForward, contentDescription = null) },
                        content = {
                            Slider(
                                value = ffSeconds,
                                onValueChange = { ffSeconds = it },
                                onValueChangeFinished = {
                                    viewModel.setFastForwardMs((ffSeconds * 1000).toLong())
                                },
                                valueRange = 0f..90f,
                                steps = 17
                            )
                        }
                    )
                }

                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_keepalive)) },
                        description = stringResource(R.string.settings_resurrect_desc),
                        icon = { Icon(Icons.Default.Healing, contentDescription = null) },
                        onCheckedChange = { viewModel.setKeepAlive(it) },
                        isChecked = settings.keepalive
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.common_lyrics),
                icon = Icons.Default.Lyrics
            ) {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_romanize)) },
                        description = stringResource(R.string.settings_romanize_desc),
                        icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                        onCheckedChange = { viewModel.setRomanizeLyrics(it) },
                        isChecked = romanizeLyrics
                    )
                }
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_lyrics_fallback)) },
                        description = stringResource(R.string.settings_lyrics_fallback_desc),
                        icon = { Icon(Icons.Default.Lyrics, contentDescription = null) },
                        onCheckedChange = { viewModel.setLyricsFallbackEnabled(it) },
                        isChecked = lyricsFallbackEnabled
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.section_spotify_connection),
                icon = Icons.Default.Cast
            ) {
                entry {
                    DebouncedTextEntry(
                        title = stringResource(R.string.settings_connect_name),
                        placeholder = "Outify",
                        icon = Icons.Default.Smartphone,
                        saved = settings.deviceName,
                        normalize = { it.ifBlank { "Outify" } },
                        onCommit = { viewModel.setDeviceName(it) }
                    )
                }
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_auto_transfer)) },
                        description = stringResource(R.string.settings_connect_desc),
                        icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                        onCheckedChange = { viewModel.setAutoTransfer(it) },
                        isChecked = settings.autoTransfer
                    )
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_advanced)) },
                        icon = { Icon(Icons.Default.Build, contentDescription = null) },
                        onClick = { advancedExpanded = !advancedExpanded },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(arrowRotation)
                            )
                        }
                    )
                }

                if (advancedExpanded) {
                    entry {
                        DebouncedTextEntry(
                            title = stringResource(R.string.settings_client_id),
                            placeholder = stringResource(R.string.placeholder_leave_empty),
                            icon = Icons.Default.Key,
                            saved = savedClientId ?: "",
                            onCommit = { viewModel.setClientId(it) }
                        )
                    }
                    entry {
                        DebouncedTextEntry(
                            title = stringResource(R.string.settings_client_secret),
                            placeholder = stringResource(R.string.placeholder_leave_empty),
                            icon = Icons.Default.Lock,
                            saved = savedClientSecret ?: "",
                            onCommit = { viewModel.setClientSecret(it) }
                        )
                    }
                }
            }
        }
    }
}

/** Text field that commits to the view model 500 ms after the user stops typing. */
@Composable
private fun DebouncedTextEntry(
    title: String,
    placeholder: String,
    icon: ImageVector,
    saved: String,
    onCommit: (String) -> Unit,
    normalize: (String) -> String = { it },
) {
    var input by remember(saved) { mutableStateOf(saved) }

    LaunchedEffect(input) {
        delay(500)
        val final = normalize(input)
        if (final != saved) onCommit(final)
    }

    TextInputPreferenceEntry(
        title = { Text(title) },
        placeholder = placeholder,
        value = input,
        onValueChange = { input = it },
        icon = { Icon(icon, contentDescription = null) },
    )
}