package cc.tomko.outify.ui.screens.settings

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.bottomsheet.AccountDetailBottomSheet
import cc.tomko.outify.ui.viewmodel.settings.AccountsViewModel

private const val LIBRESPOT_URL = "https://github.com/librespot-org/librespot#librespot"

private data class Feature(val text: String, val available: Boolean, val badge: Int)

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkAuthState()
    }

    val isPlaybackLoggedIn by viewModel.isPlaybackLoggedIn.collectAsStateWithLifecycle()
    val isAccountLoggedIn by viewModel.isAccountLoggedIn.collectAsStateWithLifecycle()
    val scopes by viewModel.scopes.collectAsStateWithLifecycle()

    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val userImageUrl by viewModel.userImageUrl.collectAsStateWithLifecycle()

    var showPlaybackSheet by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }

    if (showPlaybackSheet) {
        AccountDetailBottomSheet(
            title = stringResource(R.string.common_playback_login),
            description = stringResource(R.string.settings_playback_login_desc),
            isLoggedIn = isPlaybackLoggedIn,
            onLogout = { viewModel.logoutPlayback() },
            onDismiss = { showPlaybackSheet = false }
        )
    }

    if (showAccountSheet) {
        AccountDetailBottomSheet(
            title = stringResource(R.string.common_account_login),
            description = stringResource(R.string.settings_account_login_desc),
            isLoggedIn = isAccountLoggedIn,
            username = username,
            userImageUrl = userImageUrl,
            onLogout = { viewModel.logoutAccount() },
            onDismiss = { showAccountSheet = false }
        )
    }

    val canModifyPlaylists = isAccountLoggedIn && scopes.containsAll(
        listOf("playlist-modify-public", "playlist-modify-private")
    )
    val canLikeAndFollow = isAccountLoggedIn && scopes.containsAll(
        listOf("user-library-modify", "user-follow-modify", "playlist-modify-public")
    )
    val canReadLibrary = isAccountLoggedIn && scopes.containsAll(listOf("user-library-read"))
    val playbackFeatures = isPlaybackLoggedIn && isPremium

    val features = listOf(
        Feature("Stream tracks from Outify", playbackFeatures, 0),
        Feature("Sync your liked tracks and playlists", playbackFeatures, 0),
        Feature("View artists, albums, playlists", playbackFeatures, 0),
        Feature("Viewing user profiles", isPlaybackLoggedIn, 0),
        Feature("Search Spotify", isAccountLoggedIn, 1),
        Feature("Modify playlists", canModifyPlaylists, 1),
        Feature("Create playlists", canModifyPlaylists, 1),
        Feature("Liking and unliking tracks, playlists, artists, ..", canLikeAndFollow, 1),
        Feature("Syncing liked albums, episodes, ..", canReadLibrary, 1),
        Feature("Navigating from episode to show", isAccountLoggedIn, 1),
    )

    SettingsScaffold(
        title = stringResource(R.string.common_accounts),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        if (!isPremium || !isAccountLoggedIn) {
            item(key = "premium_banner") {
                PremiumBanner(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, LIBRESPOT_URL.toUri()))
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.onb_why_two),
                icon = Icons.Default.Info
            ) {
                entry { BodyEntry(stringResource(R.string.onb_librespot_desc)) }
                entry { BodyEntry(stringResource(R.string.onb_oauth_desc)) }
            }
        }

        item {
            SettingsSection {
                if (isAccountLoggedIn) {
                    entry {
                        AccountProfileRow(
                            username = username,
                            userImageUrl = userImageUrl,
                            isPremium = isPremium,
                            onClick = { showAccountSheet = true }
                        )
                    }
                    if (!isPremium) {
                        entry { PremiumWarningRow() }
                    }
                } else {
                    entry {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.common_account_login)) },
                            description = stringResource(R.string.settings_oauth_creds),
                            icon = {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            },
                            trailingContent = { NumberBadge(1, highlighted = true) },
                            onClick = { viewModel.startAccountAuth(context) },
                        )
                    }
                    entry {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.onb_real_account),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FeatureItem("Liking and unliking tracks")
                            FeatureItem("Creating and modifying playlists")
                            FeatureItem("Accessing your recommendations")
                            FeatureItem("Managing your library")
                        }
                    }
                }
            }
        }

        item {
            SettingsSection {
                entry {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_playback_login)) },
                        description = stringResource(R.string.settings_librespot_creds),
                        icon = {
                            Icon(
                                imageVector = if (isPlaybackLoggedIn) Icons.Default.CheckCircle
                                else Icons.AutoMirrored.Filled.Login,
                                contentDescription = null
                            )
                        },
                        trailingContent = { NumberBadge(0, highlighted = true) },
                        onClick = {
                            if (isPlaybackLoggedIn) showPlaybackSheet = true
                            else viewModel.startSpircAuth(context)
                        },
                    )
                }
                entry {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.onb_required_streaming),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.onb_anon_creds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.section_feature_availability),
                icon = Icons.Default.Checklist
            ) {
                entry {
                    BodyEntry(stringResource(R.string.onb_features_by_login))
                }
                features.forEach { feature ->
                    entry { FeatureRow(feature) }
                }
                entry {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.onb_available_scopes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = scopes.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PremiumBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape())
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onb_premium_required),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.onb_premium_learn),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun BodyEntry(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

@Composable
private fun AccountProfileRow(
    username: String?,
    userImageUrl: String?,
    isPremium: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            if (userImageUrl != null) {
                SmartImage(
                    url = userImageUrl,
                    contentDescription = stringResource(R.string.common_profile_picture),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = username ?: stringResource(R.string.common_account),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    if (isPremium) R.string.common_logged_in else R.string.account_logged_in_free
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPremium) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.common_logged_in),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        NumberBadge(1, highlighted = true)
    }
}

@Composable
private fun PremiumWarningRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = stringResource(R.string.settings_premium_playback),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureRow(feature: Feature) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = if (feature.available) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = stringResource(
                if (feature.available) R.string.state_available else R.string.state_unavailable
            ),
            tint = if (feature.available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = feature.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (feature.available) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        NumberBadge(feature.badge, highlighted = feature.available)
    }
}

@Composable
private fun NumberBadge(number: Int, highlighted: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .background(
                color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape
            )
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}