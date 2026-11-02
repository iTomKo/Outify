package cc.tomko.outify.core.spirc

import cc.tomko.outify.playback.model.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Seed the native load options before publishing readiness.
 */
internal suspend fun restorePlaybackModes(
    shuffleEnabled: Flow<Boolean>,
    repeatMode: Flow<RepeatMode>,
    applyShuffle: (Boolean) -> Boolean,
    applyRepeat: (Boolean, Boolean) -> Boolean,
    onReady: suspend (RepeatMode) -> Unit,
) {
    val shuffle = shuffleEnabled.first()
    val repeat = repeatMode.first()
    check(applyShuffle(shuffle)) { "Could not restore saved shuffle mode" }
    check(applyRepeat(repeat.repeat, repeat.repeatTrack)) { "Could not restore saved repeat mode" }
    onReady(repeat)
}
