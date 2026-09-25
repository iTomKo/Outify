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
import androidx.compose.material.icons.filled.CreateNewFolder
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.core.model.PlaylistFolder
import cc.tomko.outify.ui.components.ColorPicker
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreateFolderBottomSheet(
    onDismiss: () -> Unit,
    onCreated: (PlaylistFolder) -> Unit,
    initialName: String = "",
    initialColor: Color = Color(0xFF1DB954),
    folderId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()
    val isEditMode = folderId != null

    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                    Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(selectedColor)
                        .padding(12.dp)
                        .size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = if (isEditMode) "Edit Folder" else "Create Folder",
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    fontWeight = FontWeight.Black,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.folder_color),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            ColorPicker(
                initial = selectedColor,
                onColorChanged = { selectedColor = it },
                preview = false,

                )

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
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Button(
                    onClick = {
                        val id = folderId ?: UUID.randomUUID().toString()
                        val folder = PlaylistFolder(
                            id = id,
                            name = name.trim(),
                            color = selectedColor.toArgb().toLong(),
                        )
                        onCreated(folder)
                        coroutineScope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = name.isNotBlank(),
                ) {
                    Text(if (isEditMode) stringResource(R.string.common_save) else stringResource(R.string.common_create))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

private fun Color.toArgb(): Int {
    return (alpha * 255).toInt() shl 24 or
            (red * 255).toInt() shl 16 or
            (green * 255).toInt() shl 8 or
            (blue * 255).toInt()
}
