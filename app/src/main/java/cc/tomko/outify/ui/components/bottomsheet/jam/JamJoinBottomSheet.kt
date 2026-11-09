package cc.tomko.outify.ui.components.bottomsheet.jam

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.viewmodel.bottomsheet.JamInfoViewModel
import coil3.compose.AsyncImage

data class JamUser(
    val name: String,
    val avatarUrl: String?,
)

enum class JamListenTarget(val rustValue: String) {
    ThisDevice("ListenAndControl"),
    RemoteDevice("Control")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JoinJamBottomSheet(
    viewModel: JamInfoViewModel,
    jamToken: String,
    onDismiss: () -> Unit,
    onJoin: (JamListenTarget) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isJoining = uiState.isJoining || uiState.hasJoined
    var target by rememberSaveable(jamToken) { mutableStateOf(JamListenTarget.ThisDevice) }

    LaunchedEffect(viewModel, jamToken) {
        viewModel.load(jamToken)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.token != jamToken || uiState.isLoading) {
                LoadingIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.common_loading))
                return@Column
            }
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::retry) {
                    Text(stringResource(R.string.common_retry))
                }
                return@Column
            }
            val screenshotMode = LocalUiSettings.current.screenshotMode
            val developer = JamUser(stringResource(R.string.screenshot_mode_username), null)
            val currentUser = uiState.currentUser?.let {
                if (screenshotMode) developer else it
            } ?: return@Column
            val host = uiState.host?.let {
                if (screenshotMode && uiState.jam?.members?.any { member -> member.host && member.you } == true)
                    developer else it
            } ?: return@Column

            AvatarPair(
                currentUser = currentUser,
                host = host,
                ringColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Join ${host.name}'s Jam",
                style = MaterialTheme.typography.headlineSmallEmphasized,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Listen together in real time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ListenOption(
                    icon = Icons.Rounded.Smartphone,
                    title = "Listen on my device",
                    supporting = "Audio plays here",
                    selected = target == JamListenTarget.ThisDevice,
                    enabled = !isJoining,
                    onClick = { target = JamListenTarget.ThisDevice },
                )
                ListenOption(
                    icon = Icons.Rounded.Speaker,
                    title = "Listen on ${host.name}'s device",
                    supporting = "Audio plays where the Jam is hosted",
                    selected = target == JamListenTarget.RemoteDevice,
                    enabled = !isJoining,
                    onClick = { target = JamListenTarget.RemoteDevice },
                )
            }

            Spacer(Modifier.height(24.dp))

            uiState.joinError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = { onJoin(target) },
                enabled = !isJoining,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ButtonDefaults.LargeContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
            ) {
                AnimatedContent(targetState = isJoining, label = "jamJoinButton") { pending ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pending) {
                            LoadingIndicator(
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(if (pending) R.string.jam_joining else R.string.jam_join),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AvatarPair(
    currentUser: JamUser,
    host: JamUser,
    ringColor: Color,
    avatarSize: Dp = 80.dp,
) {
    val overlap = 14.dp

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Current user: circle
        Avatar(
            user = currentUser,
            size = avatarSize,
            shape = CircleShape,
            ringColor = ringColor,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        // Remote user: expressive cookie, tucked in over the first avatar
        Box(
            modifier = Modifier.offset(x = -overlap),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(
                user = host,
                size = avatarSize,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                ringColor = ringColor,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Avatar(
    user: JamUser,
    size: Dp,
    shape: Shape,
    ringColor: Color,
    containerColor: Color,
    contentColor: Color,
    ringWidth: Dp = 4.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(ringColor, shape)
            .padding(ringWidth)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        // Initial shows while loading / when there is no picture
        Text(
            text = user.name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        if (user.avatarUrl != null) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = user.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun ListenOption(
    icon: ImageVector,
    title: String,
    supporting: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) 28.dp else 14.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "optionCorner",
    )
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "optionContainer",
    )
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Row handles the click, so the radio is purely visual
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
