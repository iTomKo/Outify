package cc.tomko.outify.ui.screens.settings

import android.content.ClipData
import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.R
import cc.tomko.outify.ScreenBottomPadding
import cc.tomko.outify.ui.viewmodel.settings.DebugViewModel
import kotlinx.coroutines.launch

private sealed interface DebugRow {
    val label: String

    /**
     * [value] is what's shown on screen and copied by default.
     * If [sensitiveValue] is set, it replaces [value] in the copied text
     * only when the "include sensitive info" toggle is on.
     */
    data class Text(
        override val label: String,
        val value: String?,
        val sensitiveValue: String? = null,
    ) : DebugRow

    data class Flag(
        override val label: String,
        val available: Boolean,
    ) : DebugRow
}

private data class DebugSectionData(
    val title: String,
    val icon: ImageVector,
    val rows: List<DebugRow>,
    val isError: Boolean = false,
    val footer: (@Composable () -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    val spircState by viewModel.spircState.collectAsState()
    val isSpircUsable by viewModel.isSpircUsable.collectAsState()
    val spircLastError by viewModel.spircLastError.collectAsState()
    val spircDiagnostics by viewModel.spircDiagnostics.collectAsState()
    val spircDiagnosticsError by viewModel.spircDiagnosticsError.collectAsState()
    val isRestarting by viewModel.isRestarting.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val isBuffering by viewModel.isBuffering.collectAsState(initial = true)
    val isActiveDevice by viewModel.isActiveDevice.collectAsState(initial = false)
    val currentTrackName by viewModel.currentAudioName.collectAsState(initial = null)
    val currentTrackUri by viewModel.currentAudioUri.collectAsState(initial = null)
    val queueSize by viewModel.queueSize.collectAsState(initial = 0)
    val preferences by viewModel.preferences.collectAsState()
    val exceptions = viewModel.exceptionCollector.exceptions

    val runtime = Runtime.getRuntime()
    val memoryInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val threadCount = Thread.getAllStackTraces().size
    val cpuTime = Process.getElapsedCpuTime()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var includeSensitive by rememberSaveable { mutableStateOf(false) }
    val copiedMessage = stringResource(R.string.debug_copied)

    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Outify debug", text)))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                snackbarHostState.showSnackbar(copiedMessage)
            }
        }
    }

    val threadLabel = stringResource(R.string.debug_thread)
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
    val maxMemory = runtime.maxMemory() / 1048576

    val sections = buildList {
        add(
            DebugSectionData(
                title = stringResource(R.string.section_general),
                icon = Icons.Rounded.Info,
                rows = listOf(
                    DebugRow.Text(stringResource(R.string.debug_build_number), BuildConfig.VERSION_CODE.toString()),
                    DebugRow.Text(stringResource(R.string.debug_display_density), LocalDensity.current.density.toString()),
                    DebugRow.Text(stringResource(R.string.debug_display_dpi), LocalConfiguration.current.densityDpi.toString()),
                    DebugRow.Text(stringResource(R.string.debug_android_version), Build.VERSION.RELEASE),
                )
            )
        )

        add(
            DebugSectionData(
                title = stringResource(R.string.common_accounts),
                icon = Icons.Rounded.AccountCircle,
                rows = listOf(
                    DebugRow.Flag(stringResource(R.string.debug_playback_logged_in), playbackLoggedIn),
                    DebugRow.Flag(stringResource(R.string.debug_accounts_logged_in), accountsLoggedIn),
                    DebugRow.Flag(stringResource(R.string.debug_playback_creds_exists), hasCredentials),
                    DebugRow.Flag(stringResource(R.string.debug_account_creds_exists), hasAccountFile),
                    DebugRow.Text(
                        label = stringResource(R.string.debug_user_id),
                        value = userId?.redact(),
                        sensitiveValue = userId,
                    ),
                    DebugRow.Text(stringResource(R.string.debug_username), username),
                    DebugRow.Flag(stringResource(R.string.debug_spotify_premium), isPremium),
                )
            )
        )

        add(
            DebugSectionData(
                title = stringResource(R.string.section_spirc),
                icon = Icons.Rounded.Share,
                rows = listOf(
                    DebugRow.Text(stringResource(R.string.debug_spirc_state), spircState.name),
                    DebugRow.Flag(stringResource(R.string.debug_spirc_usable), isSpircUsable),
                    DebugRow.Text(stringResource(R.string.debug_spirc_last_error), spircLastError),
                    DebugRow.Flag(stringResource(R.string.debug_active_device), isActiveDevice),
                ),
                footer = {
                    Button(
                        onClick = viewModel::restartSpirc,
                        enabled = !isRestarting,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(ButtonDefaults.MediumContainerHeight)
                    ) {
                        if (isRestarting) {
                            LoadingIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                        Text(
                            stringResource(
                                if (isRestarting) R.string.debug_spirc_restarting
                                else R.string.debug_spirc_restart
                            )
                        )
                    }
                }
            )
        )

        val nativeRows = buildList {
            if (spircDiagnosticsError != null) {
                add(DebugRow.Text(stringResource(R.string.state_unavailable), spircDiagnosticsError))
            }
            spircDiagnostics.forEach { (key, value) -> add(value.toDebugRow(key)) }
        }
        if (nativeRows.isNotEmpty()) {
            add(
                DebugSectionData(
                    title = stringResource(R.string.debug_spirc_native),
                    icon = Icons.Rounded.Build,
                    rows = nativeRows
                )
            )
        }

        add(
            DebugSectionData(
                title = stringResource(R.string.common_playback),
                icon = Icons.Rounded.PlayArrow,
                rows = listOf(
                    DebugRow.Flag(stringResource(R.string.common_playing), isPlaying),
                    DebugRow.Flag(stringResource(R.string.debug_buffering), isBuffering),
                    DebugRow.Text(stringResource(R.string.debug_current_track), currentTrackName),
                    DebugRow.Text(stringResource(R.string.debug_current_track), currentTrackUri),
                    DebugRow.Text(stringResource(R.string.debug_queue_size), queueSize.toString()),
                )
            )
        )

        val preferenceRows = preferences.map { (key, value) -> value.toDebugRow(key) }
        if (preferenceRows.isNotEmpty()) {
            add(
                DebugSectionData(
                    title = stringResource(R.string.section_preferences),
                    icon = Icons.Rounded.Settings,
                    rows = preferenceRows
                )
            )
        }

        add(
            DebugSectionData(
                title = stringResource(R.string.section_exceptions, exceptions.size),
                icon = Icons.Rounded.Warning,
                isError = exceptions.isNotEmpty(),
                rows = if (exceptions.isEmpty()) {
                    listOf(DebugRow.Text(stringResource(R.string.debug_no_exceptions), null))
                } else {
                    exceptions.reversed().flatMapIndexed { i, ex ->
                        listOf(
                            DebugRow.Text("#${exceptions.size - i} ${ex.timestamp}", ex.message),
                            DebugRow.Text(threadLabel, ex.threadName),
                        )
                    }
                }
            )
        )

        add(
            DebugSectionData(
                title = stringResource(R.string.section_system),
                icon = Icons.Rounded.Build,
                rows = listOf(
                    DebugRow.Text(stringResource(R.string.debug_used_memory), usedMemory.toString()),
                    DebugRow.Text(stringResource(R.string.debug_max_memory), maxMemory.toString()),
                    DebugRow.Text(stringResource(R.string.debug_pss), memoryInfo.totalPss.toString()),
                    DebugRow.Text(stringResource(R.string.debug_private_dirty), memoryInfo.totalPrivateDirty.toString()),
                    DebugRow.Text(stringResource(R.string.debug_shared_dirty), memoryInfo.totalSharedDirty.toString()),
                    DebugRow.Text(stringResource(R.string.debug_thread_count), threadCount.toString()),
                    DebugRow.Text(stringResource(R.string.debug_cpu_time), cpuTime.toString()),
                )
            )
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.common_debug)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copy(sections.joinToString("\n\n") { it.toCopyText(includeSensitive) })
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.debug_copy_all)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            item {
                SensitiveToggle(
                    checked = includeSensitive,
                    onCheckedChange = { includeSensitive = it }
                )
            }

            items(sections) { section ->
                DebugSection(
                    section = section,
                    onRowClick = { row -> copy(row.toCopyText(includeSensitive)) }
                )
            }

            item {
                Spacer(Modifier.height(ScreenBottomPadding))
            }
        }
    }
}

