package cc.tomko.outify.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.data.repository.OutifyBackup
import cc.tomko.outify.ui.components.PreferenceEntry
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.viewmodel.settings.BackupStatus
import cc.tomko.outify.ui.viewmodel.settings.MiscSettingsViewModel
import cc.tomko.outify.ui.viewmodel.settings.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiscSettingsScreen(
    viewModel: MiscSettingsViewModel,
    onNavigateBack: () -> Unit,
    openDebugScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val likedCount by viewModel.likedCount.collectAsStateWithLifecycle()
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val backupStatus by viewModel.backupStatus.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-outify-backup")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackup(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBackup(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_misc)) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PreferenceHeader(stringResource(R.string.section_sync))
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_liked_tracks),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        Text(
                            text = stringResource(R.string.sync_stored_count, likedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (!isAuthenticated) {
                            Text(
                                text = stringResource(R.string.tile_no_creds),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        when (val status = syncStatus) {
                            is SyncStatus.Idle -> {
                                Button(
                                    onClick = { viewModel.syncLikedTracks() },
                                    enabled = isAuthenticated,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Text(
                                        stringResource(R.string.sync_liked_tracks),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }

                            is SyncStatus.Syncing -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.sync_in_progress),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            is SyncStatus.Progress -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.notif_sync_progress_short, status.current, status.total),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    LinearProgressIndicator(
                                        progress = { status.current.toFloat() / status.total.toFloat() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            is SyncStatus.Success -> {
                                Button(
                                    onClick = { viewModel.resetStatus() },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = false
                                ) {
                                    Text(stringResource(R.string.notif_complete_excl))
                                }
                            }

                            is SyncStatus.Error -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.error_format, status.message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Button(
                                        onClick = { viewModel.syncLikedTracks() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Text(
                                            stringResource(R.string.common_retry),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.sync_fetches_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            item {
                PreferenceHeader(stringResource(R.string.section_backup_restore))
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_app_settings),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        Text(
                            text = stringResource(R.string.settings_backup_desc, OutifyBackup.FILE_EXTENSION),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        when (val status = backupStatus) {
                            is BackupStatus.Idle -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            exportLauncher.launch("outify-backup.${OutifyBackup.FILE_EXTENSION}")
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_export))
                                    }
                                    Button(
                                        onClick = {
                                            importLauncher.launch(arrayOf("*/*"))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.settings_import))
                                    }
                                }
                            }

                            is BackupStatus.Exporting -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_exporting),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }

                            is BackupStatus.Importing -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_importing),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }

                            is BackupStatus.Success -> {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            is BackupStatus.Error -> {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }

            item {
                ElevatedCard {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_reset_to_defaults)) },
                        description = stringResource(R.string.settings_reset_prefs_desc),
                        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                        onClick = {
                            viewModel.resetPreferences()
                        }
                    )
                }
            }

            item {
                ElevatedCard {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.common_debug)) },
                        description = stringResource(R.string.settings_show_debug_desc),
                        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        onClick = {
                            openDebugScreen()
                        }
                    )
                }
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}
