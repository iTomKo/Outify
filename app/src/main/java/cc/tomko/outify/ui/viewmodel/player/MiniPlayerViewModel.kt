package cc.tomko.outify.ui.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.jam.JamClient
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    jamClient: JamClient,
) : ViewModel() {
    val isJamSession: StateFlow<Boolean> = jamClient.state
        .map { it != null }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = jamClient.state.value != null,
        )

    val jamMemberCount: StateFlow<Number> = jamClient.state
        .map { it?.members?.size ?: 1 }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 1,
        )

    val positionMs: StateFlow<Long> = playbackStateHolder.positionTicks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = playbackStateHolder.estimatePosition().inWholeMilliseconds
        )

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    fun openDevices() {
        GlobalPopupController.show(PopupSpec.PlaybackDevices())
    }

    fun isBuffering(): Flow<Boolean> =
        playbackStateHolder.state
            .map { it.isBuffering }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    fun isActiveDevice(): Flow<Boolean> =
        playbackStateHolder.state
            .map { it.isActiveDevice }

    fun setAudio(audio: PlayableAudio?) {
        if (audio == null) {
            spirc.playerPause()
        }
        playbackStateHolder.setAudio(audio)
    }
}
