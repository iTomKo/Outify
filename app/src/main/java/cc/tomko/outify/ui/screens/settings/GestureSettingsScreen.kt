package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.setting.DisplayIcon
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.getDisplayName
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.components.bottomsheet.GestureCustomizeBottomSheet
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.settings.GestureSettingViewModel

@Composable
fun SharedTransitionScope.GestureSettingsScreen(
    viewModel: GestureSettingViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gestures by viewModel.gestures.collectAsState()
    val flipQueueGestures by viewModel.flipQueueGestures.collectAsState()
    val swipeEnabled by viewModel.swipeEnabled.collectAsState()

    var customizeGesture by remember { mutableStateOf<GestureSetting?>(null) }
    var customizeGestureIndex by remember { mutableStateOf<Int?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.common_gestures),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_flip_queue)) },
                        description = stringResource(R.string.settings_gesture_row_desc),
                        icon = { Icon(Icons.Default.Flip, contentDescription = null) },
                        onCheckedChange = { viewModel.setFlipQueueGestures(it) },
                        isChecked = flipQueueGestures
                    )
                }
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_enable_swipes)) },
                        description = stringResource(R.string.settings_gesture_quick_action_desc),
                        icon = { Icon(Icons.Default.Gesture, contentDescription = null) },
                        onCheckedChange = { viewModel.setGesturesEnabled(it) },
                        isChecked = swipeEnabled
                    )
                }
            }
        }

        if (gestures.isNotEmpty()) {
            item {
                SettingsSection {
                    gestures.forEachIndexed { index, gesture ->
                        entry {
                            val actionLabel = stringResource(gesture.action.getDisplayName())
                            val triggerLabel = stringResource(gesture.trigger.getDisplayName())
                            val directionLabel = gesture.side?.let { stringResource(it.getDisplayName()) }
                            val description = if (gesture.enabled) {
                                listOfNotNull(triggerLabel, directionLabel)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" • ")
                            } else {
                                stringResource(R.string.common_disabled)
                            }

                            PreferenceEntry(
                                title = { Text(actionLabel) },
                                description = description,
                                icon = { gesture.action.DisplayIcon(Modifier.size(20.dp)) },
                                onClick = {
                                    customizeGesture = gesture
                                    customizeGestureIndex = index
                                },
                                trailingContent = {
                                    if (!gesture.enabled) {
                                        Text(
                                            text = stringResource(R.string.common_off),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { viewModel.removeGesture(index) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.common_delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (swipeEnabled) {
            item {
                SettingsSection {
                    entry {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.gesture_add)) },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = { viewModel.addGesture() }
                        )
                    }
                    entry {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.common_reset_to_defaults)) },
                            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                            onClick = { viewModel.resetToDefaults() }
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.gesture_try),
                icon = Icons.Default.TouchApp
            ) {
                entry {
                    SwipeableTrackRowConfigured(track = Track.dummy())
                }
            }
        }
    }

    customizeGesture?.let { gesture ->
        GestureCustomizeBottomSheet(
            gesture = gesture,
            onDismiss = {
                // Saving
                customizeGestureIndex?.let { index -> viewModel.updateGestureAt(index, it) }
                customizeGesture = null
                customizeGestureIndex = null
            },
        )
    }
}