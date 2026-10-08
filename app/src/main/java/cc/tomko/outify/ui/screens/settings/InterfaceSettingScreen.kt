package cc.tomko.outify.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.ui.components.NavigationPreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.InterfaceViewModel

@Composable
fun InterfaceSettingScreen(
    viewModel: InterfaceViewModel,
    onNavigateBack: () -> Unit,
    openGestureSettings: () -> Unit,
    openAppearanceSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState(initial = InterfaceSettings())
    val showNavbarHistory = settings.showNavbarHistory
    val showNavbarHistoryOnEnd = settings.navbarHistoryOnEnd
    val showSystemNavigationPadding = settings.showSystemNavigationPadding

    SettingsScaffold(
        title = stringResource(R.string.common_interface),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection {
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_gestures)) },
                        description = stringResource(R.string.settings_gestures_desc),
                        icon = { Icon(Icons.Default.Gesture, contentDescription = null) },
                        onClick = openGestureSettings,
                    )
                }
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_appearance)) },
                        description = stringResource(R.string.settings_appearance_desc),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        onClick = openAppearanceSettings,
                    )
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_nav_history)) },
                        description = stringResource(R.string.settings_nav_history_desc),
                        isChecked = showNavbarHistory,
                        onCheckedChange = { viewModel.setShowNavbarHistory(it) },
                        icon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                }

                if (showNavbarHistory) {
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_show_right)) },
                            description = stringResource(R.string.settings_right_side_desc),
                            isChecked = showNavbarHistoryOnEnd,
                            onCheckedChange = { viewModel.setNavbarHistoryOnEnd(it) },
                            icon = { Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = null) }
                        )
                    }
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_system_padding)) },
                        description = stringResource(R.string.settings_padding_desc),
                        isChecked = showSystemNavigationPadding,
                        onCheckedChange = { viewModel.setShowSystemNavigationPadding(it) },
                        icon = { Icon(Icons.Default.Padding, contentDescription = null) }
                    )
                }
            }
        }
    }
}