package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.NavigationPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.ScreenshotAvatar
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.bottomsheet.account.AccountDetailBottomSheet
import cc.tomko.outify.ui.components.user.AccountAvatar
import cc.tomko.outify.ui.components.user.StatusPill
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
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkAuthState()
    }

    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val profilePicture by viewModel.profilePicture.collectAsStateWithLifecycle()
    var showPlaybackSheet by remember { mutableStateOf(false) }

    val screenshotMode = LocalUiSettings.current.screenshotMode

    if (showPlaybackSheet) {
        AccountDetailBottomSheet(
            title = stringResource(R.string.common_playback_login),
            description = stringResource(R.string.settings_playback_login_desc),
            isLoggedIn = isLoggedIn,
            username = username,
            userImageUrl = profilePicture,
            onLogout = { viewModel.logoutPlayback() },
            onDismiss = { showPlaybackSheet = false }
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.common_settings),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection {
                entry {
                    AccountEntry(
                        isLoggedIn = isLoggedIn,
                        username = if (isLoggedIn && screenshotMode) "Developer" else username,
                        profilePicture = profilePicture,
                        screenshotMode = screenshotMode,
                        onClick = {
                            if (isLoggedIn) showPlaybackSheet = true
                            else viewModel.startSpircAuth(context)
                        }
                    )
                }
            }
        }

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
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountEntry(
    isLoggedIn: Boolean,
    username: String?,
    screenshotMode: Boolean,
    profilePicture: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        AccountAvatar(
            isLoggedIn = isLoggedIn,
            name = username,
            imageUrl = profilePicture,
            screenshotMode = screenshotMode,
            size = 64.dp
        )

        // Name + status
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (isLoggedIn && !username.isNullOrBlank()) username
                else stringResource(R.string.common_playback_login),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isLoggedIn) {
                StatusPill(text = stringResource(R.string.common_logged_in))
            } else {
                Text(
                    text = stringResource(R.string.settings_librespot_creds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Trailing action
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}