@Composable
private fun SensitiveToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.debug_include_sensitive),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.debug_include_sensitive_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
            // The whole surface is clickable, so the switch itself doesn't handle input
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugSection(
    section: DebugSectionData,
    onRowClick: (DebugRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = section.isError
    val badgeContainer = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val badgeContent = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer
    val badgeShape = remember { MaterialShapes.Cookie9Sided }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(badgeContainer, badgeShape.toShape())
            ) {
                Icon(section.icon, contentDescription = null, tint = badgeContent, modifier = Modifier.size(24.dp))
            }
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            section.rows.forEachIndexed { index, row ->
                val shape = segmentedShape(index, section.rows.size)
                val container = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainerHigh

                Surface(
                    onClick = { onRowClick(row) },
                    shape = shape,
                    color = container,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (row) {
                        is DebugRow.Text -> InfoRow(row.label, row.value)
                        is DebugRow.Flag -> StatusRow(row.label, row.available)
                    }
                }
            }
        }

        section.footer?.invoke()
    }
}

private fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 28.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun StatusRow(text: String, available: Boolean, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (available) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        val pillContainer = if (available) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
        val pillContent = if (available) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer
        val label = stringResource(if (available) R.string.state_available else R.string.state_unavailable)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(pillContainer, CircleShape)
                .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(
                imageVector = if (available) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = null,
                tint = pillContent,
                modifier = Modifier.size(18.dp)
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = pillContent)
        }
    }
}

@Composable
private fun InfoRow(text: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value ?: "null",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

// --- Copy formatting ---

private fun DebugRow.toCopyText(includeSensitive: Boolean): String = when (this) {
    is DebugRow.Text -> {
        val shown = if (includeSensitive) sensitiveValue ?: value else value
        "$label: ${shown ?: "null"}"
    }
    is DebugRow.Flag -> "$label: $available"
}

private fun DebugSectionData.toCopyText(includeSensitive: Boolean): String =
    buildString {
        appendLine("== $title ==")
        rows.forEach { appendLine(it.toCopyText(includeSensitive)) }
    }.trimEnd()

private fun Any?.toDebugRow(key: String): DebugRow = when (this) {
    is Boolean -> DebugRow.Flag(key, this)
    is String -> when (lowercase()) {
        "true" -> DebugRow.Flag(key, true)
        "false" -> DebugRow.Flag(key, false)
        else -> DebugRow.Text(key, this)
    }
    else -> DebugRow.Text(key, this?.toString())
}

fun String.redact(visible: Int = 2): String {
    if (length <= visible * 2) return ".".repeat(length)

    return take(visible) +
            ".".repeat(3) +
            takeLast(visible)
}