package cc.tomko.outify.ui.screens

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.navigation.Route
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.HomeUiState
import cc.tomko.outify.ui.viewmodel.HomeViewModel
import cc.tomko.outify.ui.viewmodel.TopArtist
import cc.tomko.outify.ui.viewmodel.TopItemsDuration

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.HomeScreen(
    backStack: NavBackStack<NavKey>,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val username by viewModel.username.collectAsState(initial = "User")
    val userAvatarUrl by viewModel.userImageUrl.collectAsState(initial = null)
    val selectedDuration by viewModel.selectedDuration.collectAsState(initial = TopItemsDuration.SHORT_TERM)
    val isPlaybackLoggedIn by viewModel.isPlaybackLoggedIn.collectAsState(initial = false)
    val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
    val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var durationExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshPlaybackLoginState()
    }

    Scaffold(
        modifier = modifier,
    ) { innerPaddings ->
        val state = uiState
        if (state is HomeUiState.Error) {
            ErrorScreen(
                message = state.message,
                onRetry = { viewModel.retry() },
                modifier = Modifier.padding(top = innerPaddings.calculateTopPadding()),
            )
        } else {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPaddings.calculateTopPadding()),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        HeaderSection(
                            username = username,
                            userAvatarUrl = userAvatarUrl,
                            isPlaybackLoggedIn = isPlaybackLoggedIn,
                            selectedDuration = selectedDuration,
                            onDurationChange = { viewModel.setDuration(it) },
                            onSettingsClick = { backStack.add(Route.SettingsScreen) },
                            onAccountClick = { backStack.add(Route.AccountsScreen) },
                            durationExpanded = durationExpanded,
                            onDurationExpandedChange = { durationExpanded = it }
                        )
                    }

                    when (state) {
                        is HomeUiState.Loading -> {
                            item {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    text = "Top Artists",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                            item { SkeletonArtistRow() }
                            item {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Top Tracks",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                            items(10) { SwipeableTrackRowConfigured(track = null) }
                        }

                        is HomeUiState.NotAuthenticated -> {
                            item { Spacer(Modifier.height(32.dp)) }
                            item {
                                ConnectSpotifyCard(
                                    onConnectClick = { backStack.add(Route.AccountsScreen) }
                                )
                            }
                        }

                        is HomeUiState.EmptyResult -> {
                            item { Spacer(Modifier.height(32.dp)) }
                            item {
                                EmptyResultCard()
                            }
                        }

                        is HomeUiState.Success -> {
                            item {
                                Spacer(Modifier.height(24.dp))
                                Text(
                                    text = "Top Artists",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                            if (state.topArtists.isNotEmpty()) {
                                item {
                                    LazyRow(
                                        contentPadding = PaddingValues(
                                            horizontal = 24.dp,
                                            vertical = 12.dp
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(state.topArtists.take(10)) { artist ->
                                            TopArtistItem(
                                                artist = artist,
                                                modifier = Modifier.clickable {
                                                    backStack.add(Route.ArtistScreen(artist.uri))
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Top Tracks",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                            if (state.topTracks.isNotEmpty()) {
                                items(state.topTracks.take(10)) { track ->
                                    SwipeableTrackRowConfigured(
                                        track,
                                        currentAudio = currentTrack,
                                        isPlaybackPlaying = isPlaybackPlaying,
                                        onRowClick = remember(track.uri) {
                                            { viewModel.loadTrack(track.toPlayableAudio()) }
                                        },
                                        onArtworkClick = {
                                            backStack.add(Route.AlbumScreen(track.album!!.uri))
                                        },
                                        onArtistClick = { artist ->
                                            backStack.add(Route.ArtistScreen(artist.uri))
                                        },
                                        trailingContent = {},
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }

                        is HomeUiState.Error -> {
                            // handled above
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeaderSection(
    username: String?,
    userAvatarUrl: String?,
    isPlaybackLoggedIn: Boolean,
    selectedDuration: TopItemsDuration,
    onDurationChange: (TopItemsDuration) -> Unit,
    onSettingsClick: () -> Unit,
    onAccountClick: () -> Unit,
    durationExpanded: Boolean,
    onDurationExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Welcome back,\n${username ?: "User"}!",
                style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = durationExpanded,
                onExpandedChange = onDurationExpandedChange
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .menuAnchor()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDurationExpandedChange(!durationExpanded) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = selectedDuration.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ExposedDropdownMenu(
                    expanded = durationExpanded,
                    onDismissRequest = { onDurationExpandedChange(false) },
                    modifier = Modifier.wrapContentWidth()
                ) {
                    TopItemsDuration.entries.forEach { duration ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = duration.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (duration == selectedDuration)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onDurationChange(duration)
                                onDurationExpandedChange(false)
                            }
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAccountClick) {
                if (userAvatarUrl != null) {
                    SmartImage(
                        url = userAvatarUrl,
                        contentDescription = "Account",
                        shape = CircleShape,
                    )
                } else {
                    Icon(
                        Icons.Default.NoAccounts,
                        contentDescription = "Account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isPlaybackLoggedIn) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Not logged in",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                        .padding(2.dp)
                )
            }

            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
        }
    }
}

@Composable
private fun EmptyResultCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No top data available",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "API request returned nothing",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectSpotifyCard(
    onConnectClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Connect to Spotify",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Link your Spotify account to see your top artists and tracks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onConnectClick,
                shape = RoundedCornerShape(50),
            ) {
                Text("Connect Spotify")
            }
        }
    }
}

@Composable
private fun TopArtistItem(artist: TopArtist, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(100.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (artist.imageUrl != null) {
                SmartImage(
                    url = artist.imageUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = artist.name.take(1),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SkeletonArtistRow() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(10) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(100.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                ) {}

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                ) {}
            }
        }
    }
}