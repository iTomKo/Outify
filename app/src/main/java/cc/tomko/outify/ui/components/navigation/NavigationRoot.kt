package cc.tomko.outify.ui.components.navigation

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import cc.tomko.outify.reccobeats.PendingRecommendation
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.screens.HomeScreen
import cc.tomko.outify.ui.screens.SearchScreen
import cc.tomko.outify.ui.screens.library.LibraryScreen
import cc.tomko.outify.ui.screens.library.LikedScreen
import cc.tomko.outify.ui.screens.library.PlaylistScreen
import cc.tomko.outify.ui.screens.library.ProfileDetailScreen
import cc.tomko.outify.ui.screens.library.album.AlbumDetailScreen
import cc.tomko.outify.ui.screens.library.artist.ArtistDetailScreen
import cc.tomko.outify.ui.screens.library.show.ShowDetailScreen
import cc.tomko.outify.ui.screens.library.track.TrackDetailScreen
import cc.tomko.outify.ui.screens.settings.AboutScreen
import cc.tomko.outify.ui.screens.settings.AccountsScreen
import cc.tomko.outify.ui.screens.settings.AppearanceSettingScreen
import cc.tomko.outify.ui.screens.settings.DebugScreen
import cc.tomko.outify.ui.screens.settings.GestureSettingsScreen
import cc.tomko.outify.ui.screens.settings.InterfaceSettingScreen
import cc.tomko.outify.ui.screens.settings.MiscSettingsScreen
import cc.tomko.outify.ui.screens.settings.PlaybackSettingScreen
import cc.tomko.outify.ui.screens.settings.SettingsScreen
import cc.tomko.outify.ui.viewmodel.HomeViewModel
import cc.tomko.outify.ui.viewmodel.SearchViewModel
import cc.tomko.outify.ui.viewmodel.detail.AlbumDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.ArtistDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.PlaylistDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.TrackDetailViewModel
import cc.tomko.outify.ui.viewmodel.library.LibraryViewModel
import cc.tomko.outify.ui.viewmodel.library.LikedViewModel
import cc.tomko.outify.ui.viewmodel.library.ProfileDetailViewModel
import cc.tomko.outify.ui.viewmodel.library.ShowDetailViewModel
import cc.tomko.outify.ui.viewmodel.settings.AccountsViewModel
import cc.tomko.outify.ui.viewmodel.settings.AppearanceViewModel
import cc.tomko.outify.ui.viewmodel.settings.DebugViewModel
import cc.tomko.outify.ui.viewmodel.settings.GestureSettingViewModel
import cc.tomko.outify.ui.viewmodel.settings.InterfaceViewModel
import cc.tomko.outify.ui.viewmodel.settings.MiscSettingsViewModel
import cc.tomko.outify.ui.viewmodel.settings.PlaybackSettingViewModel
import cc.tomko.outify.ui.viewmodel.settings.SettingsViewModel

private const val NAV_RANK_KEY = "outify.navRank"

/** Position in the navbar, left to right. */
fun navRank(rank: Int): Map<String, Any> = mapOf(NAV_RANK_KEY to rank)

private fun Scene<NavKey>.navRank(): Int? =
    entries.lastOrNull()?.metadata?.get(NAV_RANK_KEY) as? Int

