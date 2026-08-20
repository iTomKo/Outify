package cc.tomko.outify.ui.screens.library.track

import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.Track

data class TrackUiState(
    val isLoading: Boolean = true,
    val track: Track? = null,
    val lyrics: List<LyricLine> = emptyList(),
    val error: String? = null,
)
