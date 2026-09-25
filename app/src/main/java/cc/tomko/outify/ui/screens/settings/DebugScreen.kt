package cc.tomko.outify.ui.screens.settings

import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.viewmodel.settings.DebugViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val playbackLoggedIn by viewModel.isPlaybackLoggedIn.collectAsState()
    val accountsLoggedIn by viewModel.isAccountLoggedIn.collectAsState()
    val hasCredentials by viewModel.hasPlaybackFile.collectAsState()
    val hasAccountFile by viewModel.hasAccountsFile.collectAsState()

    val userId by viewModel.userId.collectAsState()
    val username by viewModel.username.collectAsState()
    val isPremium by viewModel.isPremium.collectAsState()

    val isSpircUsable by viewModel.isSpircUsable.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val isBuffering by viewModel.isBuffering.collectAsState(initial = true)
    val isActiveDevice by viewModel.isActiveDevice.collectAsState(initial = false)
    val currentTrackName by viewModel.currentAudioName.collectAsState(initial = null)
    val queueSize by viewModel.queueSize.collectAsState(initial = 0)
    val preferences by viewModel.preferences.collectAsState()
    val exceptions = viewModel.exceptionCollector.exceptions

    val runtime = Runtime.getRuntime()
    val memoryInfo = Debug.MemoryInfo()
    val threadCount = Thread.getAllStackTraces().size
    val cpuTimeNanos = Process.getElapsedCpuTime()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_debug)) },
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
                PreferenceHeader(stringResource(R.string.section_general))

                Information(stringResource(R.string.debug_build_number), BuildConfig.VERSION_CODE.toString())
                Information(stringResource(R.string.debug_display_density), LocalDensity.current.density.toString())
                Information(stringResource(R.string.debug_display_dpi), LocalConfiguration.current.densityDpi.toString())
                Information(stringResource(R.string.debug_android_version), Build.VERSION.RELEASE)
            }

            item {
                PreferenceHeader(stringResource(R.string.common_accounts))

                Availability(stringResource(R.string.debug_playback_logged_in), playbackLoggedIn)
                Availability(stringResource(R.string.debug_accounts_logged_in), accountsLoggedIn)

                Availability(stringResource(R.string.debug_playback_creds_exists), hasCredentials)
                Availability(stringResource(R.string.debug_account_creds_exists), hasAccountFile)

                Information(stringResource(R.string.debug_user_id), userId)
                Information(stringResource(R.string.debug_username), username)
                Availability(stringResource(R.string.debug_spotify_premium), isPremium)
            }

            item {
                PreferenceHeader(stringResource(R.string.section_spirc))

                Availability(stringResource(R.string.debug_spirc_usable), isSpircUsable)
                Availability(stringResource(R.string.debug_active_device), isActiveDevice)
            }

            item {
                PreferenceHeader(stringResource(R.string.common_playback))

                Availability(stringResource(R.string.common_playing), isPlaying)
                Availability(stringResource(R.string.debug_buffering), isBuffering)
                Information(stringResource(R.string.debug_current_track), currentTrackName)
                Information(stringResource(R.string.debug_queue_size), queueSize.toString())
            }

            item {
                PreferenceHeader(stringResource(R.string.section_preferences))

                preferences.forEach { (key, value) ->
                    Information(key, value)
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.section_exceptions, exceptions.size))

                if (exceptions.isEmpty()) {
                    Information(stringResource(R.string.debug_no_exceptions), null)
                } else {
                    exceptions.reversed().forEachIndexed { i, ex ->
                        Information("#${exceptions.size - i} ${ex.timestamp}", ex.message)
                        Information(stringResource(R.string.debug_thread), ex.threadName)
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.section_system))

                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                val maxMemory = runtime.maxMemory() / 1048576

                Information(stringResource(R.string.debug_used_memory), usedMemory.toString())
                Information(stringResource(R.string.debug_max_memory), maxMemory.toString())

                Information(stringResource(R.string.debug_pss), memoryInfo.totalPss.toString())
                Information(stringResource(R.string.debug_private_dirty), memoryInfo.totalPrivateDirty.toString())
                Information(stringResource(R.string.debug_shared_dirty), memoryInfo.totalSharedDirty.toString())

                Information(stringResource(R.string.debug_thread_count), threadCount.toString())
                Information(stringResource(R.string.debug_cpu_time), cpuTimeNanos.toString())
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}

@Composable
private fun Availability(text: String, available: Boolean, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = if (available) stringResource(R.string.state_available) else stringResource(R.string.state_unavailable),
            tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(
                alpha = 0.7f
            ),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun Information(text: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = value ?: "null",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
