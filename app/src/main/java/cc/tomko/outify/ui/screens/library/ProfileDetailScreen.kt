package cc.tomko.outify.ui.screens.library

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.core.model.ProfilePlaylist
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.ProfileDetailSkeleton
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.viewmodel.library.ProfileDetailViewModel
import cc.tomko.outify.ui.viewmodel.library.ProfileUiState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileDetailScreen(
    viewModel: ProfileDetailViewModel,
    onBack: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current
    val collapsingState = rememberCollapsingHeaderState()
    val lazyList = rememberLazyListState()
    val atTop by remember {
        derivedStateOf {
            lazyList.firstVisibleItemIndex == 0 &&
                    lazyList.firstVisibleItemScrollOffset == 0
        }
    }
    SideEffect { collapsingState.canExpand = atTop }

    LaunchedEffect(lazyList.isScrollInProgress) {
        if (!lazyList.isScrollInProgress) {
            val canExpand =
                lazyList.firstVisibleItemIndex == 0 &&
                        lazyList.firstVisibleItemScrollOffset == 0

            collapsingState.snapIfNeeded(canExpand)
        }
    }

    when (val state = uiState) {
        is ProfileUiState.Loading -> {
            ProfileDetailSkeleton(modifier = modifier.fillMaxSize())
        }

        is ProfileUiState.Error -> {
            ErrorScreen(
                message = state.message,
                onRetry = { viewModel.retry() },
            )
        }

        is ProfileUiState.Success -> {
            val profile = state.profile
            val artworkUrl = profile?.imageUrl
            val playlists = profile?.publicPlaylists ?: emptyList()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface)
                    .nestedScroll(collapsingState.nestedScrollConnection)
            ) {
                val currentTopBarHeightDp = with(density) { collapsingState.height.value.toDp() }

                LazyColumn(
                    state = lazyList,
                    contentPadding = PaddingValues(top = currentTopBarHeightDp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        ProfileStatsRow(
                            followersCount = state.followersCount,
                            followingCount = state.followingCount,
                            onFollowersClick = onFollowersClick,
                            onFollowingClick = onFollowingClick
                        )
                    }

                    item {
                        OutlinedButton(
                            onClick = { viewModel.toggleFollow() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (state.isFollowing) Icons.Filled.PersonRemove else Icons.Filled.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.isFollowing) "Unfollow" else "Follow"
                            )
                        }
                    }

                    item {
                        Text(
                            text = "Public Playlists",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 16.dp)
                        )
                    }

                    items(playlists) { playlist ->
                        ProfilePlaylistRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.uri) }
                        )
                    }
                }

                CollapsingHeader(
                    collapseFraction = collapsingState.collapseFraction,
                    headerHeight = currentTopBarHeightDp,
                    onBackPressed = onBack,
                    backgroundContent = {
                        ArtworkBackground(
                            artworkUrl = artworkUrl,
                        )
                    },
                    titleContent = {
                        Text(
                            text = profile?.name ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = "Account • ${playlists.size} playlists",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ProfileStatsRow(
    followersCount: Int,
    followingCount: Int,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onFollowersClick)
        ) {
            Text(
                text = formatCount(followersCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Followers",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onFollowingClick)
        ) {
            Text(
                text = formatCount(followingCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Following",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfilePlaylistRow(
    playlist: ProfilePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artworkUrl = playlist.imageUrl

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                SmartImage(
                    url = artworkUrl,
                    monochrome = LocalUiSettings.current.monochromePlaylists
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.followersCount} followers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}