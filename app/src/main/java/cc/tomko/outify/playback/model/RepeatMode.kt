package cc.tomko.outify.playback.model

import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE

enum class RepeatMode(
    val repeat: Boolean,
    val repeatTrack: Boolean,
) {
    NONE(false, false),
    ALL(true, false),
    ONE(true, true);

    fun next(): RepeatMode =
        when (this) {
            NONE -> ALL
            ALL -> ONE
            ONE -> NONE
        }

    fun toMediaRepeatMode(): Int =
        when (this) {
            NONE -> REPEAT_MODE_OFF
            ALL -> REPEAT_MODE_ALL
            ONE -> REPEAT_MODE_ONE
        }

    companion object {
        fun fromSettings(repeat: Boolean, repeatTrack: Boolean): RepeatMode =
            when {
                repeatTrack -> ONE
                repeat -> ALL
                else -> NONE
            }
    }
}