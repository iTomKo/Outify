package cc.tomko.outify.ui.components.bottomsheet

import cc.tomko.outify.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ui.notifications.InAppNotificationController
import cc.tomko.outify.ui.viewmodel.bottomsheet.CreatePlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreatePlaylistBottomSheet(
    viewModel: CreatePlaylistViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    initialName: String = "",
    initialDescription: String = "",
    initialPublic: Boolean = true,
    initialCollaborative: Boolean = false,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()
    val isEditMode = playlistId != null

    var name by remember(playlistId) { mutableStateOf(initialName) }
    var description by remember(playlistId) { mutableStateOf(initialDescription) }
    var isPublic by remember(playlistId) { mutableStateOf(initialPublic) }
    var isCollaborative by remember(playlistId) { mutableStateOf(initialCollaborative) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.result.collect { result ->
            isSaving = false
            result.fold(
                onSuccess = { id ->
                    if (isEditMode) {
                        InAppNotificationController.show(context.getString(R.string.toast_playlist_updated), durationMillis = 2000L)
                    }
                    onDismiss()
                    onCreated(id)
                },
                onFailure = { error ->
                    val message = error.message ?: context.getString(R.string.error_unknown)
                    InAppNotificationController.show(message, durationMillis = 3000L)
                }
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) {
                coroutineScope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                        .size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = if (isEditMode) stringResource(R.string.createplaylist_edit_title) else stringResource(R.string.createplaylist_create_title),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    fontWeight = FontWeight.Black,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.playlist_desc_optional)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.playlist_public),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (isPublic) "Anyone can see this playlist" else "Only you can see this playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isPublic,
                    onCheckedChange = { isPublic = it },
                    enabled = !isCollaborative,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.playlist_collaborative),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.playlist_others),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isCollaborative,
                    onCheckedChange = { collaborative ->
                        isCollaborative = collaborative
                        if (collaborative) isPublic = false
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Button(
                    onClick = {
                        isSaving = true
                        if (isEditMode) {
                            viewModel.modifyPlaylist(
                                playlistId = playlistId,
                                name = name.trim(),
                                description = description.trim().ifEmpty { "" },
                                isPublic = isPublic,
                                isCollaborative = isCollaborative,
                            )
                        } else {
                            viewModel.createPlaylist(
                                name = name.trim(),
                                description = description.trim().ifEmpty { "" },
                                isPublic = isPublic,
                                isCollaborative = isCollaborative,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank() && !isSaving,
                ) {
                    Text(
                        when {
                            isSaving && isEditMode -> stringResource(R.string.createplaylist_saving)
                            isSaving -> stringResource(R.string.createplaylist_creating)
                            isEditMode -> stringResource(R.string.common_save)
                            else -> stringResource(R.string.common_create)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
