package cc.tomko.outify.ui.screens.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.bottomsheet.AccountDetailBottomSheet
import cc.tomko.outify.ui.viewmodel.settings.AccountsViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
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
            title = "Playback login",
            description = "This login is mandatory to allow for playback. It uses fake Spotify credentials to stream audio.",
            isLoggedIn = isPlaybackLoggedIn,
            onLogout = { viewModel.logoutPlayback() },
            onDismiss = { showPlaybackSheet = false }
        )
    }

    if (showAccountSheet) {
        AccountDetailBottomSheet(
            title = "Account login",
            description = "This login allows for manipulation of your Spotify account: liking tracks, creating playlists, accessing recommendations, and managing your library.",
            isLoggedIn = isAccountLoggedIn,
            username = username,
            userImageUrl = userImageUrl,
            onLogout = { viewModel.logoutAccount() },
            onDismiss = { showAccountSheet = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AnimatedVisibility(
                    visible = !isPremium || !isAccountLoggedIn
                ) {

                    Surface(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/librespot-org/librespot#librespot".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Spotify Premium required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Outify only works with a Spotify Premium account. Tap to learn why.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Why two logins?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Outify uses librespot to stream audio. librespot authenticates with Spotify's streaming protocol using anonymous credentials — it operates independently of your Spotify account and cannot access your personal data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Your Spotify account login is separate and handled via OAuth. It grants access to your library, playlists, and social features — but is not involved in audio streaming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier.fillMaxWidth(),
                ) {
                    PreferenceEntry(
                        title = { Text("Playback login") },
                        description = "librespot · anonymous streaming credentials",
                        icon = {
                            if (isPlaybackLoggedIn) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            }
                        },
                        trailingContent = {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape
                                    )
                            ) {
                                Text(
                                    text = "0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        },
                        onClick = {
                            if (isPlaybackLoggedIn) {
                                showPlaybackSheet = true
                            } else {
                                viewModel.startSpircAuth(context)
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Required for audio streaming.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Uses anonymous credentials embedded in librespot to authenticate with Spotify's streaming protocol. Not linked to your personal Spotify account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAccountLoggedIn) {
                                    showAccountSheet = true
                                } else {
                                    viewModel.startAccountAuth(context)
                                }
                            }
                    ) {
                        if (isAccountLoggedIn) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (userImageUrl != null) {
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        SmartImage(
                                            url = userImageUrl,
                                            contentDescription = "Profile picture",
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = username ?: "Account",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isPremium) "Logged in" else "Logged in (Free)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Logged in",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = CircleShape
                                        )
                                ) {
                                    Text(
                                        text = "1",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }

                            if (!isPremium) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Spotify Premium required for playback",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else {
                            PreferenceEntry(
                                title = { Text("Account login") },
                                description = "Your Spotify account · OAuth",
                                icon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Login,
                                        contentDescription = null
                                    )
                                },
                                trailingContent = {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape
                                            )
                                    ) {
                                        Text(
                                            text = "1",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                },
                                onClick = { viewModel.startAccountAuth(context) },
                            )

                            Spacer(Modifier.height(12.dp))

                            Column(
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Your real Spotify account, connected via OAuth.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )

                                Spacer(Modifier.height(8.dp))

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FeatureItem("Liking and unliking tracks")
                                    FeatureItem("Creating and modifying playlists")
                                    FeatureItem("Accessing your recommendations")
                                    FeatureItem("Managing your library")
                                }
                            }
                        }
                    }
                }
            }

            item {
                PreferenceHeader("Feature availability")
            }

            item {
                ElevatedCard(
                    modifier = modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Features available based on your login status:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(16.dp))

                        FeatureAvailability(
                            "Stream tracks from Outify",
                            isPlaybackLoggedIn && isPremium,
                            0
                        )
                        FeatureAvailability(
                            "Sync your liked tracks and playlists",
                            isPlaybackLoggedIn && isPremium,
                            0
                        )
                        FeatureAvailability(
                            "View artists, albums, playlists",
                            isPlaybackLoggedIn && isPremium,
                            0
                        )

                        Spacer(Modifier.height(12.dp))

                        FeatureAvailability("Search Spotify", isAccountLoggedIn, 1)
                        FeatureAvailability(
                            "Modify playlists",
                            isAccountLoggedIn && scopes.containsAll(
                                listOf(
                                    "playlist-modify-public",
                                    "playlist-modify-private"
                                )
                            ),
                            1
                        )
                        FeatureAvailability(
                            "Create playlists",
                            isAccountLoggedIn && scopes.containsAll(
                                listOf(
                                    "playlist-modify-public",
                                    "playlist-modify-private"
                                )
                            ),
                            1
                        )
                        FeatureAvailability(
                            "Liking and unliking tracks, playlists, artists, ..",
                            isAccountLoggedIn && scopes.containsAll(
                                listOf(
                                    "user-library-modify",
                                    "user-follow-modify",
                                    "playlist-modify-public"
                                )
                            ),
                            1
                        )

                        FeatureAvailability(
                            "Syncing liked albums, episodes, ..",
                            isAccountLoggedIn && scopes.containsAll(
                                listOf(
                                    "user-library-read",
                                )
                            ),
                            1
                        )

                        FeatureAvailability(
                            "Navigating from episode to show",
                            isAccountLoggedIn,
                            1
                        )
                        FeatureAvailability("Viewing user profiles", isPlaybackLoggedIn, 0)

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "Available scopes:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = scopes.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(start = 4.dp)
    ) {
        Surface(
            modifier = Modifier.size(6.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        ) {}
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureAvailability(text: String, available: Boolean, badgeNumber: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = if (available) "Available" else "Unavailable",
            tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(
                alpha = 0.7f
            ),
            modifier = Modifier.size(22.dp)
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = if (available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape
                )
        ) {
            Text(
                text = badgeNumber.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (available) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}