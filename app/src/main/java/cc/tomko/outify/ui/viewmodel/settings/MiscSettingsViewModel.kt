package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.data.repository.BackupRepository
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.PendingBackupImport
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.OAuthService
import cc.tomko.outify.services.SyncNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val spClient: SpClient,
    private val syncNotificationManager: SyncNotificationManager,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val savedQueueRepository: SavedQueueRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _likedCount = MutableStateFlow(0)
    val likedCount: StateFlow<Int> = _likedCount.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    init {
        viewModelScope.launch {
            likedRepository.observeCount().collect { count ->
                _likedCount.value = count
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
        _isAuthenticated.value = spClient.isOAuthAuthenticated()
    }

    fun syncLikedTracks() {
        if (_syncStatus.value is SyncStatus.Syncing || _syncStatus.value is SyncStatus.Progress) return
        if (!spClient.isOAuthAuthenticated()) {
            _syncStatus.value = SyncStatus.Error("Please log in to Spotify account first")
            return
        }

        OAuthService.start(context)
        syncNotificationManager.showIndeterminate()
        viewModelScope.launch {
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
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
                syncNotificationManager.showError(e.message ?: "Unknown error")
            } finally {
                OAuthService.stop(context)
            }

            // Sync liked episodes in the background
            launch {
                runCatching {
                    likedRepository.syncLikedEpisodes(forceSync = true)
                }.onFailure {
                    Log.w("MiscSettingsViewModel", "syncLikedEpisodes failed", it)
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