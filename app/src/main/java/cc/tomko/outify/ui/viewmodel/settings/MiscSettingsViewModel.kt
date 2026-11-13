package cc.tomko.outify.ui.viewmodel.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.data.repository.BackupRepository
import cc.tomko.outify.data.repository.CollectionSessionState
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.PendingBackupImport
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.SyncNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Progress(val current: Int, val total: Int) : SyncStatus()
    data object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

sealed class BackupStatus {
    data object Idle : BackupStatus()
    data object Exporting : BackupStatus()
    data object Importing : BackupStatus()
    data class Success(val message: String) : BackupStatus()
    data class Error(val message: String) : BackupStatus()
}

@HiltViewModel
class MiscSettingsViewModel @Inject constructor(
    private val likedRepository: LikedRepository,
    private val collectionSessions: CollectionSessionState,
    private val syncNotificationManager: SyncNotificationManager,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val savedQueueRepository: SavedQueueRepository,
) : ViewModel() {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _likedCount = MutableStateFlow(0)
    val likedCount: StateFlow<Int> = _likedCount.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    fun setScreenshotMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScreenshotMode(enabled)
        }
    }

    init {
        viewModelScope.launch {
            likedRepository.observeCount().collect { count ->
                _likedCount.value = count
            }
        }
        viewModelScope.launch {
            collectionSessions.current.collect { lease ->
                _isAuthenticated.value = lease != null
            }
        }
        viewModelScope.launch {
            PendingBackupImport.uri.collect { uri: Uri ->
                importBackup(uri)
            }
        }
        checkAuthState()
    }

    fun checkAuthState() {
        _isAuthenticated.value = collectionSessions.current.value != null
    }

    fun syncLikedTracks() {
        if (_syncStatus.value is SyncStatus.Syncing || _syncStatus.value is SyncStatus.Progress) return

        viewModelScope.launch {
            if (collectionSessions.current.value == null) {
                _syncStatus.value = SyncStatus.Error("Please log in for playback first")
                return@launch
            }

            syncNotificationManager.showIndeterminate()
            _syncStatus.value = SyncStatus.Syncing
            try {
                var totalTracks = 0
                val urisSynced = likedRepository.syncLikedTracks(
                    forceSync = true,
                    onProgress = { current, total ->
                        totalTracks = total
                        _syncStatus.value = SyncStatus.Progress(current, total)
                        syncNotificationManager.showProgress(current, total)
                    }
                )
                if (urisSynced) {
                    _syncStatus.value = SyncStatus.Success
                    syncNotificationManager.showComplete(totalTracks)
                } else {
                    _syncStatus.value = SyncStatus.Error("Failed to sync liked tracks")
                    syncNotificationManager.showError("Failed to sync liked tracks")
                }
            } catch (cancelled: CancellationException) {
                _syncStatus.value = SyncStatus.Idle
                syncNotificationManager.cancel()
                throw cancelled
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
                syncNotificationManager.showError(e.message ?: "Unknown error")
            }

            launch {
                try {
                    likedRepository.syncLikedEpisodes(forceSync = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("MiscSettingsViewModel", "syncLikedEpisodes failed", error)
                }
            }

            launch {
                try {
                    likedRepository.syncLikedShows(forceSync = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("MiscSettingsViewModel", "syncLikedShows failed", error)
                }
            }

            launch {
                delay(3000)
                if (_syncStatus.value is SyncStatus.Success || _syncStatus.value is SyncStatus.Error) {
                    _syncStatus.value = SyncStatus.Idle
                }
            }
        }
    }

    fun resetStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    fun exportBackup(uri: Uri) {
        if (_backupStatus.value is BackupStatus.Exporting) return
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.Exporting
            backupRepository.exportBackup(uri, BuildConfig.VERSION_NAME)
                .onSuccess {
                    _backupStatus.value = BackupStatus.Success("Backup saved")
                }
                .onFailure { e ->
                    _backupStatus.value = BackupStatus.Error(e.message ?: "Export failed")
                }
            delay(3000)
            _backupStatus.value = BackupStatus.Idle
        }
    }

    fun importBackup(uri: Uri) {
        if (_backupStatus.value is BackupStatus.Importing) return
        viewModelScope.launch {
            _backupStatus.value = BackupStatus.Importing
            backupRepository.importBackup(uri)
                .onSuccess {
                    savedQueueRepository.reload()
                    _backupStatus.value = BackupStatus.Success("Settings restored")
                }
                .onFailure { e ->
                    _backupStatus.value = BackupStatus.Error(e.message ?: "Import failed")
                }
            delay(3000)
            _backupStatus.value = BackupStatus.Idle
        }
    }

    fun resetPreferences() {
        viewModelScope.launch {
            settingsRepository.resetSettings()
        }
    }
}
