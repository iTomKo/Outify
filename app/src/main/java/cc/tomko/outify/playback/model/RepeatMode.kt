package cc.tomko.outify.playback.model

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

    companion object {
        fun fromSettings(repeat: Boolean, repeatTrack: Boolean): RepeatMode =
            when {
                repeatTrack -> ONE
                repeat -> ALL
                else -> NONE
            }
    }
}