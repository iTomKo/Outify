package cc.tomko.outify.ui.viewmodel.bottomsheet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.database.PlaylistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CreatePlaylistViewModel @Inject constructor(
    private val spClient: SpClient,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    private val _result = MutableSharedFlow<Result<String>>(extraBufferCapacity = 1)
    val result: SharedFlow<Result<String>> = _result

    fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val playlistId = withContext(Dispatchers.IO) {
                    spClient.createPlaylist(name, description ?: "", isPublic, isCollaborative)
                } ?: return@launch

                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = playlistId,
                        uri = "spotify:playlist:$playlistId",
                        ownerUsername = withContext(Dispatchers.IO) { spClient.username() } ?: "",
                        revision = "",
                        name = name,
                        description = description ?: "",
                        pictureId = "",
                        isCollaborative = isCollaborative,
                        isDeletedByOwner = false,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                _result.tryEmit(Result.success(playlistId))
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }

    fun modifyPlaylist(
        playlistId: String,
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborative: Boolean
    ) {
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    spClient.modifyPlaylist(
                        playlistId,
                        name,
                        description ?: "",
                        isPublic,
                        isCollaborative
                    )
                }
                if (status == 200) {
                    playlistDao.upsertPlaylist(
                        PlaylistEntity(
                            id = playlistId,
                            uri = "spotify:playlist:$playlistId",
                            ownerUsername = withContext(Dispatchers.IO) { spClient.username() } ?: "",
                            revision = "",
                            name = name,
                            description = description ?: "",
                            pictureId = "",
                            isCollaborative = isCollaborative,
                            isDeletedByOwner = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    _result.tryEmit(Result.success(playlistId))
                } else {
                    Log.w("CreatePlaylistViewModel", "Failed to modify with status code: $status")
                    val message = when (status) {
                        403 -> "You don't have permission to modify this playlist"
                        else -> "Failed to modify playlist (status: $status)"
                    }
                    _result.tryEmit(Result.failure(RuntimeException(message)))
                }
            } catch (e: Exception) {
                _result.tryEmit(Result.failure(e))
            }
        }
    }
}
