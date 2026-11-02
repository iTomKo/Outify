package cc.tomko.outify.ui.components.bottomsheet

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.core.jam.JamMember
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.viewmodel.bottomsheet.JamAction
import cc.tomko.outify.ui.viewmodel.bottomsheet.PlaybackDevicesViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ---------- UI models ---------- */

data class JamMemberUi(
    val id: String,
    val name: String,
    val imageUrl: String,
    val isHost: Boolean,
    val isYou: Boolean,
)

data class JamSessionUi(
    val members: List<JamMemberUi>,
    val isHost: Boolean,
)

/* ---------- Sheet ---------- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JamBottomSheet(
    viewModel: PlaybackDevicesViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val jam by viewModel.jam.collectAsState()
    val action by viewModel.jamAction.collectAsState()
    val error by viewModel.jamError.collectAsState()
    val available by viewModel.jamAvailable.collectAsState(initial = false)

    val session = jam.toUi()
    var inviteUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(available) {
        if (available) viewModel.refreshJam()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JamHeader(memberCount = session?.members?.size)

            AnimatedVisibility(visible = error != null) {
                JamErrorBanner(message = error.orEmpty())
            }

            AnimatedContent(
                targetState = session != null,
                label = "jam_content",
            ) { inJam ->
                if (!inJam) {
                    JamStartCard(
                        starting = action == JamAction.Starting,
                        enabled = available && action == null,
                        available = available,
                        onStart = viewModel::startJam,
                    )
                } else if (session != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        JamMemberList(
                            members = session.members,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        JamActions(
                            isHost = session.isHost,
                            action = action,
                            onInvite = {
                                scope.launch { inviteUrl = viewModel.inviteToJam() }
                            },
                            onLeave = viewModel::leaveJam,
                        )
                    }
                }
            }
        }
    }

    inviteUrl?.let { url ->
        JamInviteDialog(url = url, onDismiss = { inviteUrl = null })
    }
}

/* ---------- Header ---------- */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamHeader(memberCount: Int?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Default.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(12.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.jam_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (memberCount == null)
                    stringResource(R.string.jam_not_in_jam)
                else
                    pluralStringResource(R.plurals.jam_listeners, memberCount, memberCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JamErrorBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/* ---------- Not in a Jam ---------- */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamStartCard(
    starting: Boolean,
    enabled: Boolean,
    available: Boolean,
    onStart: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.GroupAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(24.dp)
                    .size(32.dp),
            )
            Text(
                text = stringResource(R.string.jam_start_headline),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (available) R.string.jam_start_body else R.string.jam_unavailable
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (starting) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.jam_start), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/* ---------- In a Jam ---------- */

@Composable
private fun JamMemberList(members: List<JamMemberUi>, modifier: Modifier = Modifier) {
    // Host first, then the rest in their original order.
    val sorted = remember(members) { members.sortedByDescending { it.isHost } }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(sorted, key = { _, m -> m.id }) { index, member ->
            JamMemberRow(member, groupedShape(index, sorted.size))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamMemberRow(member: JamMemberUi, shape: Shape) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                val avatarShape: Shape =
                    if (member.isHost) MaterialShapes.Cookie12Sided.toShape() else CircleShape
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(avatarShape)
                        .background(
                            if (member.isHost) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (member.imageUrl.isNotEmpty()) {
                        SmartImage(
                            url = member.imageUrl,
                            imageSize = 44.dp,
                        )
                    } else {
                        Text(
                            text = member.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (member.isHost) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            },
            headlineContent = {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = if (member.isYou) {
                { Text(stringResource(R.string.jam_you)) }
            } else null,
            trailingContent = if (member.isHost) {
                {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) {
                        Text(
                            text = stringResource(R.string.jam_host),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            } else null,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamActions(
    isHost: Boolean,
    action: JamAction?,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
) {
    val busy = action != null

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isHost) {
            FilledTonalButton(
                onClick = onInvite,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                if (action == JamAction.Inviting) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.jam_invite))
            }
        }

        Button(
            onClick = onLeave,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        ) {
            if (action == JamAction.Leaving) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (isHost) R.string.jam_end else R.string.jam_leave))
        }
    }
}

/* ---------- Invite popup ---------- */

@Composable
private fun JamInviteDialog(url: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        icon = { Icon(Icons.Default.QrCode2, contentDescription = null) },
        title = { Text(stringResource(R.string.jam_invite_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.jam_invite_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // QR codes need a light background + quiet zone to scan reliably,
                // regardless of the app theme.
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                    QrCode(
                        content = url,
                        modifier = Modifier
                            .padding(16.dp)
                            .size(220.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Jam link", url))
                        )
                        copied = true
                    }
                },
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (copied) R.string.jam_copied else R.string.jam_copy_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf<EncodeHintType, Any>(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
    }

    Canvas(modifier = modifier) {
        val cell = size.width / matrix.width
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        // tiny overdraw avoids hairline seams between modules
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/* ---------- Helpers ---------- */

/** Expressive "grouped list" look: big outer corners, tight inner corners. */
private fun groupedShape(index: Int, count: Int): Shape {
    val outer = 20.dp
    val inner = 4.dp
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}

private fun Jam?.toUi(): JamSessionUi? = this?.let { jam ->
    JamSessionUi(
        isHost = jam.owner,
        members = jam.members.map { m ->
            JamMemberUi(
                id = m.id,
                name = m.name,
                imageUrl = m.image ?: "",
                isHost = m.host,
                isYou = m.you,
            )
        },
    )
}