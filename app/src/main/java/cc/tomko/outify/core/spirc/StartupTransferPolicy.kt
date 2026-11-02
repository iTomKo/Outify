package cc.tomko.outify.core.spirc

import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.data.repository.PlaybackSettings

/**
 * Checked before activation as well as transfer, so starting Outify does not
 * disturb an existing Jam or playback on another device. Lookup errors propagate:
 * unknown is not evidence that it is safe to take over.
 * The supplied lookup must be read-only; never use start() or invite() here.
 */
internal suspend fun shouldTransferOnStartup(
    settings: PlaybackSettings,
    currentJam: suspend () -> Jam?,
    hasActiveDevice: suspend () -> Boolean,
): Boolean {
    if (!settings.autoTransfer) return false
    if (!settings.smartTransfer) return true
    if (currentJam() != null) return false
    return !hasActiveDevice()
}
