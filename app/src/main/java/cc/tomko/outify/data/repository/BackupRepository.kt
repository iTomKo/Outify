package cc.tomko.outify.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

object PendingBackupImport {
    private val _uri = MutableSharedFlow<Uri>(replay = 1)
    val uri: SharedFlow<Uri> = _uri.asSharedFlow()

    fun offer(uri: Uri) {
        _uri.tryEmit(uri)
    }
}

@Serializable
data class OutifyBackup(
    val version: Int = CURRENT_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val preferences: BackupPreferences? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val FILE_EXTENSION = "outify"
        const val MIME_TYPE = "application/x-outify-backup"
    }
}

@Serializable
data class BackupPreferences(
    val shuffle: Boolean? = null,
    val repeat: Boolean? = null,
    val repeatTrack: Boolean? = null,
    val gapless: Boolean? = null,
    val normalizeAudio: Boolean? = null,
    val keepalive: Boolean? = null,
    val autoTransfer: Boolean? = null,
    val bitrate: String? = null,
    val deviceName: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val userImageUrl: String? = null,
    val gesturesEnabled: Boolean? = null,
    val gesturesJson: String? = null,
    val alwaysShowLyrics: Boolean? = null,
    val dynamicTheme: Boolean? = null,
    val dynamicSystem: Boolean? = null,
    val accentColor: Long? = null,
    val pureBlack: Boolean? = null,
    val highContrastCompat: Boolean? = null,
    val fontScale: Float? = null,
    val monochromeImages: Boolean? = null,
    val monochromeAlbums: Boolean? = null,
    val monochromeArtists: Boolean? = null,
    val monochromePlaylists: Boolean? = null,
    val monochromeTracks: Boolean? = null,
    val monochromePlayer: Boolean? = null,
    val monochromeHeaders: Boolean? = null,
    val savedQueuesJson: String? = null,
    val activeQueueId: String? = null,
    val lastTrackUri: String? = null,
    val lastContextUri: String? = null,
    val lastPositionMs: String? = null,
)

@Singleton
class BackupRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    suspend fun exportBackup(uri: Uri, appVersion: String): Result<Unit> = runCatching {
        val prefs = settingsRepository.dataStore.data.first()

        val backup = OutifyBackup(
            appVersion = appVersion,
            preferences = BackupPreferences(
                shuffle = prefs[SettingsRepository.Keys.SHUFFLE],
                repeat = prefs[SettingsRepository.Keys.REPEAT],
								repeatTrack = prefs[SettingsRepository.Keys.REPEAT_TRACK],
                gapless = prefs[SettingsRepository.Keys.GAPLESS],
                normalizeAudio = prefs[SettingsRepository.Keys.NORMALIZE_AUDIO],
                keepalive = prefs[SettingsRepository.Keys.KEEPALIVE],
                autoTransfer = prefs[SettingsRepository.Keys.AUTO_TRANSFER],
                bitrate = prefs[SettingsRepository.Keys.BITRATE],
                deviceName = prefs[SettingsRepository.Keys.DEVICE_NAME],
                userId = prefs[SettingsRepository.Keys.USER_ID],
                username = prefs[SettingsRepository.Keys.USERNAME],
                userImageUrl = prefs[SettingsRepository.Keys.USER_IMAGE_URL],
                gesturesEnabled = prefs[SettingsRepository.Keys.Gesture.ENABLED],
                gesturesJson = prefs[SettingsRepository.Keys.Gesture.GESTURES],
                alwaysShowLyrics = prefs[SettingsRepository.Keys.Lyrics.SHOW_LYRICS_ALWAYS],
                dynamicTheme = prefs[SettingsRepository.Keys.Interface.DYNAMIC_THEME],
                dynamicSystem = prefs[SettingsRepository.Keys.Interface.DYNAMIC_SYSTEM],
                accentColor = prefs[SettingsRepository.Keys.Interface.ACCENT_COLOR],
                pureBlack = prefs[SettingsRepository.Keys.Interface.PURE_BLACK],
                highContrastCompat = prefs[SettingsRepository.Keys.Interface.HIGH_CONTRAST_COMPAT],
                fontScale = prefs[SettingsRepository.Keys.Interface.FONT_SCALE],
                monochromeImages = prefs[SettingsRepository.Keys.Interface.MONOCHROME_IMAGES],
                monochromeAlbums = prefs[SettingsRepository.Keys.Interface.MONOCHROME_ALBUMS],
                monochromeArtists = prefs[SettingsRepository.Keys.Interface.MONOCHROME_ARTISTS],
                monochromePlaylists = prefs[SettingsRepository.Keys.Interface.MONOCHROME_PLAYLISTS],
                monochromeTracks = prefs[SettingsRepository.Keys.Interface.MONOCHROME_TRACKS],
                monochromePlayer = prefs[SettingsRepository.Keys.Interface.MONOCHROME_PLAYER],
                monochromeHeaders = prefs[SettingsRepository.Keys.Interface.MONOCHROME_HEADERS],
                savedQueuesJson = prefs[SettingsRepository.Keys.Queue.QUEUES],
                activeQueueId = prefs[SettingsRepository.Keys.Queue.ACTIVE_ID],
                lastTrackUri = prefs[SettingsRepository.Keys.Playback.LAST_TRACK_URI],
                lastContextUri = prefs[SettingsRepository.Keys.Playback.LAST_CONTEXT_URI],
                lastPositionMs = prefs[SettingsRepository.Keys.Playback.LAST_POSITION_MS],
            )
        )

