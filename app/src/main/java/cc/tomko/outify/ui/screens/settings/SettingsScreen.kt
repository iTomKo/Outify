package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    openInterfaceSettings: (() -> Unit),
    openPlaybackSettings: (() -> Unit),
    openMiscSettings: (() -> Unit),
    openAboutSettings: (() -> Unit),
    openAccountSettings: (() -> Unit),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_interface)) },
                        description = stringResource(R.string.settings_interface_desc),
                        icon = { Icon(Icons.Default.Interests, contentDescription = null) },
                        onClick = openInterfaceSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_playback)) },
                        description = stringResource(R.string.settings_playback_desc),
                        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                        onClick = openPlaybackSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_misc)) },
                        description = stringResource(R.string.settings_misc_desc),
                        icon = { Icon(Icons.Default.DeveloperMode, contentDescription = null) },
                        onClick = openMiscSettings,
                    )

                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_about)) },
                        description = stringResource(R.string.settings_about_desc),
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = openAboutSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_accounts)) },
                        description = stringResource(R.string.settings_accounts_desc),
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        onClick = openAccountSettings,
                    )
                }
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}
