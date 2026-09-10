package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.tomko.outify.data.repository.PlaybackSettings
import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.playback.model.getName
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                PreferenceHeader("Audio Settings")

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        DropdownPreferenceEntry(
                            title = { Text("Bitrate (Quality)") },
                            description = "Choose your preferred streaming quality",
                            icon = { Icon(Icons.Default.HighQuality, contentDescription = null) },
                            options = listOf(
                                DropdownOption(
                                    Bitrate.KBPS320,
                                    "320Kbps, ${Bitrate.KBPS320.getName()}"
                                ),
                                DropdownOption(
                                    Bitrate.KBPS160,
                                    "160Kbps, ${Bitrate.KBPS160.getName()}"
                                ),
                                DropdownOption(
                                    Bitrate.KBPS96,
                                    "96Kbps, ${Bitrate.KBPS96.getName()}"
                                ),
                            ),
                            selectedValue = settings.bitrate,
                            onValueChange = { viewModel.setBitrate(it) }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Normalize audio") },
                            description = "Every track will be the same loudness",
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
                            title = { Text("Gapless playback") },
                            description = "Smooth playback without gaps",
                            icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                            onCheckedChange = { viewModel.setGaplessPlayback(it) },
                            isChecked = settings.gapless
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
                                    title = { Text("Restart Spirc") },
                                    description = "Required to apply playback related settings",
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
                PreferenceHeader("Controls & Behavior")

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    Column {
                        var ffSeconds by remember(settings.forwardMilliseconds) {
                            mutableFloatStateOf(settings.forwardMilliseconds.toFloat() / 1000f)
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Fast forward duration",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "${ffSeconds.roundToInt()} seconds",
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
                            title = { Text("Keepalive") },
                            description = "Allow resurrection from notification",
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
                PreferenceHeader("Lyrics")

                ElevatedCard(
                    modifier = modifier.fillMaxWidth()
                ) {
                    SwitchPreferenceEntry(
                        title = { Text("Romanize lyrics") },
                        description = "Show romanized text beneath original lyrics",
                        icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                        onCheckedChange = { viewModel.setRomanizeLyrics(it) },
                        isChecked = romanizeLyrics
                    )
                }
            }

            item {
                PreferenceHeader("Spotify Connection")

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
                            title = { Text("Spotify Connect name") },
                            placeholder = "Outify",
                            value = deviceName,
                            onValueChange = { deviceName = it },
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Auto transfer") },
                            description = "Make Outify the active device to stream from",
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
                        title = { Text("Advanced settings") },
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
                                title = { Text("Spotify Client Id") },
                                placeholder = "Leave empty for default",
                                value = clientIdInput,
                                onValueChange = { clientIdInput = it },
                            )

                            TextInputPreferenceEntry(
                                title = { Text("Spotify Client Secret") },
                                placeholder = "Leave empty for default",
                                value = clientSecretInput,
                                onValueChange = { clientSecretInput = it },
                            )
                        }
                    }
                }
            }
        }
    }
}