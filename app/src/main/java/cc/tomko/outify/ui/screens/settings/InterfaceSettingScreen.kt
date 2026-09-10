package cc.tomko.outify.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.SwitchPreferenceEntry
import cc.tomko.outify.ui.viewmodel.settings.InterfaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceSettingScreen(
    viewModel: InterfaceViewModel,
    onNavigateBack: () -> Unit,
    openGestureSettings: (() -> Unit),
    openAppearanceSettings: (() -> Unit),
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState(initial = InterfaceSettings())
    val showNavbarHistory = settings.showNavbarHistory
    val showNavbarHistoryOnEnd = settings.navbarHistoryOnEnd

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interface") },
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
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text("Gestures") },
                        description = "Personalize gestures",
                        icon = { Icon(Icons.Default.Gesture, contentDescription = null) },
                        onClick = openGestureSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    PreferenceEntry(
                        title = { Text("Appearance") },
                        description = "Customize the design",
                        icon = { Icon(Icons.Default.DesignServices, contentDescription = null) },
                        onClick = openAppearanceSettings,
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier
                        .fillMaxWidth()
                ) {
                    SwitchPreferenceEntry(
                        title = { Text("Navigation history") },
                        description = "Show last seen detail screen in the navbar",
                        isChecked = showNavbarHistory,
                        onCheckedChange = { viewModel.setShowNavbarHistory(it) },
                        icon = { Icon(Icons.Default.History, contentDescription = null) }
                    )

                    if (showNavbarHistory) {
                        SwitchPreferenceEntry(
                            title = { Text("Show on right side") },
                            description = "When enabled shows on right side, otherwise left",
                            isChecked = showNavbarHistoryOnEnd,
                            onCheckedChange = { viewModel.setNavbarHistoryOnEnd(it) },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowRight,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
