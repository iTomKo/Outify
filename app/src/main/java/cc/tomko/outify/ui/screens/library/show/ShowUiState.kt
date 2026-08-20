package cc.tomko.outify.ui.screens.library.show

import androidx.compose.runtime.Immutable
import cc.tomko.outify.core.model.ConsumptionOrder
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Show
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class ShowUiState(
    val isLoading: Boolean = true,
    val show: Show? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoadingMore: Boolean = false,
    val consumptionOrder: ConsumptionOrder? = null,
    val hasMore: Boolean = false,
    val error: String? = null,
)