        val jsonString = json.encodeToString(backup)

        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Cannot open file for writing")
        }
    }

    suspend fun importBackup(uri: Uri): Result<Unit> = runCatching {
        val jsonString = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("Cannot open file for reading")
        }

        val backup = json.decodeFromString<OutifyBackup>(jsonString)
        val prefs =
            backup.preferences ?: throw IllegalStateException("Backup file contains no preferences")

        settingsRepository.dataStore.edit { data ->
            prefs.shuffle?.let { data[SettingsRepository.Keys.SHUFFLE] = it }
            prefs.repeat?.let { data[SettingsRepository.Keys.REPEAT] = it }
            prefs.repeatTrack?.let { data[SettingsRepository.Keys.REPEAT_TRACK] = it }
            prefs.gapless?.let { data[SettingsRepository.Keys.GAPLESS] = it }
            prefs.normalizeAudio?.let { data[SettingsRepository.Keys.NORMALIZE_AUDIO] = it }
            prefs.keepalive?.let { data[SettingsRepository.Keys.KEEPALIVE] = it }
            prefs.autoTransfer?.let { data[SettingsRepository.Keys.AUTO_TRANSFER] = it }
            prefs.bitrate?.let { data[SettingsRepository.Keys.BITRATE] = it }
            prefs.deviceName?.let { data[SettingsRepository.Keys.DEVICE_NAME] = it }
            prefs.gesturesEnabled?.let { data[SettingsRepository.Keys.Gesture.ENABLED] = it }
            prefs.gesturesJson?.let { data[SettingsRepository.Keys.Gesture.GESTURES] = it }
            prefs.alwaysShowLyrics?.let {
                data[SettingsRepository.Keys.Lyrics.SHOW_LYRICS_ALWAYS] = it
            }
            prefs.dynamicTheme?.let { data[SettingsRepository.Keys.Interface.DYNAMIC_THEME] = it }
            prefs.dynamicSystem?.let { data[SettingsRepository.Keys.Interface.DYNAMIC_SYSTEM] = it }
            prefs.accentColor?.let { data[SettingsRepository.Keys.Interface.ACCENT_COLOR] = it }
            prefs.pureBlack?.let { data[SettingsRepository.Keys.Interface.PURE_BLACK] = it }
            prefs.highContrastCompat?.let {
                data[SettingsRepository.Keys.Interface.HIGH_CONTRAST_COMPAT] = it
            }
            prefs.fontScale?.let { data[SettingsRepository.Keys.Interface.FONT_SCALE] = it }
            prefs.monochromeImages?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_IMAGES] = it
            }
            prefs.monochromeAlbums?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_ALBUMS] = it
            }
            prefs.monochromeArtists?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_ARTISTS] = it
            }
            prefs.monochromePlaylists?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_PLAYLISTS] = it
            }
            prefs.monochromeTracks?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_TRACKS] = it
            }
            prefs.monochromePlayer?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_PLAYER] = it
            }
            prefs.monochromeHeaders?.let {
                data[SettingsRepository.Keys.Interface.MONOCHROME_HEADERS] = it
            }
            prefs.userId?.let { data[SettingsRepository.Keys.USER_ID] = it }
            prefs.username?.let { data[SettingsRepository.Keys.USERNAME] = it }
            prefs.userImageUrl?.let { data[SettingsRepository.Keys.USER_IMAGE_URL] = it }
            prefs.savedQueuesJson?.let { data[SettingsRepository.Keys.Queue.QUEUES] = it }
            prefs.activeQueueId?.let { data[SettingsRepository.Keys.Queue.ACTIVE_ID] = it }
            prefs.lastTrackUri?.let { data[SettingsRepository.Keys.Playback.LAST_TRACK_URI] = it }
            prefs.lastContextUri?.let {
                data[SettingsRepository.Keys.Playback.LAST_CONTEXT_URI] = it
            }
            prefs.lastPositionMs?.let {
                data[SettingsRepository.Keys.Playback.LAST_POSITION_MS] = it
            }
        }
    }
}
