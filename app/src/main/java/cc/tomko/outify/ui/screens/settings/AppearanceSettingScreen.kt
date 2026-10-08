package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.ui.components.ColorPreferenceEntry
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SettingsScaffold
import cc.tomko.outify.ui.components.SettingsSection
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.AppearanceViewModel

@Composable
fun AppearanceSettingScreen(
    viewModel: AppearanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = InterfaceSettings())

    SettingsScaffold(
        title = stringResource(R.string.common_appearance),
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        item {
            SettingsSection(title = "Dynamic", icon = Icons.Default.Palette) {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_dynamic_theme)) },
                        description = stringResource(R.string.settings_appearance_track_theme_desc),
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        isChecked = settings.dynamicTheme,
                        onCheckedChange = { viewModel.setDynamicTheme(it) }
                    )
                }

                if (!settings.dynamicTheme) {
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_dynamic_system)) },
                            description = stringResource(R.string.settings_appearance_system_theme_desc),
                            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                            isChecked = settings.dynamicSystem,
                            onCheckedChange = { viewModel.setDynamicSystem(it) }
                        )
                    }

                    if (!settings.dynamicSystem) {
                        entry {
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
        }

        item {
            SettingsSection {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_pure_black)) },
                        description = stringResource(R.string.settings_appearance_amoled),
                        icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                        isChecked = settings.pureBlack,
                        onCheckedChange = { viewModel.setPureBlack(it) }
                    )
                }
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_high_contrast)) },
                        icon = { Icon(Icons.Default.Contrast, contentDescription = null) },
                        isChecked = settings.highContrastCompat,
                        onCheckedChange = { viewModel.setHighContrastCompat(it) }
                    )
                }
            }
        }

        item {
            SettingsSection(title = "Monochrome", icon = Icons.Default.MonochromePhotos) {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_mono_artwork)) },
                        description = stringResource(R.string.settings_appearance_monochrome),
                        icon = { Icon(Icons.Default.MonochromePhotos, contentDescription = null) },
                        isChecked = settings.monochromeImages,
                        onCheckedChange = { viewModel.setMonochromeImages(it) }
                    )
                }

                if (settings.monochromeImages) {
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_albums)) },
                            description = stringResource(R.string.settings_mono_album_desc),
                            icon = { Icon(Icons.Default.Album, contentDescription = null) },
                            isChecked = settings.monochromeAlbums,
                            onCheckedChange = { viewModel.setMonochromeAlbums(it) }
                        )
                    }
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_artists)) },
                            description = stringResource(R.string.settings_mono_artist_desc),
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            isChecked = settings.monochromeArtists,
                            onCheckedChange = { viewModel.setMonochromeArtists(it) }
                        )
                    }
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_playlists)) },
                            description = stringResource(R.string.settings_mono_playlist_desc),
                            icon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                            },
                            isChecked = settings.monochromePlaylists,
                            onCheckedChange = { viewModel.setMonochromePlaylists(it) }
                        )
                    }
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_tracks)) },
                            description = stringResource(R.string.settings_mono_tracks_desc),
                            icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                            isChecked = settings.monochromeTracks,
                            onCheckedChange = { viewModel.setMonochromeTracks(it) }
                        )
                    }
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_player)) },
                            description = stringResource(R.string.settings_mono_player_desc),
                            icon = { Icon(Icons.Default.PlayCircleOutline, contentDescription = null) },
                            isChecked = settings.monochromePlayer,
                            onCheckedChange = { viewModel.setMonochromePlayer(it) }
                        )
                    }
                    entry {
                        SwitchPreferenceEntry(
                            title = { Text(stringResource(R.string.settings_mono_headers)) },
                            description = stringResource(R.string.settings_mono_headers_desc),
                            icon = { Icon(Icons.Default.Topic, contentDescription = null) },
                            isChecked = settings.monochromeHeaders,
                            onCheckedChange = { viewModel.setMonochromeHeaders(it) }
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = "Font", icon = Icons.Default.TextFields) {
                entry {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.settings_font_scale)) },
                        description = stringResource(R.string.settings_font_scale_value, settings.fontScale),
                        icon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                        content = {
                            Slider(
                                value = settings.fontScale,
                                onValueChange = { viewModel.setFontScale(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 14,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        },
                    )
                }
            }
        }

        item {
            SettingsSection(title = "Experimental", icon = Icons.Default.Science) {
                entry {
                    SwitchPreferenceEntry(
                        title = { Text(stringResource(R.string.settings_floating_navbar)) },
                        description = stringResource(R.string.settings_dynamic_header_desc),
                        icon = { Icon(Icons.Default.Houseboat, contentDescription = null) },
                        isChecked = settings.experimentalFloatingNav,
                        onCheckedChange = { viewModel.setExperimentalFloatingNav(it) },
                    )
                }

                if (settings.experimentalFloatingNav) {
                    entry {
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
        }
    }
}