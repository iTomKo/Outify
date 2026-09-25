package cc.tomko.outify.ui.screens.settings

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.setting.DisplayIcon
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.getDisplayName
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.components.bottomsheet.GestureCustomizeBottomSheet
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.settings.GestureSettingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.GestureSettingsScreen(
    viewModel: GestureSettingViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gestures by viewModel.gestures.collectAsState()
    val flipQueueGestures by viewModel.flipQueueGestures.collectAsState()
    val swipeEnabled by viewModel.swipeEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    var customizeGesture by remember { mutableStateOf<GestureSetting?>(null) }
    var customizeGestureIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_gestures)) },
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
            modifier = modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_flip_queue)) },
                        description = stringResource(R.string.settings_gesture_row_desc),
                        icon = { Icon(Icons.Default.Flip, contentDescription = null) },
                        onCheckedChange = { viewModel.setFlipQueueGestures(it) },
                        isChecked = flipQueueGestures
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_enable_swipes)) },
                        description = stringResource(R.string.settings_gesture_quick_action_desc),
                        icon = { Icon(Icons.Default.Gesture, contentDescription = null) },
                        onCheckedChange = { viewModel.setGesturesEnabled(it) },
                        isChecked = swipeEnabled
                    )
                }
            }

            // gestures list
            itemsIndexed(gestures) { index, gesture ->
                val triggerLabel = stringResource(gesture.trigger.getDisplayName())
                val actionLabel = stringResource(gesture.action.getDisplayName())
                val directionLabel = gesture.side?.let { stringResource(it.getDisplayName()) } ?: ""

                PreferenceEntry(
                    title = { Text(actionLabel) },
                    description = if (gesture.enabled) "$triggerLabel • $directionLabel" else stringResource(R.string.common_disabled),
                    icon = {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) { gesture.action.DisplayIcon(Modifier.size(20.dp)) }
                    },
                    onClick = {
                        customizeGesture = gesture
                        customizeGestureIndex = index
                    },
                    trailingContent = {
                        if (!gesture.enabled) {
                            Text(
                                stringResource(R.string.common_off),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeGesture(index) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }

            if (swipeEnabled) {
                item {
                    ElevatedCard(
                        modifier = modifier
                            .fillMaxWidth()
                    ) {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.gesture_add)) },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                viewModel.addGesture()
                            }
                        )

                        PreferenceEntry(
                            title = { Text(stringResource(R.string.common_reset_to_defaults)) },
                            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                            onClick = {
                                viewModel.resetToDefaults()
                            }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.gesture_try)
                )

                SwipeableTrackRowConfigured(
                    track = Track.dummy()
                )
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }

    if (customizeGesture != null) {
        GestureCustomizeBottomSheet(
            gesture = customizeGesture!!,
            onDismiss = {
                // Saving
                viewModel.updateGestureAt(customizeGestureIndex!!, it)
                customizeGesture = null
                customizeGestureIndex = null
            },
        )
    }
}
