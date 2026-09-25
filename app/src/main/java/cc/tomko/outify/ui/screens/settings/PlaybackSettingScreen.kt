package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.data.repository.PlaybackSettings
import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.ui.components.DropdownOption
import cc.tomko.outify.ui.components.DropdownPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceHeader
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
    val savedClientId by viewModel.clientId.collectAsState(initial = null)
    val savedClientSecret by viewModel.clientSecret.collectAsState(initial = null)

    var crossfadeSecs by remember(settings.crossfadeMillis / 1_000) {
        mutableFloatStateOf(settings.crossfadeMillis.toFloat() / 1000f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_playback)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPaddings ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                PreferenceHeader(stringResource(R.string.section_audio_settings))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        DropdownPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_bitrate)) },
                            description = stringResource(R.string.settings_bitrate_desc),
                            icon = { Icon(Icons.Default.HighQuality, contentDescription = null) },
                            options = listOf(
                                DropdownOption(
                                    Bitrate.KBPS320,
                                    stringResource(R.string.settings_bitrate_value, 320, stringResource(R.string.bitrate_very_high))
                                ),
                                DropdownOption(
                                    Bitrate.KBPS160,
                                    stringResource(R.string.settings_bitrate_value, 160, stringResource(R.string.bitrate_high))
                                ),
                                DropdownOption(
                                    Bitrate.KBPS96,
                                    stringResource(R.string.settings_bitrate_value, 96, stringResource(R.string.bitrate_normal))
                                ),
                            ),
                            selectedValue = settings.bitrate,
                            onValueChange = { viewModel.setBitrate(it) }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_normalize)) },
                            description = stringResource(R.string.settings_normalize_desc),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeDown,
                                    contentDescription = null
                                )
                            },
                            onCheckedChange = { viewModel.setNormalizeAudio(it) },
                            isChecked = settings.normalizeAudio
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_gapless)) },
                            description = stringResource(R.string.settings_gapless_desc),
                            icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                            onCheckedChange = { viewModel.setGaplessPlayback(it) },
                            isChecked = settings.gapless
                        )

                        PreferenceEntry(
                            title = { Text(stringResource(R.string.settings_crossfade)) },
                            description = stringResource(R.string.settings_crossfade_desc),
                            icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                            content = {
                                Text(
                                    text = stringResource(R.string.player_speed_seconds, crossfadeSecs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (restartNeeded)
                                    MaterialTheme.colorScheme.tertiaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                PreferenceEntry(
                                    title = { Text(stringResource(R.string.settings_restart_spirc)) },
                                    description = stringResource(R.string.settings_restart_spirc_desc),
                                    icon = {
                                        Icon(
                                            Icons.Default.RestartAlt,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        viewModel.restartSpirc()
                                    },
                                    trailingContent = {
                                        AnimatedVisibility(
                                            visible = restartNeeded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.tertiary
                                            ) {
                                                Text("!")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.section_controls_behavior))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        var ffSeconds by remember(settings.forwardMilliseconds) {
                            mutableFloatStateOf(settings.forwardMilliseconds.toFloat() / 1000f)
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.settings_fast_forward_desc),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.settings_ff_seconds, ffSeconds.roundToInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_keepalive)) },
                            description = stringResource(R.string.settings_resurrect_desc),
                            icon = {
                                Icon(
                                    Icons.Default.Healing,
                                    contentDescription = null
                                )
                            },
                            onCheckedChange = { viewModel.setKeepAlive(it) },
                            isChecked = settings.keepalive
                        )
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.common_lyrics))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_romanize)) },
                        description = stringResource(R.string.settings_romanize_desc),
                        icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                        onCheckedChange = { viewModel.setRomanizeLyrics(it) },
                        isChecked = romanizeLyrics
                    )
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.section_spotify_connection))

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        var deviceName by remember(settings.deviceName) {
                            mutableStateOf(settings.deviceName)
                        }

                        LaunchedEffect(deviceName) {
                            delay(500)
                            val finalValue = deviceName.ifBlank { "Outify" }
                            if (finalValue != settings.deviceName) {
                                viewModel.setDeviceName(finalValue)
                            }
                        }

                        TextInputPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_connect_name)) },
                            placeholder = "Outify",
                            value = deviceName,
                            onValueChange = { deviceName = it },
                        )

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
                var advancedSettings by remember { mutableStateOf(false) }
                ElevatedCard(modifier = modifier.fillMaxWidth()) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_advanced)) },
                        onClick = { advancedSettings = !advancedSettings }
                    )

                    if (advancedSettings) {
                        Column {
                            var clientIdInput by remember(savedClientId) {
                                mutableStateOf(savedClientId ?: "")
                            }
                            var clientSecretInput by remember(savedClientSecret) {
                                mutableStateOf(savedClientSecret ?: "")
                            }

                            LaunchedEffect(clientIdInput) {
                                delay(500)
                                if (clientIdInput != (savedClientId ?: "")) {
                                    viewModel.setClientId(clientIdInput)
                                }
                            }

                            LaunchedEffect(clientSecretInput) {
                                delay(500)
                                if (clientSecretInput != (savedClientSecret ?: "")) {
                                    viewModel.setClientSecret(clientSecretInput)
                                }
                            }

                            TextInputPreferenceEntry(
                                title = { Text(stringResource(R.string.settings_client_id)) },
                                placeholder = stringResource(R.string.placeholder_leave_empty),
                                value = clientIdInput,
                                onValueChange = { clientIdInput = it },
                            )

                            TextInputPreferenceEntry(
                                title = { Text(stringResource(R.string.settings_client_secret)) },
                                placeholder = stringResource(R.string.placeholder_leave_empty),
                                value = clientSecretInput,
                                onValueChange = { clientSecretInput = it },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}