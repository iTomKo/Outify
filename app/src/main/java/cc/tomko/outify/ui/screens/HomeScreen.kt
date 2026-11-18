package cc.tomko.outify.ui.screens

import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.core.home.HomeItem
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.HomeJamCard
import cc.tomko.outify.ui.components.ScreenshotAvatar
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.navigation.Route
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.HomeScreen(
    backStack: NavBackStack<NavKey>,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    onShowJam: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val username by viewModel.username.collectAsState()
    val avatar by viewModel.userImageUrl.collectAsState()
    val loggedIn by viewModel.isPlaybackLoggedIn.collectAsState()
    val current by viewModel.currentAudio.collectAsState()
    val playing by viewModel.isPlaying.collectAsState()
    val jam by viewModel.jam.collectAsState()
    val metadata by viewModel.trackMetadata.collectAsState()
    val likedIds by viewModel.likedIds.collectAsState()
    val tracks = metadata.takeIf { it.lease == state.lease }?.tracks.orEmpty()
    val open: (HomeItem) -> Unit = { item ->
        if (viewModel.isCurrent(state.lease)) {
            val route = when (item.kind) {
                "album" -> Route.AlbumScreen(item.uri)
                "playlist" -> Route.PlaylistScreen(item.uri)
                "artist" -> Route.ArtistScreen(item.uri)
                else -> Route.TrackScreen(item.uri)
            }
            backStack.add(route)
        }
    }
    val play: (HomeItem) -> Unit = { viewModel.play(it, state.lease) }
    val trackRow: @Composable (HomeItem) -> Unit = { item ->
        val track = tracks[item.uri]
        SwipeableTrackRowConfigured(
            track = track,
            currentAudio = current,
            isPlaybackPlaying = playing,
            isLiked = track?.id in likedIds,
            onRowClick = { play(item) },
            onArtworkClick = {
                if (viewModel.isCurrent(state.lease)) {
                    track?.album?.uri?.let { backStack.add(Route.AlbumScreen(it)) } ?: open(item)
                }
            },
            onArtistClick = { artist ->
                if (viewModel.isCurrent(state.lease)) backStack.add(Route.ArtistScreen(artist.uri))
            },
        )
    }

    Scaffold(modifier = modifier) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.hasContent,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = ScreenBottomPadding + 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    HomeHeader(username, avatar, { backStack.add(Route.SettingsScreen) }, { backStack.add(Route.SettingsScreen) })
                }
                item(key = "jam") {
                    HomeJamCard(jam = jam, onClick = onShowJam, modifier = Modifier.padding(horizontal = 24.dp))
                }
                when {
                    state.lease == null -> item(key = "login") {
                        HomeMessage(
                            title = stringResource(if (loggedIn) R.string.home_connecting else R.string.connect_to_spotify),
                            message = stringResource(if (loggedIn) R.string.home_connecting_description else R.string.home_playback_login_description),
                            action = stringResource(if (loggedIn) R.string.common_retry else R.string.connect_to_spotify),
                            onClick = { if (loggedIn) viewModel.retry() else backStack.add(Route.SettingsScreen) },
                        )
                    }
                    else -> {
                        if (state.error != null || state.partial) item(key = "warning") {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (state.hasContent) R.string.home_partial_feed else R.string.home_feed_error),
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.common_retry)) }
                                }
                            }
                        }
                        item(key = "quick_title") { SectionTitle(stringResource(R.string.home_quick_picks)) }
                        if (state.quickPicks.isNotEmpty()) item(key = "quick_picks") {
                            // Horizontal scrolling would intercept the configured left/right swipe actions.
                            Column {
                                state.quickPicks.forEach { item ->
                                    key(item.uri) {
                                        if (item.kind == "track") trackRow(item)
                                        else Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                                            QuickPick(item, playing && current?.uri == item.uri, { play(item) }, { open(item) })
                                        }
                                    }
                                }
                            }
                        } else if (state.refreshing) item(key = "quick_loading") {
                            Column { repeat(3) { SwipeableTrackRowConfigured(track = null) } }
                        }
                        else item(key = "quick_empty") {
                            Text(stringResource(R.string.home_quick_picks_empty), Modifier.padding(horizontal = 24.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (state.pickedAlbums.isNotEmpty() || state.refreshing) {
                            item(key = "albums_title") { SectionTitle(stringResource(R.string.home_picked_albums)) }
                            item(key = "albums") {
                                if (state.pickedAlbums.isEmpty()) HomeSkeleton()
                                else HomeShelf(state.pickedAlbums, open, play, trackRow)
                            }
                        }
                        state.sections.forEach { section ->
                            item(key = "title-${section.id}") { SectionTitle(section.title) }
                            item(key = "shelf-${section.id}") { HomeShelf(section.items, open, play, trackRow) }
                        }
                        if (!state.hasContent && !state.refreshing && state.error == null) item(key = "empty") {
                            HomeMessage(stringResource(R.string.home_feed_empty), stringResource(R.string.home_feed_empty_description),
                                stringResource(R.string.common_retry), viewModel::refresh)
                        }
                        item(key = "source") {
                            Text(stringResource(R.string.home_source_description), Modifier.padding(horizontal = 24.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeHeader(username: String?, avatar: String?, onAccount: () -> Unit, onSettings: () -> Unit) {
    val screenshot = LocalUiSettings.current.screenshotMode
    val name = if (screenshot) stringResource(R.string.screenshot_mode_username) else username ?: stringResource(R.string.home_user_fallback)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.welcome_back, name), style = MaterialTheme.typography.headlineLargeEmphasized,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.home_discovery_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onAccount) {
            if (screenshot) ScreenshotAvatar()
            else if (avatar != null) SmartImage(avatar, Modifier.size(40.dp), shape = CircleShape,
                contentDescription = stringResource(R.string.common_account))
            else Icon(Icons.Default.NoAccounts, stringResource(R.string.common_account))
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.common_settings)) }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun QuickPick(item: HomeItem, playing: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (playing) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeArtwork(item, Modifier.size(60.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(item.subtitle.ifBlank { homeKind(item.kind) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreHoriz, stringResource(R.string.home_open_item, item.title))
            }
        }
    }
}

@Composable
private fun HomeShelf(
    items: List<HomeItem>,
    onOpen: (HomeItem) -> Unit,
    onPlay: (HomeItem) -> Unit,
    trackRow: @Composable (HomeItem) -> Unit,
) {
    Column {
        val cards = items.filter { it.kind != "track" }
        if (cards.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(cards, key = { it.uri }) { item ->
                // Clip artwork only: a rounded clip around the column cuts into its bottom labels.
                Column(Modifier.width(156.dp).clickable { onOpen(item) }) {
                    Box {
                        HomeArtwork(item, Modifier.size(156.dp))
                        FilledIconButton(onClick = { onPlay(item) }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp)) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.home_play_item, item.title))
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
                        Text(item.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle.ifBlank { homeKind(item.kind) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
        items.filter { it.kind == "track" }.forEach { item -> key(item.uri) { trackRow(item) } }
    }
}

@Composable
private fun HomeArtwork(item: HomeItem, modifier: Modifier) {
    val shape = if (item.kind == "artist") CircleShape else RoundedCornerShape(12.dp)
    if (item.imageUrl != null) SmartImage(item.imageUrl, modifier, contentDescription = item.title, shape = shape)
    else Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun homeKind(kind: String): String = stringResource(when (kind) {
    "album" -> R.string.home_kind_album
    "playlist" -> R.string.home_kind_playlist
    "artist" -> R.string.home_kind_artist
    else -> R.string.home_kind_track
})

@Composable
private fun HomeSkeleton() {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(4) {
            Column(Modifier.width(156.dp)) {
                Surface(Modifier.size(156.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HomeMessage(title: String, message: String, action: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick) { Text(action) }
        }
    }
}
