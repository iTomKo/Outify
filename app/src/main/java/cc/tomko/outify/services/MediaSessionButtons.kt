package cc.tomko.outify.services

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import cc.tomko.outify.MediaSessionConstants

@UnstableApi
internal fun shuffleNotificationButton(
    enabled: Boolean,
    hasAudio: Boolean,
    displayName: String,
): CommandButton = CommandButton.Builder(
    if (enabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
)
    .setDisplayName(displayName)
    .setSessionCommand(MediaSessionConstants.CommandToggleShuffle)
    .setEnabled(hasAudio)
    .build()
