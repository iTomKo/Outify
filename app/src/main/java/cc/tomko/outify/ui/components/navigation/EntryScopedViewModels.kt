package cc.tomko.outify.ui.components.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.savedstate.compose.LocalSavedStateRegistryOwner

private const val ENTRY_SCOPED_VIEW_MODELS = "cc.tomko.outify.entryScopedViewModels"

/**
 * Entry metadata that gives the entry its own ViewModelStore, cleared when the entry is popped.
 * Entries without it keep resolving view models against the Activity.
 */
internal val EntryScopedViewModels: Map<String, Any> = mapOf(ENTRY_SCOPED_VIEW_MODELS to true)

/**
 * Same mechanism as the lifecycle-viewmodel-navigation3 ViewModelStoreNavEntryDecorator, limited
 * to entries marked with [EntryScopedViewModels]. The [provider] should be remembered above
 * NavDisplay so the stores survive NavDisplay leaving composition (e.g. orientation layouts).
 */
@Composable
internal fun <T : Any> rememberEntryScopedViewModelDecorator(
    provider: ViewModelStoreProvider,
): NavEntryDecorator<T> = remember(provider) {
    NavEntryDecorator(
        onPop = { contentKey -> provider.clearKey(contentKey) },
        decorate = { entry ->
            if (entry.metadata[ENTRY_SCOPED_VIEW_MODELS] == true) {
                val owner = rememberViewModelStoreOwner(
                    key = entry.contentKey,
                    provider = provider,
                    savedStateRegistryOwner = LocalSavedStateRegistryOwner.current,
                )
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    entry.Content()
                }
            } else {
                entry.Content()
            }
        },
    )
}
