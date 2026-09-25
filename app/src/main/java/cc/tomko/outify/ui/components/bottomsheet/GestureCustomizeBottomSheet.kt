package cc.tomko.outify.ui.components.bottomsheet

import cc.tomko.outify.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.data.setting.DisplayIcon
import cc.tomko.outify.data.setting.GestureAction
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.GestureTrigger
import cc.tomko.outify.data.setting.Side
import cc.tomko.outify.data.setting.getDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureCustomizeBottomSheet(
    gesture: GestureSetting,
    onDismiss: (GestureSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabledState by remember { mutableStateOf(gesture.enabled) }
    var thresholdValue by remember { mutableFloatStateOf(gesture.thresholdFraction ?: 0.25f) }
    var triggerValue by remember { mutableStateOf(gesture.trigger) }
    var sideValue by remember { mutableStateOf(gesture.side ?: Side.End) }
    var actionValue by remember { mutableStateOf(gesture.action) }
    var actionExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            onDismiss(
                GestureSetting(
                    action = actionValue,
                    side = if (triggerValue == GestureTrigger.LongPress) null else sideValue,
                    trigger = triggerValue,
                    enabled = enabledState,
                    thresholdFraction = if (triggerValue == GestureTrigger.LongPress) null else thresholdValue,
                )
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: title + enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.gesture_customize),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (enabledState) "On" else "Off",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabledState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = enabledState,
                    onCheckedChange = { enabledState = it },
                )
            }

            if (!enabledState) {
                Text(
                    text = stringResource(R.string.gesture_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // --- Action (What happens) ---
            Text(
                text = stringResource(R.string.gesture_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Show selected action as a card, tap to open dropdown
            Card(
                onClick = { actionExpanded = !actionExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.5f
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(10.dp)
                            .size(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        actionValue.DisplayIcon(Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(actionValue.getDisplayName()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.gesture_tap_to_change),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action dropdown overlay
            if (actionExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GestureAction.entries.filter { it != GestureAction.NONE }.forEach { action ->
                        val selected = actionValue == action
                        Card(
                            onClick = {
                                actionValue = action
                                actionExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.5f
                                )
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(10.dp)
                                        .size(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    action.DisplayIcon(Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(action.getDisplayName()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Trigger (When does it trigger) ---
            Text(
                text = stringResource(R.string.gesture_trigger),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GestureTrigger.entries.forEach { trigger ->
                    val selected = triggerValue == trigger
                    Card(
                        onClick = { triggerValue = trigger },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = 0.5f
                            )
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(trigger.getDisplayName()),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // --- Swipe options (only for swipe triggers) ---
            if (triggerValue != GestureTrigger.LongPress) {
                HorizontalDivider()

                Text(
                    text = stringResource(R.string.gesture_swipe_options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Direction
                Text(
                    text = stringResource(R.string.gesture_direction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side.entries.forEach { side ->
                        val selected = sideValue == side
                        Card(
                            onClick = { sideValue = side },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.5f
                                )
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(side.getDisplayName()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Threshold slider
                Text(
                    text = stringResource(R.string.gesture_swipe_distance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Slider(
                        value = thresholdValue,
                        onValueChange = { thresholdValue = it },
                        valueRange = 0.05f..0.95f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.gesture_threshold_percent, (thresholdValue * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = stringResource(R.string.gesture_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
