package cc.tomko.outify.ui.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.queue.SavedQueue
import cc.tomko.outify.data.repository.SavedQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MultiQueueViewModel @Inject constructor(
    private val repository: SavedQueueRepository,
    private val spirc: SpircWrapper,
    private val json: Json,
) : ViewModel() {

    val queues: StateFlow<List<SavedQueue>> = repository.queues
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeQueueId: StateFlow<String?> = repository.activeQueueId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Snapshot the current live spirc queue and persist it under [name].
     * [currentAudio] is passed from the UI because this VM does not own PlaybackStateHolder.
     */
    fun saveCurrentQueue(name: String, currentAudio: PlayableAudio?) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousUris: List<String> = try {
                json.decodeFromString(spirc.previousTracks())
            } catch (_: Exception) {
                emptyList()
            }

            val nextUris: List<String> = try {
                json.decodeFromString(spirc.nextTracks())
            } catch (_: Exception) {
                emptyList()
            }

            val allUris = buildList {
                addAll(previousUris)
                currentAudio?.uri?.let { add(it) }
                addAll(nextUris)
            }
            if (allUris.isEmpty()) return@launch

            val queue = SavedQueue(
                id = UUID.randomUUID().toString(),
                name = name,
                trackUris = allUris,
                currentIndex = previousUris.size,
                createdAt = System.currentTimeMillis(),
            )
            repository.saveQueue(queue)
            repository.setActiveQueueId(queue.id)
        }
    }

    /**
     * Restore a saved queue into spirc.
     *
     * Loads the track at [SavedQueue.currentIndex] as the playing track,
     * then enqueues every track after it via addToQueue.
     *
     * Tracks before currentIndex are not restorable — librespot-rust's
     * previous-list is internal state that cannot be injected from outside.
     */
    fun activateQueue(id: String) {
        val queue = repository.getQueue(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val uris = queue.trackUris
            if (uris.isEmpty()) return@launch

            spirc.setQueue(uris.toTypedArray())

            delay(300L)
            repository.setActiveQueueId(id)
        }
    }

    fun deleteQueue(id: String) = repository.deleteQueue(id)

    fun renameQueue(id: String, newName: String) = repository.renameQueue(id, newName)

    fun clearActive() = repository.setActiveQueueId(null)
}