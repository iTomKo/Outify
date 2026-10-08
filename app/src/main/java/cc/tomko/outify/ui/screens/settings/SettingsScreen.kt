package cc.tomko.outify.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.NavigationPreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.viewmodel.settings.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    openInterfaceSettings: () -> Unit,
    openPlaybackSettings: () -> Unit,
    openMiscSettings: () -> Unit,
    openAboutSettings: () -> Unit,
    openAccountSettings: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.common_settings),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection {
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_interface)) },
                        description = stringResource(R.string.settings_interface_desc),
                        icon = { Icon(Icons.Default.Interests, contentDescription = null) },
                        onClick = openInterfaceSettings,
                    )
                }
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_playback)) },
                        description = stringResource(R.string.settings_playback_desc),
                        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                        onClick = openPlaybackSettings,
                    )
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_misc)) },
                        description = stringResource(R.string.settings_misc_desc),
                        icon = { Icon(Icons.Default.DeveloperMode, contentDescription = null) },
                        onClick = openMiscSettings,
                    )
                }
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_about)) },
                        description = stringResource(R.string.settings_about_desc),
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = openAboutSettings,
                    )
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    NavigationPreferenceEntry(
                        title = { Text(stringResource(R.string.common_accounts)) },
                        description = stringResource(R.string.settings_accounts_desc),
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        onClick = openAccountSettings,
                    )
                }
            }
        }
    }
}