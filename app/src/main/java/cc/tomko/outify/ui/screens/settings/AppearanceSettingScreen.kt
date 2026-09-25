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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
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
                title = { Text(stringResource(R.string.common_appearance)) },
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
                PreferenceSectionHeader("Dynamic")

                ElevatedCard {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_dynamic_theme)) },
                        description = stringResource(R.string.settings_appearance_track_theme_desc),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        isChecked = settings.dynamicTheme,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicTheme(enabled)
                        }
                    )

                    if (!settings.dynamicTheme) {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_dynamic_system)) },
                            description = stringResource(R.string.settings_appearance_system_theme_desc),
                            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                            isChecked = settings.dynamicSystem,
                            onCheckedChange = { enabled ->
                                viewModel.setDynamicSystem(enabled)
                            }
                        )

                        if (!settings.dynamicSystem) {
                            ColorPreferenceEntry(
                                title = { Text(stringResource(R.string.settings_accent_color)) },
                                description = stringResource(R.string.settings_appearance_accent_desc),
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
                        title = { Text(stringResource(R.string.settings_pure_black)) },
                        description = stringResource(R.string.settings_appearance_amoled),
                        icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                        isChecked = settings.pureBlack,
                        onCheckedChange = { enabled ->
                            viewModel.setPureBlack(enabled)
                        }
                    )

                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_high_contrast)) },
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
                        title = { Text(stringResource(R.string.settings_mono_artwork)) },
                        description = stringResource(R.string.settings_appearance_monochrome),
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
                            title = { Text(stringResource(R.string.settings_mono_albums)) },
                            description = stringResource(R.string.settings_mono_album_desc),
                            icon = { Icon(Icons.Default.Album, contentDescription = null) },
                            isChecked = settings.monochromeAlbums,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeAlbums(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_artists)) },
                            description = stringResource(R.string.settings_mono_artist_desc),
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            isChecked = settings.monochromeArtists,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeArtists(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_playlists)) },
                            description = stringResource(R.string.settings_mono_playlist_desc),
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
                            title = { Text(stringResource(R.string.settings_mono_tracks)) },
                            description = stringResource(R.string.settings_mono_tracks_desc),
                            icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                            isChecked = settings.monochromeTracks,
                            onCheckedChange = { enabled ->
                                viewModel.setMonochromeTracks(enabled)
                            }
                        )

                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_player)) },
                            description = stringResource(R.string.settings_mono_player_desc),
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
                            title = { Text(stringResource(R.string.settings_mono_headers)) },
                            description = stringResource(R.string.settings_mono_headers_desc),
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
                        title = { Text(stringResource(R.string.settings_font_scale)) },
                        description = stringResource(R.string.settings_font_scale_value, settings.fontScale),
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
                        title = { Text(stringResource(R.string.settings_floating_navbar)) },
                        description = stringResource(R.string.settings_dynamic_header_desc),
                        icon = { Icon(Icons.Default.Houseboat, contentDescription = null) },
                        isChecked = settings.experimentalFloatingNav,
                        onCheckedChange = { viewModel.setExperimentalFloatingNav(it) },
                    )
                }

                if (settings.experimentalFloatingNav) {
                    ElevatedCard {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_show_selected_label)) },
                            description = stringResource(R.string.settings_show_selected_label_desc),
                            icon = { Icon(Icons.Default.Title, contentDescription = null) },
                            isChecked = settings.navbarShowLabel,
                            onCheckedChange = { viewModel.setNavbarShowLabel(it) },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}
