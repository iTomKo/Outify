package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Houseboat
import androidx.compose.material.icons.filled.MonochromePhotos
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.ui.components.ColorPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceSectionHeader
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.AppearanceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingScreen(
    viewModel: AppearanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = InterfaceSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PreferenceSectionHeader("Dynamic")

                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text("Dynamic theme") },
                        description = "Colorscheme will change according to current track",
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        isChecked = settings.dynamicTheme,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicTheme(enabled)
                        }
                    )

                    if (!settings.dynamicTheme) {
                        SwitchPreferenceEntry(
                            title = { Text("Dynamic system") },
                            description = "Colorscheme will adapt to your system's colorscheme",
                            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                            isChecked = settings.dynamicSystem,
                            onCheckedChange = { enabled ->
                                viewModel.setDynamicSystem(enabled)
                            }
                        )

                        if (!settings.dynamicSystem) {
                            ColorPreferenceEntry(
                                title = { Text("Accent color") },
                                description = "Color of Outify's interface",
                                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                value = settings.accentColor,
                                onValueChange = { viewModel.setAccentColor(it) }
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text("Pure black") },
                        description = "Use AMOLED black",
                        icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                        isChecked = settings.pureBlack,
                        onCheckedChange = { enabled ->
                            viewModel.setPureBlack(enabled)
                        }
                    )

                    SwitchPreferenceEntry(
                        title = { Text("High contrast") },
                        icon = { Icon(Icons.Default.Contrast, contentDescription = null) },
                        isChecked = settings.highContrastCompat,
                        onCheckedChange = { enabled ->
                            viewModel.setHighContrastCompat(enabled)
                        }
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth(),
                ) {
                    SwitchPreferenceEntry(
                        title = { Text("Monochrome artwork") },
                        description = "Every image will be black & white",
                        icon = { Icon(Icons.Default.MonochromePhotos, contentDescription = null) },
                        isChecked = settings.monochromeImages,
                        onCheckedChange = { enabled ->
                            viewModel.setMonochromeImages(enabled)
                        }
                    )
                }

            }
            item {
                if (settings.monochromeImages) {
                    PreferenceSectionHeader("Monochrome settings")

                    ElevatedCard {
                        SwitchPreferenceEntry(
                            title = { Text("Monochrome albums") },
                            description = "Album artwork in album views will be monochrome",
                            icon = { Icon(Icons.Default.Album, contentDescription = null) },
                            isChecked = settings.monochromeAlbums,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeAlbums(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Monochrome artists") },
                            description = "Artist artwork in artist views will be monochrome",
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            isChecked = settings.monochromeArtists,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeArtists(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Monochrome playlists") },
                            description = "Playlist artwork will be monochrome",
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null
                                )
                            },
                            isChecked = settings.monochromePlaylists,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromePlaylists(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Monochrome tracks") },
                            description = "Track rows will be monochrome",
                            icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                            isChecked = settings.monochromeTracks,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeTracks(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Monochrome player") },
                            description = "Player (mini & fullscreen) will be monochrome",
                            icon = {
                                Icon(
                                    Icons.Default.PlayCircleOutline,
                                    contentDescription = null
                                )
                            },
                            isChecked = settings.monochromePlayer,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromePlayer(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text("Monochrome headers") },
                            description = "Page headers will be monochrome",
                            icon = { Icon(Icons.Default.Topic, contentDescription = null) },
                            isChecked = settings.monochromeHeaders,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeHeaders(enabled)
                            }
                        )
                    }
                }
            }

            item {
                PreferenceSectionHeader("Font")

                ElevatedCard {
                    PreferenceEntry(
                        title = { Text("Font scale") },
                        description = "%.1f×".format(settings.fontScale),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        content = {
                            Slider(
                                value = settings.fontScale,
                                onValueChange = { viewModel.setFontScale(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 14,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        },
                        onClick = { },
                    )
                }
            }

            item {
                PreferenceSectionHeader("Experimental")

                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text("Floating navbar") },
                        description = "Instead of the standard static one",
                        icon = { Icon(Icons.Default.Houseboat, contentDescription = null) },
                        isChecked = settings.experimentalFloatingNav,
                        onCheckedChange = { viewModel.setExperimentalFloatingNav(it) },
                    )
                }

                if (settings.experimentalFloatingNav) {
                    ElevatedCard {
                        SwitchPreferenceEntry(
                            title = { Text("Show selected label") },
                            description = "Show name of current page",
                            icon = { Icon(Icons.Default.Title, contentDescription = null) },
                            isChecked = settings.navbarShowLabel,
                            onCheckedChange = { viewModel.setNavbarShowLabel(it) },
                        )
                    }
                }
            }
        }
    }
}
