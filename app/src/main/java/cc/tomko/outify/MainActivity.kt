package cc.tomko.outify

import android.content.res.Configuration
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import cc.tomko.outify.MainActivity.MainActivity.LocalSharedTransitionScope
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.spirc.VolumeController
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.data.repository.PendingBackupImport
import cc.tomko.outify.data.setting.LocalEpisodeSwipeActionHandler
import cc.tomko.outify.data.setting.LocalSwipeActionHandler
import cc.tomko.outify.data.setting.LocalSwipeGestureSettings
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.OutifyTheme
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.ThemeMode
import cc.tomko.outify.ui.components.GlobalPopupHost
import cc.tomko.outify.ui.components.navigation.FloatingOutifyBottomNav
import cc.tomko.outify.ui.components.navigation.NavDestination
import cc.tomko.outify.ui.components.navigation.NavigationRoot
import cc.tomko.outify.ui.components.navigation.OutifyBottomNav
import cc.tomko.outify.ui.components.navigation.Route
import cc.tomko.outify.ui.components.player.MiniPlayer
import cc.tomko.outify.ui.components.player.PlayerSheet
import cc.tomko.outify.ui.components.player.QueueBottomSheet
import cc.tomko.outify.ui.components.player.rememberPlayerSheetState
import cc.tomko.outify.ui.components.player.rememberQueueBottomSheetState
import cc.tomko.outify.ui.notifications.InAppNotificationHost
import cc.tomko.outify.ui.screens.player.PlayerContent
import cc.tomko.outify.ui.viewmodel.MainViewModel
import cc.tomko.outify.ui.viewmodel.bottomsheet.AddToPlaylistViewModel
import cc.tomko.outify.ui.viewmodel.bottomsheet.AddToWidgetViewModel
import cc.tomko.outify.ui.viewmodel.bottomsheet.CreatePlaylistViewModel
import cc.tomko.outify.ui.viewmodel.bottomsheet.PlaybackDevicesViewModel
import cc.tomko.outify.ui.viewmodel.player.MiniPlayerViewModel
import cc.tomko.outify.ui.viewmodel.player.MultiQueueViewModel
import cc.tomko.outify.ui.viewmodel.player.PlayerViewModel
import cc.tomko.outify.ui.viewmodel.player.QueueViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var volumeController: VolumeController

    private val deepLinkFlow = MutableSharedFlow<Uri>(extraBufferCapacity = 1)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handled = super.onKeyDown(keyCode, event)
            volumeController.onAndroidVolumeChanged()
            handled
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    data object MainActivity {
        val LocalSharedTransitionScope =
            compositionLocalOf<SharedTransitionScope> { error("No scope provided") }
        val LocalAnimatedVisibilityScope =
            compositionLocalOf<AnimatedVisibilityScope> { error("No scope provided") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            GlobalPopupController.show(PopupSpec.NotificationPermission)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) {
            GlobalPopupController.show(PopupSpec.BatteryOptimization)
        }

        volumeController.start()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        intent?.data?.let {
            deepLinkFlow.tryEmit(it)
        }

        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()

            App(mainViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        intent.data?.let {
            deepLinkFlow.tryEmit(it)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @Composable
    fun App(
        viewModel: MainViewModel,
    ) {
        val startRoute = Route.HomeScreen

        val backStack = rememberNavBackStack(startRoute)

        LaunchedEffect(Unit) {
            deepLinkFlow.collect { uri ->
                val path = uri.lastPathSegment?.lowercase() ?: ""
                if (path.endsWith(".outify")) {
                    PendingBackupImport.offer(uri)
                    backStack.add(Route.MiscSettings)
                } else {
                    parseDeepLinkUriToNavKey(uri)?.let { navKey ->
                        backStack.add(navKey)
                    }
                }
            }
        }

        val routes = listOf(
            NavDestination("home", "Home", Route.HomeScreen) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null
                )
            },
            NavDestination("search", "Search", Route.SearchScreen) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null
                )
            },
            NavDestination("liked", "Liked", Route.LikedScreen()) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null
                )
            },
            NavDestination(
                "library",
                "Library",
                Route.LibraryScreen
            ) { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
        )

        val currentRoute = backStack.last()

        val selectedId = when (currentRoute) {
            Route.HomeScreen -> "home"
            Route.SearchScreen -> "search"
            is Route.LikedScreen -> "liked"
            Route.LibraryScreen -> "library"
            else -> null
        }

        val sheetState = rememberQueueBottomSheetState()

        val queueViewModel: QueueViewModel = hiltViewModel()
        val multiQueueViewModel: MultiQueueViewModel = hiltViewModel()
        val miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel()
        val playerViewModel: PlayerViewModel = hiltViewModel()
        val addToPlaylistViewModel: AddToPlaylistViewModel = hiltViewModel()
        val createPlaylistViewModel: CreatePlaylistViewModel = hiltViewModel()
        val playbackDevicesViewModel: PlaybackDevicesViewModel = hiltViewModel()
        val addToWidgetViewModel: AddToWidgetViewModel = hiltViewModel()

        val playerSheetState = rememberPlayerSheetState()
        val playerListState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        val interfaceSettings by viewModel
            .interfaceSettings
            .collectAsState(initial = InterfaceSettings())

        val trackSwipeSettings by viewModel.swipeSettings.collectAsState(initial = interfaceSettings.gestureSettings)
        val currentTrack by viewModel.currentTrack.collectAsState(initial = null)

        val density = LocalDensity.current
        val fixedDensity = Density(density.density, fontScale = interfaceSettings.fontScale)

        val themeMode =
            if (interfaceSettings.dynamicTheme) ThemeMode.DYNAMIC_ALBUM else if (interfaceSettings.dynamicSystem) ThemeMode.DYNAMIC_SYSTEM else ThemeMode.STATIC

        var lastDetailRoute by remember { mutableStateOf<Route?>(null) }
        LaunchedEffect(currentRoute) {
            when (currentRoute) {
                is Route.ArtistScreen,
                is Route.AlbumScreen,
                is Route.PlaylistScreen,
                is Route.TrackScreen,
                is Route.ProfileScreen -> lastDetailRoute = currentRoute
                else -> {}
            }
        }

        val detailDestination = remember(lastDetailRoute) {
            lastDetailRoute?.let { route ->
                val (id, label, icon) = when (route) {
                    is Route.ArtistScreen -> Triple("detail_artist", "Artist", Icons.Default.Person)
                    is Route.AlbumScreen,
                    is Route.TrackScreen -> Triple("detail_album", "Album", Icons.Default.Album)
                    is Route.PlaylistScreen -> Triple("detail_playlist", "Playlist", Icons.AutoMirrored.Filled.QueueMusic)
                    is Route.ProfileScreen -> Triple("detail_profile", "Profile", Icons.Default.AccountCircle)
                    else -> return@let null
                }
                NavDestination(id, label, route) { Icon(icon, contentDescription = null) }
            }
        }

        val allRoutes = remember(detailDestination, interfaceSettings.showNavbarHistory, interfaceSettings.navbarHistoryOnEnd) {
            if (detailDestination != null && interfaceSettings.showNavbarHistory) {
                if (interfaceSettings.navbarHistoryOnEnd) routes + detailDestination
                else listOf(detailDestination) + routes
            } else routes
        }

        BackHandler(enabled = playerSheetState.isExpanded) {
            scope.launch {
                playerSheetState.collapse()
            }
        }

        OutifyTheme(
            track = currentTrack,
            themeMode = themeMode,
            staticAccentColor = interfaceSettings.accentColor,
            pureBlack = interfaceSettings.pureBlack,
            highContrastCompat = interfaceSettings.highContrastCompat,
            content = {
                CompositionLocalProvider(
                    LocalDensity provides fixedDensity
                ) {
                    SharedTransitionLayout {
                        CompositionLocalProvider(
                            LocalSharedTransitionScope provides this,
                            LocalSwipeGestureSettings provides trackSwipeSettings,
                            LocalEpisodeSwipeActionHandler provides viewModel.episodeSwipeActionHandler,
                            LocalSwipeActionHandler provides viewModel.swipeActionHandler,
                            LocalUiSettings provides interfaceSettings,
                        ) {
                            Scaffold { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = innerPadding.calculateBottomPadding())
                                        .consumeWindowInsets(WindowInsets(bottom = innerPadding.calculateBottomPadding()))
                                ) {
                                    val notificationPaddingBottom by animateDpAsState(
                                        targetValue = if (currentTrack != null) 168.dp
                                        else if (interfaceSettings.experimentalFloatingNav) 80.dp
                                        else 68.dp,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "notificationPaddingBottom"
                                    )

                                    NavigationRoot(
                                        backStack,
                                        modifier = Modifier.matchParentSize(),
                                        bottomPadding = if (currentTrack != null) 156.dp else if (interfaceSettings.experimentalFloatingNav) 60.dp else 56.dp
                                    )

                                    InAppNotificationHost(
                                        modifier = Modifier.matchParentSize(),
                                        maxWidthFraction = 0.92f,
                                        hostPaddingBottom = notificationPaddingBottom
                                    )

                                    GlobalPopupHost(
                                        backStack = backStack,
                                        addToQueue = { viewModel.addToQueue(it.toUriString()) },
                                        playNext = { viewModel.playNext(it.toUriString()) },
                                        startRadio = { viewModel.startRadio(it) },
                                        openRadio = {
                                            val uri =
                                                viewModel.getRadioUri(it) ?: return@GlobalPopupHost
                                            backStack.add(Route.PlaylistScreen(uri))
                                        },
                                        addToPlaylist = { viewModel.addToPlaylist(it) },
                                        toggleLike = { viewModel.favorite(it.toUriString()) },

                                        addToPlaylistViewModel = addToPlaylistViewModel,
                                        createPlaylistViewModel = createPlaylistViewModel,
                                        playbackDevicesViewModel = playbackDevicesViewModel,
                                        addToWidgetViewModel = addToWidgetViewModel,
                                    )

                                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                                    if (isLandscape) {
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(3f)
                                                    .fillMaxHeight()
                                            ) {
                                                PlayerContent(
                                                    viewModel = playerViewModel,
                                                    expansionFractionProvider = { 1f },
                                                    listState = playerListState,
                                                    paddingValues = innerPadding,
                                                    onShowQueue = { sheetState.show() },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .weight(7f)
                                                    .fillMaxHeight()
                                            ) {
                                                NavigationRoot(
                                                    backStack = backStack,
                                                    modifier = Modifier.fillMaxSize(),
                                                    bottomPadding = if (interfaceSettings.experimentalFloatingNav) 60.dp else 56.dp
                                                )

                                                InAppNotificationHost(
                                                    modifier = Modifier.fillMaxSize(),
                                                    maxWidthFraction = 0.92f,
                                                    hostPaddingBottom = notificationPaddingBottom
                                                )

                                                if (interfaceSettings.experimentalFloatingNav) {
                                                    FloatingOutifyBottomNav(
                                                        items = allRoutes,
                                                        selectedId = selectedId,
                                                        onItemSelected = { item -> backStack.add(item.route) },
                                                        showSelectedLabel = interfaceSettings.navbarShowLabel,
                                                        modifier = Modifier.align(Alignment.BottomCenter)
                                                    )
                                                } else {
                                                    OutifyBottomNav(
                                                        items = allRoutes,
                                                        selectedId = selectedId,
                                                        onItemSelected = { item -> backStack.add(item.route) },
                                                        modifier = Modifier.align(Alignment.BottomCenter)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Portrait mode
                                        AnimatedVisibility(
                                            visible = currentTrack != null,
                                            enter = slideInVertically(
                                                initialOffsetY = { fullHeight -> fullHeight }
                                            ) + fadeIn(),
                                            exit = slideOutVertically(
                                                targetOffsetY = { fullHeight -> fullHeight }
                                            ) + fadeOut(),
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = if (interfaceSettings.experimentalFloatingNav) 78.dp else 68.dp)
                                        ) {
                                            PlayerSheet(
                                                sheetState = playerSheetState,
                                                listState = playerListState,
                                                miniPlayerHeight = 88.dp,
                                                miniContent = { progress ->
                                                    MiniPlayer(
                                                        viewModel = miniPlayerViewModel,
                                                        onDismiss = {
                                                            miniPlayerViewModel.setTrack(null)
                                                        },
                                                        modifier = Modifier.padding(
                                                            horizontal = 12.dp,
                                                            vertical = 12.dp
                                                        ),
                                                        showQueue = { sheetState.show() },
                                                        onClick = {
                                                            scope.launch { playerSheetState.expand() }
                                                        }
                                                    )
                                                },
                                                fullContent = { progress ->
                                                    PlayerContent(
                                                        viewModel = playerViewModel,
                                                        expansionFractionProvider = { progress },
                                                        listState = playerListState,
                                                        paddingValues = innerPadding,
                                                        onShowQueue = {
                                                            sheetState.show()
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                    )
                                                }
                                            )
                                        }

                                        if (interfaceSettings.experimentalFloatingNav) {
                                            AnimatedVisibility(
                                                visible = currentTrack == null || !playerSheetState.isExpanded,
                                                enter = slideInVertically(
                                                    initialOffsetY = { fullHeight -> fullHeight }
                                                ) + fadeIn(),
                                                exit = slideOutVertically(
                                                    targetOffsetY = { fullHeight -> fullHeight }
                                                ) + fadeOut(),
                                                modifier = Modifier.align(Alignment.BottomCenter),
                                            ) {
                                                FloatingOutifyBottomNav(
                                                    items = allRoutes,
                                                    selectedId = selectedId,
                                                    onItemSelected = { item -> backStack.add(item.route) },
                                                    showSelectedLabel = interfaceSettings.navbarShowLabel,
                                                )
                                            }
                                        } else {
                                            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                                                OutifyBottomNav(
                                                    items = allRoutes,
                                                    selectedId = selectedId,
                                                    onItemSelected = { item -> backStack.add(item.route) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (sheetState.visible.value) {
                                QueueBottomSheet(
                                    sheetState = sheetState.sheetState,
                                    viewModel = queueViewModel,
                                    multiQueueViewModel = multiQueueViewModel,
                                    onArtistClick = {
                                        backStack.add(Route.ArtistScreen(it.uri))
                                    },
                                    onArtworkClick = {
                                        val albumUri = it.album?.uri
                                        if (albumUri != null) {
                                            backStack.add(Route.AlbumScreen(albumUri))
                                        } else {
                                            backStack.add(Route.TrackScreen(it.uri))
                                        }
                                    },
                                    onDismissRequest = {
                                        sheetState.hide()
                                    }
                                )
                            }
                        }
                    }
                }
            })
    }

    fun parseDeepLinkUriToNavKey(uri: Uri): NavKey? {
        // spotify:x:y (opaque)
        if (uri.scheme == "spotify" && uri.host == null) {
            val ssp = uri.schemeSpecificPart ?: return null
            val parts = ssp.split(":")
            if (parts.size == 2) {
                val type = parts[0]

                return when (type) {
                    "album" -> Route.AlbumScreen(uri.toString())
                    "artist" -> Route.ArtistScreen(uri.toString())
                    "track" -> Route.TrackScreen(uri.toString())
                    "playlist" -> Route.PlaylistScreen(uri.toString())
                    "user" -> Route.ProfileScreen(uri.toString())
                    else -> null
                }
            }
        }

        // spotify://x/y
        if (uri.scheme == "spotify" && uri.host != null) {
            val id = uri.lastPathSegment ?: return null
            val internalUri = "spotify:${uri.host}:$id"
            return when (uri.host) {
                "album" -> Route.AlbumScreen(internalUri)
                "artist" -> Route.ArtistScreen(internalUri)
                "track" -> Route.TrackScreen(internalUri)
                "playlist" -> Route.PlaylistScreen(internalUri)
                "user" -> Route.ProfileScreen(internalUri)
                else -> null
            }
        }

        // https://open.spotify.com/x/y
        if (uri.scheme == "https" && uri.host == "open.spotify.com") {
            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val type = segments[0]
                val id = segments[1]
                val internalUri = "spotify:$type:$id"

                return when (type) {
                    "album" -> Route.AlbumScreen(internalUri)
                    "artist" -> Route.ArtistScreen(internalUri)
                    "track" -> Route.TrackScreen(internalUri)
                    "playlist" -> Route.PlaylistScreen(internalUri)
                    "user" -> Route.ProfileScreen(internalUri)
                    else -> null
                }
            }
        }

        return null
    }
}