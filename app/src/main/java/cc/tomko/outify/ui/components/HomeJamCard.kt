package cc.tomko.outify.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.core.jam.JamMember
import cc.tomko.outify.ui.components.user.UserChipAvatar

/** Opens Jam controls; never starts a session or playback just by tapping. */
@Composable
internal fun HomeJamCard(
    jam: Jam?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = jam != null
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (active) colors.tertiaryContainer else colors.surfaceContainerLow,
        contentColor = if (active) colors.onTertiaryContainer else colors.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) colors.tertiary else colors.secondaryContainer,
                contentColor = if (active) colors.onTertiary else colors.onSecondaryContainer,
            ) {
                Icon(
                    Icons.Default.Group,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (active) R.string.home_jam_active else R.string.jam_start_headline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (jam == null) stringResource(R.string.home_jam_description)
                        else pluralStringResource(R.plurals.jam_listeners, jam.members.size, jam.members.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) colors.onTertiaryContainer else colors.onSurfaceVariant,
                    )

                    jam?.members?.take(6)?.forEach { mem ->
                        UserChipAvatar(
                            artworkUrl = mem.image,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (active) R.string.jam_manage else R.string.jam_start),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) colors.onTertiaryContainer else colors.primary,
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
