package cc.tomko.outify.ui.components.bottomsheet.playback

import cc.tomko.outify.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ui.components.AudioBarsIndicator
import cc.tomko.outify.ui.viewmodel.bottomsheet.PlaybackDevicesViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackDevicesBottomSheet(
    viewModel: PlaybackDevicesViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    LaunchedEffect(viewModel) {
        viewModel.observeDevices()
    }

    val scope = rememberCoroutineScope()

    val deviceState by viewModel.deviceState.collectAsState()
    val devices = deviceState.devices
    val playback by viewModel.playback.collectAsState()
    val controlError by viewModel.controlError.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
            .fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = stringResource(R.string.cd_devices),
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                        .size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.devices_title),
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        fontWeight = FontWeight.Black,
                    )

                    Text(
                        text = stringResource(R.string.count_devices, devices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            viewModel.makeActive(deviceState.lease)
                        }
                    },
                    enabled = deviceState.available && deviceState.localDeviceId != null && deviceState.transferringDeviceId == null,
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.cd_transfer_playback))
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            viewModel.loadDevices()
                        }
                    },
                    enabled = deviceState.available && !deviceState.isRefreshing,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh_devices))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (deviceState.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            deviceState.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            playback?.takeIf { it.isRemote && it.volume != null && "volume" in it.allowed }?.let { remote ->
                key(remote.target) {
                    var volume by remember { mutableFloatStateOf(remote.volume!!.toFloat()) }
                    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
                    LaunchedEffect(remote.volume, dragging) { if (!dragging) volume = remote.volume!!.toFloat() }
                    Text(stringResource(R.string.devices_remote_volume, remote.deviceName), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = volume, valueRange = 0f..65535f,
                        onValueChange = { dragging = true; volume = it },
                        onValueChangeFinished = {
                            dragging = false
                            viewModel.setRemoteVolume(remote.target, volume.toInt())
                        },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (devices.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.SearchOff, contentDescription = null)

                                Text(
                                    text = stringResource(when {
                                        !deviceState.available -> R.string.devices_playback_not_ready
                                        deviceState.isRefreshing -> R.string.devices_loading
                                        else -> R.string.devices_none
                                    }),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(devices, key = { it.id ?: it.name }) {
                    val icon = when (it.type) {
                        "Computer" -> Icons.Default.Laptop
                        "Smartphone" -> Icons.Default.Smartphone
                        "Speaker" -> Icons.Default.Speaker
                        else -> Icons.Default.DeviceUnknown
                    }

                    val isSelected = it.isActive

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                enabled = deviceState.available && deviceState.transferringDeviceId == null && !it.isRestricted && !it.isActive,
                            ) { viewModel.selectDevice(it, deviceState.lease) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = it.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (it.isRestricted) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = stringResource(R.string.common_restricted),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.common_restricted),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (it.isPrivateSession) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Security,
                                                contentDescription = stringResource(R.string.common_private),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.common_private),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                if (deviceState.transferringDeviceId == it.id) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (isSelected) {
                                    AudioBarsIndicator(
                                        isPlaying = deviceState.isPlaying,
                                        barCount = 4,
                                        barWidth = 3.dp,
                                        barHeight = 12.dp,
                                        spacing = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
