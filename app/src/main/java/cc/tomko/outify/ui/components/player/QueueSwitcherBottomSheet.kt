package cc.tomko.outify.ui.components.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.data.queue.SavedQueue
import cc.tomko.outify.ui.viewmodel.player.MultiQueueViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueSwitcherBottomSheet(
    viewModel: MultiQueueViewModel,
    currentAudio: PlayableAudio?,
    onDismiss: () -> Unit,
) {
    val queues by viewModel.queues.collectAsState()
    val activeQueueId by viewModel.activeQueueId.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SavedQueue?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                        .size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Saved queues",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        fontWeight = FontWeight.Black,
                    )
                    if (queues.isNotEmpty()) {
                        Text(
                            text = "${queues.size} queue${if (queues.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Empty
            if (queues.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No saved queues yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queues, key = { it.id }) { queue ->
                        SavedQueueRow(
                            queue = queue,
                            isActive = queue.id == activeQueueId,
                            onActivate = {
                                viewModel.activateQueue(queue.id)
                                onDismiss()
                            },
                            onDelete = { viewModel.deleteQueue(queue.id) },
                            onRename = { renameTarget = queue },
                        )
                    }
                }
            }

            // Save queue
            FilledTonalButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save current queue")
            }

            Spacer(Modifier.height(6.dp))
        }
    }

    if (showSaveDialog) {
        QueueNameDialog(
            title = "Save queue",
            confirmLabel = "Save",
            onConfirm = { name ->
                viewModel.saveCurrentQueue(name, currentAudio)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    renameTarget?.let { target ->
        QueueNameDialog(
            title = "Rename queue",
            confirmLabel = "Rename",
            initialValue = target.name,
            onConfirm = { newName ->
                viewModel.renameQueue(target.id, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedQueueRow(
    queue: SavedQueue,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onActivate, onLongClick = onRename)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = queue.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${queue.trackUris.size} tracks · ${relativeTime(queue.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(36.dp)
                        .padding(8.dp)
                )
            } else {
                IconButton(onClick = onActivate) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Activate queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun QueueNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Queue name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}