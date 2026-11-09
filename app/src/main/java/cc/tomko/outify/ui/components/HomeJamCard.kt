package cc.tomko.outify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.core.jam.JamMember

/** Opens Jam controls; never starts a session or playback just by tapping. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeJamCard(
    jam: Jam?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = jam != null
    val colors = MaterialTheme.colorScheme

    // Card morphs to a tighter corner while pressed
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 16.dp else if (active) 28.dp else 32.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "jamCardCorner",
    )
    val container by animateColorAsState(
        targetValue = if (active) colors.tertiaryContainer else colors.surfaceContainerHigh,
        label = "jamCardContainer",
    )
    val content = if (active) colors.onTertiaryContainer else colors.onSurface

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
    ) {
        if (jam != null) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(colors.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        tint = colors.onTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_jam_active),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.jam_listeners, jam.members.size, jam.members.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (jam.members.isNotEmpty()) {
                    JamMemberStack(
                        members = jam.members,
                        ringColor = container,
                        maxVisible = 3,
                        avatarSize = 34.dp,
                        overlap = 10.dp,
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.jam_manage),
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(colors.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        tint = colors.onSecondaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.jam_start_headline),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.home_jam_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    JamCardPill(
                        text = stringResource(R.string.jam_start),
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun JamCardPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Overlapping member avatars: host is a cookie, everyone else a circle. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JamMemberStack(
    members: List<JamMember>,
    ringColor: Color,
    maxVisible: Int = 5,
    avatarSize: Dp = 40.dp,
    overlap: Dp = 10.dp,
) {
    val visible = members.take(maxVisible)
    val extra = members.size - visible.size

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(-overlap),
    ) {
        visible.forEach { member ->
            val hideProfile = LocalUiSettings.current.screenshotMode && member.you
            StackAvatar(
                name = if (hideProfile) stringResource(R.string.screenshot_mode_username) else member.name,
                imageUrl = if (hideProfile) null else member.image,
                isHost = member.host,
                size = avatarSize,
                shape = if (member.host) MaterialShapes.Cookie9Sided.toShape() else CircleShape,
                ringColor = ringColor,
            )
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .background(ringColor, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StackAvatar(
    name: String,
    imageUrl: String?,
    isHost: Boolean,
    size: Dp,
    shape: Shape,
    ringColor: Color,
    ringWidth: Dp = 3.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .background(ringColor, shape)
            .padding(ringWidth)
            .clip(shape)
            .background(if (isHost) colors.primaryContainer else colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        // Initial shows while loading / when there is no picture
        Text(
            text = name.take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isHost) colors.onPrimaryContainer else colors.onSecondaryContainer,
        )
        if (!imageUrl.isNullOrEmpty()) {
            SmartImage(
                url = imageUrl,
                imageSize = size - ringWidth * 2,
                shape = shape,
            )
        }
    }
}