@Composable
fun SharedTransitionScope.NavigationRoot(
    backStack: NavBackStack<NavKey>,
    entryViewModelStoreProvider: ViewModelStoreProvider,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.popKeepingRoot() },
        modifier = modifier.padding(bottom = bottomPadding),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberEntryScopedViewModelDecorator(entryViewModelStoreProvider),
        ),
        transitionSpec = { directionalSlide(defaultForward = true) },
        popTransitionSpec = { directionalSlide(defaultForward = false) },
        predictivePopTransitionSpec = {
            slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
        },
        entryProvider = entryProvider {
            entry<Route.HomeScreen>(metadata = navRank(0)) {
                val viewModel: HomeViewModel = hiltViewModel()

                HomeScreen(
                    backStack = backStack,
                    viewModel = viewModel,
                    onShowJam = {
                        GlobalPopupController.show(PopupSpec.Jam())
                    }
                )
            }

            entry<Route.LikedScreen>(metadata = navRank(2)) {
                val viewModel: LikedViewModel = hiltViewModel()
                val listState = rememberLazyListState()

                LikedScreen(
                    viewModel = viewModel,
                    listState = listState,
                    scrollToIndex = it.scrollToIndex,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    onArtworkClick = {
                        backStack.add(Route.AlbumScreen(it.uri))
                    },
                    onArtistClick = {
                        backStack.add(Route.ArtistScreen(it.uri))
                    }
                )
            }

            entry<Route.LibraryScreen>(metadata = navRank(3)) {
                val viewModel: LibraryViewModel = hiltViewModel()

                LibraryScreen(viewModel, backStack)
            }

            entry<Route.SearchScreen>(metadata = navRank(2)) {
                val viewModel = hiltViewModel<SearchViewModel>()
                SearchScreen(backStack, viewModel)
            }

            entry<Route.RecommendationsScreen> {
                val viewModel = hiltViewModel<SearchViewModel>()
                LaunchedEffect(Unit) {
                    val pending = PendingRecommendation
                    val seedIds = pending.seedIds
                    val config = pending.config
                    if (seedIds != null && config != null) {
                        viewModel.fetchRecommendations(seedIds, config)
                    }
                    pending.seedIds = null
                    pending.config = null
                }
                SearchScreen(backStack, viewModel, showSearchUi = false)
            }

            entry<Route.TrackScreen>(metadata = EntryScopedViewModels + navRank(4)) {
                val viewModel: TrackDetailViewModel = hiltViewModel()

                LaunchedEffect(it.trackUri) {
                    viewModel.loadTrack(it.trackUri)
                }

                TrackDetailScreen(
                    viewModel = viewModel,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    artistClick = { uri ->
                        backStack.add(Route.ArtistScreen(uri))
                    },
                    artworkClick = { album ->
                        album ?: return@TrackDetailScreen
                        backStack.add(Route.AlbumScreen(album.uri))
                    }
                )
            }

            entry<Route.AlbumScreen>(metadata = EntryScopedViewModels + navRank(4)) {
                val viewModel: AlbumDetailViewModel = hiltViewModel()

                LaunchedEffect(it.albumUri) {
                    viewModel.loadAlbum(it.albumUri)
                }

                AlbumDetailScreen(
                    viewModel = viewModel,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    artistClick = { uri ->
                        backStack.add(Route.ArtistScreen(uri))
                    },
                    artworkClick = { uri ->
                        backStack.add(Route.TrackScreen(uri))
                    }
                )
            }

            entry<Route.ArtistScreen>(metadata = EntryScopedViewModels + navRank(4)) {
                val viewModel: ArtistDetailViewModel = hiltViewModel()
                LaunchedEffect(viewModel) {
                    viewModel.loadArtist(it.artistUri)
                }

                ArtistDetailScreen(
                    viewModel,
                    onArtworkClick = { track ->
                        val albumUri = track.album?.uri
                        if (albumUri != null) {
                            backStack.add(Route.AlbumScreen(albumUri))
                        } else {
                            backStack.add(Route.TrackScreen(track.uri))
                        }
                    },
                    onAlbumClick = { album ->
                        backStack.add(Route.AlbumScreen(album.uri))
                    },
                    onArtistClick = { backStack.add(Route.ArtistScreen(it.uri)) },
                    onBack = {
                        backStack.popKeepingRoot()
                    }
                )
            }

            entry<Route.PlaylistScreen>(metadata = EntryScopedViewModels + navRank(4)) {
                val viewModel: PlaylistDetailViewModel = hiltViewModel()
                LaunchedEffect(it.playlistUri) {
                    viewModel.loadPlaylist(it.playlistUri, false)
                }
                PlaylistScreen(
                    viewModel = viewModel,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    onArtworkClick = { track ->
                        val albumUri = track.album?.uri
                        if (albumUri != null) {
                            backStack.add(Route.AlbumScreen(albumUri))
                        } else {
                            backStack.add(Route.TrackScreen(track.uri))
                        }
                    },
                    onArtistClick = { backStack.add(Route.ArtistScreen(it.uri)) },
                    onAuthorClick = {
                        backStack.add(Route.ProfileScreen(it.uri))
                    }
                )
            }

            entry<Route.ProfileScreen>(metadata = navRank(4)) {
                val viewModel: ProfileDetailViewModel = hiltViewModel()
                LaunchedEffect(it.profileUri) {
                    viewModel.loadProfile(it.profileUri)
                }
                ProfileDetailScreen(
                    viewModel = viewModel,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    onPlaylistClick = { uri ->
                        backStack.add(Route.PlaylistScreen(uri))
                    },
                    onFollowersClick = {
                        // TODO: Implement followers list
                    },
                    onFollowingClick = {
                        // TODO: Implement following list
                    }
                )
            }

            entry<Route.ShowScreen>(metadata = navRank(4)) {
                val viewModel: ShowDetailViewModel = hiltViewModel()
                LaunchedEffect(it.showUri) {
                    viewModel.loadShow(it.showUri)
                }

                ShowDetailScreen(
                    viewModel = viewModel,
                    onBack = {
                        backStack.popKeepingRoot()
                    },
                    artworkClick = {},
                )
            }

            entry<Route.SettingsScreen> {
                val viewModel: SettingsViewModel = hiltViewModel()

                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                    openInterfaceSettings = {
                        backStack.add(Route.InterfaceSettings)
                    },
                    openPlaybackSettings = {
                        backStack.add(Route.PlaybackSettings)
                    },
                    openMiscSettings = {
                        backStack.add(Route.MiscSettings)
                    },
                    openAboutSettings = {
                        backStack.add(Route.AboutScreen)
                    },
                    openAccountSettings = {
                        backStack.add(Route.AccountsScreen)
                    }
                )
            }

            entry<Route.InterfaceSettings> {
                val viewModel: InterfaceViewModel = hiltViewModel()

                InterfaceSettingScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                    openGestureSettings = {
                        backStack.add(Route.GestureSettings)
                    },
                    openAppearanceSettings = {
                        backStack.add(Route.AppearanceSettings)
                    },
                )
            }

            entry<Route.AppearanceSettings> {
                val viewModel: AppearanceViewModel = hiltViewModel()

                AppearanceSettingScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                )
            }

            entry<Route.GestureSettings> {
                val viewModel: GestureSettingViewModel = hiltViewModel()

                GestureSettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                )
            }

            entry<Route.PlaybackSettings> {
                val viewModel: PlaybackSettingViewModel = hiltViewModel()

                PlaybackSettingScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                )
            }

            entry<Route.AccountsScreen> {
                val viewModel: AccountsViewModel = hiltViewModel()

                AccountsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                )
            }

            entry<Route.AboutScreen> {
                AboutScreen(
                    onNavigateBack = { backStack.popKeepingRoot() },
                    onOpenUrl = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, it.toUri())
                        )
                    }
                )
            }

            entry<Route.MiscSettings> {
                val viewModel: MiscSettingsViewModel = hiltViewModel()
                MiscSettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                    openDebugScreen = { backStack.add(Route.DebugScreen) }
                )
            }

            entry<Route.DebugScreen> {
                val viewModel: DebugViewModel = hiltViewModel()
                DebugScreen(
                    viewModel = viewModel,
                    onNavigateBack = { backStack.popKeepingRoot() },
                )
            }
        }
    )
}

private fun horizontalSlide(forward: Boolean): ContentTransform =
    if (forward) {
        slideInHorizontally { it } + fadeIn() togetherWith
                slideOutHorizontally { -it } + fadeOut()
    } else {
        slideInHorizontally { -it } + fadeIn() togetherWith
                slideOutHorizontally { it } + fadeOut()
    }

private fun AnimatedContentTransitionScope<Scene<NavKey>>.directionalSlide(
    defaultForward: Boolean
): ContentTransform {
    val from = initialState.navRank()
    val to = targetState.navRank()

    val forward = if (from != null && to != null && from != to) to > from else defaultForward
    return horizontalSlide(forward)
}