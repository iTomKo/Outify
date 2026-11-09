package cc.tomko.outify.ui.components.bottomsheet.jam

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.viewmodel.bottomsheet.JamAction
import cc.tomko.outify.ui.viewmodel.bottomsheet.PlaybackDevicesViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JamManageBottomSheet(
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
    val screenshotMode = LocalUiSettings.current.screenshotMode
    val developerName = stringResource(R.string.screenshot_mode_username)
    val members = remember(session?.members, screenshotMode, developerName) {
        session?.members.orEmpty().map { member ->
            if (screenshotMode && member.isYou) member.copy(name = developerName, imageUrl = "")
            else member
        }.sortedByDescending { it.isHost }
    }
    var inviteUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(available) {
        if (available) viewModel.refreshJam()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "header") {
                JamHeader(
                    members = members,
                    memberCount = session?.members?.size,
                )
            }

            if (error != null) {
                item(key = "error") {
                    JamErrorBanner(message = error.orEmpty())
                }
            }

            if (session == null) {
                item(key = "start") {
                    JamStartCard(
                        starting = action == JamAction.Starting,
                        enabled = available && action == null,
                        available = available,
                        onStart = viewModel::startJam,
                    )
                }
            } else {
                items(members.size, key = { "member_${members[it].id}" }) { index ->
                    val member = members[index]
                    JamMemberRow(
                        member = member,
                        shape = segmentShape(index, members.size),
                        canKick = session.isHost && !member.isYou,
                        onKick = { viewModel.kickMember(member) },
                    )
                }
                item(key = "actions") {
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

    inviteUrl?.let { url ->
        JamInviteDialog(url = url, onDismiss = { inviteUrl = null })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamHeader(
    members: List<JamMemberUi>,
    memberCount: Int?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (members.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            JamAvatarCluster(members = members)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.jam_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (memberCount == null)
                stringResource(R.string.jam_not_in_jam)
            else
                pluralStringResource(R.plurals.jam_listeners, memberCount, memberCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Overlapping avatars: host is a cookie, everyone else a circle. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamAvatarCluster(
    members: List<JamMemberUi>,
    maxVisible: Int = 4,
    avatarSize: Dp = 92.dp,
) {
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    val visible = members.take(maxVisible)
    val extra = members.size - visible.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-18).dp),
    ) {
        visible.forEach { member ->
            JamAvatar(
                name = member.name,
                imageUrl = member.imageUrl,
                isHost = member.isHost,
                size = avatarSize,
                shape = if (member.isHost) MaterialShapes.Cookie9Sided.toShape() else CircleShape,
                ringColor = ring,
                ringWidth = 4.dp,
            )
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .background(ring, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JamAvatar(
    name: String,
    imageUrl: String,
    isHost: Boolean,
    size: Dp,
    shape: Shape,
    ringColor: Color,
    ringWidth: Dp,
) {
    val container = if (isHost) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isHost) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    Box(
        modifier = Modifier
            .size(size)
            .background(ringColor, shape)
            .padding(ringWidth)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        // Initial shows while loading / when there is no picture
        Text(
            text = name.take(1).uppercase().ifEmpty { "?" },
            style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
        if (imageUrl.isNotEmpty()) {
            SmartImage(
                url = imageUrl,
                imageSize = size - ringWidth * 2,
                shape = shape,
            )
        }
    }
}

/* Error */

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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/* Not in a Jam */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamStartCard(
    starting: Boolean,
    enabled: Boolean,
    available: Boolean,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.jam_start_headline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (available) R.string.jam_start_body else R.string.jam_unavailable
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ButtonDefaults.LargeContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
        ) {
            if (starting) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            } else {
                Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.jam_start),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/* In a Jam */

/** Large outer corners on the first/last row, small inner corners in between. */
private fun segmentShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamMemberRow(
    member: JamMemberUi,
    shape: Shape,
    canKick: Boolean,
    onKick: () -> Unit,
) {
    val container = if (member.isYou) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (member.isYou) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JamAvatar(
                name = member.name,
                imageUrl = member.imageUrl,
                isHost = member.isHost,
                size = 48.dp,
                shape = if (member.isHost) MaterialShapes.Cookie9Sided.toShape() else CircleShape,
                ringColor = Color.Transparent,
                ringWidth = 0.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.isYou) {
                    Text(
                        text = stringResource(R.string.jam_you),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.7f),
                    )
                }
            }

            if (member.isHost) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = stringResource(R.string.jam_host),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            } else if (canKick) {
                IconButton(onClick = onKick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.jam_kick_member, member.name),
                    )
                }
            }
        }
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
    val height = ButtonDefaults.MediumContainerHeight
    val padding = ButtonDefaults.contentPaddingFor(height)
    val iconSize = ButtonDefaults.iconSizeFor(height)
    val iconSpacing = ButtonDefaults.iconSpacingFor(height)
    val textStyle = ButtonDefaults.textStyleFor(height)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isHost) {
            FilledTonalButton(
                onClick = onInvite,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = height),
                contentPadding = padding,
            ) {
                if (action == JamAction.Inviting) {
                    LoadingIndicator(modifier = Modifier.size(iconSize + 4.dp))
                } else {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(iconSize))
                    Spacer(Modifier.width(iconSpacing))
                    Text(
                        text = stringResource(R.string.jam_invite),
                        style = textStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Button(
            onClick = onLeave,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = height),
            contentPadding = padding,
        ) {
            if (action == JamAction.Leaving) {
                LoadingIndicator(modifier = Modifier.size(iconSize + 4.dp))
            } else {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(iconSize))
                Spacer(Modifier.width(iconSpacing))
                Text(
                    text = stringResource(if (isHost) R.string.jam_end else R.string.jam_leave),
                    style = textStyle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* Invite popup */

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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
