package cc.tomko.outify.data.repository

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cc.tomko.outify.core.model.PlaylistFolder
import cc.tomko.outify.data.setting.GestureAction
import cc.tomko.outify.reccobeats.RecommendationConfig
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.GestureTrigger
import cc.tomko.outify.data.setting.Side
import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.ui.model.search.SearchHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    val dataStore: DataStore<Preferences>,
) {
    object Keys {
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = booleanPreferencesKey("repeat")
        val GAPLESS = booleanPreferencesKey("gapless")
        val NORMALIZE_AUDIO = booleanPreferencesKey("normalized_audio")

        /**
         * aka session resurrection
         */
        val KEEPALIVE = booleanPreferencesKey("keepalive")

        /**
         * Should we auto transfer the playback session upon connect
         */
        val AUTO_TRANSFER = booleanPreferencesKey("auto_transfer")
        val BITRATE = stringPreferencesKey("bitrate")
        val DEVICE_NAME = stringPreferencesKey("device_name")

        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val USER_IMAGE_URL = stringPreferencesKey("user_image_url")

        object Gesture {
            val ENABLED = booleanPreferencesKey("gestures_enabled")
            val FLIP_QUEUE = booleanPreferencesKey("flip_queue_gestures")
            val GESTURES = stringPreferencesKey("gestures_json")
        }

        object Lyrics {
            /**
             * When false, show on manual trigger
             */
            val SHOW_LYRICS_ALWAYS = booleanPreferencesKey("always_show_lyrics")
            val ROMANIZE_LYRICS = booleanPreferencesKey("romanize_lyrics")
        }

        object Interface {
            val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
            val DYNAMIC_SYSTEM = booleanPreferencesKey("dynamic_system")
            val ACCENT_COLOR = longPreferencesKey("accent_color")
            val PURE_BLACK = booleanPreferencesKey("pure_black")
            val HIGH_CONTRAST_COMPAT = booleanPreferencesKey("high_contrast_compat")
            val FONT_SCALE = floatPreferencesKey("font_scale")

            val MONOCHROME_IMAGES = booleanPreferencesKey("monochrome_images")
            val MONOCHROME_ALBUMS = booleanPreferencesKey("monochrome_albums")
            val MONOCHROME_ARTISTS = booleanPreferencesKey("monochrome_artists")
            val MONOCHROME_PLAYLISTS = booleanPreferencesKey("monochrome_playlists")
            val MONOCHROME_TRACKS = booleanPreferencesKey("monochrome_tracks")
            val MONOCHROME_PLAYER = booleanPreferencesKey("monochrome_player")
            val MONOCHROME_HEADERS = booleanPreferencesKey("monochrome_headers")

            val EXPERIMENTAL_FLOATING_NAV = booleanPreferencesKey("experimental_floating_nav")
            val SHOW_NAVBAR_HISTORY = booleanPreferencesKey("show_navbar_history")
            val NAVBAR_HISTORY_ON_END = booleanPreferencesKey("navbar_history_on_end")
        }

        object Queue {
            val QUEUES = stringPreferencesKey("saved_queues_v1")
            val ACTIVE_ID = stringPreferencesKey("active_queue_id")
            val RECOMMENDATIONS_ENABLED = booleanPreferencesKey("queue_recommendations_enabled")
            val RECOMMENDATION_RATIO = floatPreferencesKey("queue_recommendation_ratio")
            val RECOMMENDATION_CONFIG = stringPreferencesKey("queue_recommendation_config")
        }

        object Playback {
            val LAST_TRACK_URI = stringPreferencesKey("last_track_uri")
            val LAST_CONTEXT_URI = stringPreferencesKey("last_context_uri")
            val LAST_POSITION_MS = stringPreferencesKey("last_position_ms")
        }

        object Folders {
            val FOLDERS = stringPreferencesKey("playlist_folders_v1")
        }

        object Library {
            val CACHED_URIS = stringPreferencesKey("cached_playlist_uris_v1")
        }

        object Search {
            val SEARCH_HISTORY = stringPreferencesKey("search_history_v2")
        }

        object Cached {
            val CACHED_TOPS = stringPreferencesKey("cached_tops_v1")
        }

        val CLIENT_ID = stringPreferencesKey("client_id")
        val CLIENT_SECRET = stringPreferencesKey("client_secret")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate
    )

    val interfaceSettings: Flow<InterfaceSettings> = dataStore.data.map { prefs ->
        val enabled = prefs[Keys.Gesture.ENABLED] ?: true
        val flipQueueGestures = prefs[Keys.Gesture.FLIP_QUEUE] ?: true

        val monochrome = prefs[Keys.Interface.MONOCHROME_IMAGES] ?: false
        val accentColor = prefs[Keys.Interface.ACCENT_COLOR] ?: Color.Cyan.toArgb().toLong()

        InterfaceSettings(
            swipeGesturesEnabled = enabled,
            flipQueueGestures = flipQueueGestures,
            gestureSettings = if (enabled) decodeGestures(prefs[Keys.Gesture.GESTURES]) else emptyList(),

            // Dynamic theme
            dynamicTheme = prefs[Keys.Interface.DYNAMIC_THEME] ?: true,
            dynamicSystem = prefs[Keys.Interface.DYNAMIC_SYSTEM] ?: true,
            accentColor = Color(accentColor.toInt()),
            pureBlack = prefs[Keys.Interface.PURE_BLACK] ?: false,
            highContrastCompat = prefs[Keys.Interface.HIGH_CONTRAST_COMPAT] ?: false,

            // Font scaling
            fontScale = prefs[Keys.Interface.FONT_SCALE] ?: 1.0f,

            // Monochrome
            monochromeImages = monochrome,
            monochromeAlbums = monochrome && prefs[Keys.Interface.MONOCHROME_ALBUMS] ?: false,
            monochromeArtists = monochrome && prefs[Keys.Interface.MONOCHROME_ARTISTS] ?: false,
            monochromePlaylists = monochrome && prefs[Keys.Interface.MONOCHROME_PLAYLISTS] ?: false,
            monochromeTracks = monochrome && prefs[Keys.Interface.MONOCHROME_TRACKS] ?: false,
            monochromePlayer = monochrome && prefs[Keys.Interface.MONOCHROME_PLAYER] ?: false,
            monochromeHeaders = monochrome && prefs[Keys.Interface.MONOCHROME_HEADERS] ?: false,
            experimentalFloatingNav = prefs[Keys.Interface.EXPERIMENTAL_FLOATING_NAV] ?: true,
            showNavbarHistory = prefs[Keys.Interface.SHOW_NAVBAR_HISTORY] ?: true,
            navbarHistoryOnEnd = prefs[Keys.Interface.NAVBAR_HISTORY_ON_END] ?: true,
        )
    }

    val playbackSettings: Flow<PlaybackSettings> = dataStore.data.map { prefs ->
        val default = PlaybackSettings.Default
        val bitrate = prefs[Keys.BITRATE] ?: default.bitrate.name

        PlaybackSettings(
            gapless = prefs[Keys.GAPLESS] ?: default.gapless,
            normalizeAudio = prefs[Keys.NORMALIZE_AUDIO] ?: default.normalizeAudio,
            keepalive = prefs[Keys.KEEPALIVE] ?: default.keepalive,
            autoTransfer = prefs[Keys.AUTO_TRANSFER] ?: default.autoTransfer,
            bitrate = Bitrate.valueOf(bitrate),
            deviceName = prefs[Keys.DEVICE_NAME] ?: default.deviceName
        )
    }

    object Gesture {
        val Defaults: List<GestureSetting> = listOf(
            GestureSetting(
                action = GestureAction.ADD_TO_QUEUE,
                side = Side.End,
                thresholdFraction = 0.05f,
            ),
            GestureSetting(
                action = GestureAction.PLAY_NEXT,
                side = Side.End,
                thresholdFraction = 0.25f,
            ),
            GestureSetting(
                action = GestureAction.START_RADIO,
                side = Side.End,
                thresholdFraction = 0.45f,
            ),
            GestureSetting(
                action = GestureAction.ADD_TO_FAVORITE,
                side = Side.Start,
                thresholdFraction = 0.25f,
                backgroundHex = 0xC43C8C52,
            ),
            GestureSetting(
                action = GestureAction.SHOW_TRACK_INFO,
                trigger = GestureTrigger.LongPress,
            )
        )
    }

    init {
        scope.launch {
            dataStore.edit { prefs ->
                val current = prefs[Keys.Gesture.GESTURES]
                if (current.isNullOrBlank()) {
                    val serializedDefaults = json.encodeToString(Gesture.Defaults)
                    prefs[Keys.Gesture.GESTURES] = serializedDefaults
                }
            }
        }
    }

    val shuffleEnabled = dataStore.data.map {
        it[Keys.SHUFFLE] ?: false
    }

    val repeatEnabled = dataStore.data.map {
        it[Keys.REPEAT] ?: false
    }

    val gaplessPlayback = dataStore.data.map {
        it[Keys.GAPLESS] ?: true
    }

    val normalizePlayback = dataStore.data.map {
        it[Keys.NORMALIZE_AUDIO] ?: false
    }

    val keepalive = dataStore.data.map {
        it[Keys.KEEPALIVE] ?: false
    }

    val autoTransfer = dataStore.data.map {
        it[Keys.AUTO_TRANSFER] ?: false
    }

    val bitrate = dataStore.data.map {
        val bitrate = it[Keys.BITRATE] ?: Bitrate.KBPS320.name
        Bitrate.valueOf(bitrate)
    }

    val deviceName = dataStore.data.map {
        it[Keys.DEVICE_NAME] ?: "Outify"
    }

    val showLyricsByDefault = dataStore.data.map {
        it[Keys.Lyrics.SHOW_LYRICS_ALWAYS] ?: true
    }

    val romanizeLyrics: Flow<Boolean> = dataStore.data.map {
        it[Keys.Lyrics.ROMANIZE_LYRICS] ?: false
    }

    val lastTrackUri = dataStore.data.map { it[Keys.Playback.LAST_TRACK_URI] }
    val lastContextUri = dataStore.data.map { it[Keys.Playback.LAST_CONTEXT_URI] }
    val lastPositionMs = dataStore.data.map { it[Keys.Playback.LAST_POSITION_MS]?.toLongOrNull() }

    val userId = dataStore.data.map { it[Keys.USER_ID] }
    val username = dataStore.data.map { it[Keys.USERNAME] }
    val userImageUrl = dataStore.data.map { it[Keys.USER_IMAGE_URL] }

    suspend fun setShuffle(enabled: Boolean) {
        dataStore.edit { it[Keys.SHUFFLE] = enabled }
    }

    suspend fun setRepeat(enabled: Boolean) {
        dataStore.edit { it[Keys.REPEAT] = enabled }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        dataStore.edit { it[Keys.GAPLESS] = enabled }
    }

    suspend fun setNormalizePlayback(enabled: Boolean) {
        dataStore.edit { it[Keys.NORMALIZE_AUDIO] = enabled }
    }

    suspend fun setKeepalive(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEPALIVE] = enabled }
    }

    suspend fun setBitrate(bitrate: Bitrate) {
        dataStore.edit { it[Keys.BITRATE] = bitrate.name }
    }

    suspend fun setAutoTransfer(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_TRANSFER] = enabled }
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    suspend fun setGesturesEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.Gesture.ENABLED] = enabled }
    }

    suspend fun setFlipQueueGestures(enabled: Boolean) {
        dataStore.edit { it[Keys.Gesture.FLIP_QUEUE] = enabled }
    }

    suspend fun saveLastPlayback(trackUri: String?, contextUri: String?, positionMs: Long?) {
        dataStore.edit { prefs ->
            if (trackUri != null) prefs[Keys.Playback.LAST_TRACK_URI] = trackUri
            else prefs.remove(Keys.Playback.LAST_TRACK_URI)
            if (contextUri != null) prefs[Keys.Playback.LAST_CONTEXT_URI] = contextUri
            else prefs.remove(Keys.Playback.LAST_CONTEXT_URI)
            if (positionMs != null) prefs[Keys.Playback.LAST_POSITION_MS] = positionMs.toString()
            else prefs.remove(Keys.Playback.LAST_POSITION_MS)
        }
    }

    suspend fun setDynamicTheme(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.DYNAMIC_THEME] = enabled }
    }

    suspend fun setDynamicSystem(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.DYNAMIC_SYSTEM] = enabled }
    }

    suspend fun setAccentColor(color: Color) {
        dataStore.edit { it[Keys.Interface.ACCENT_COLOR] = color.toArgb().toLong() }
    }

    suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.PURE_BLACK] = enabled }
    }

    suspend fun setHighContrastCompat(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.HIGH_CONTRAST_COMPAT] = enabled }
    }

    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[Keys.Interface.FONT_SCALE] = scale }
    }

    suspend fun setMonochromeImages(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_IMAGES] = enabled }
    }

    suspend fun setMonochromeAlbums(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_ALBUMS] = enabled }
    }

    suspend fun setMonochromeArtists(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_ARTISTS] = enabled }
    }

    suspend fun setMonochromePlaylists(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_PLAYLISTS] = enabled }
    }

    suspend fun setMonochromeTracks(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_TRACKS] = enabled }
    }

    suspend fun setMonochromePlayer(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_PLAYER] = enabled }
    }

    suspend fun setMonochromeHeaders(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.MONOCHROME_HEADERS] = enabled }
    }

    suspend fun setExperimentalFloatingNav(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.EXPERIMENTAL_FLOATING_NAV] = enabled }
    }

    suspend fun setShowNavbarHistory(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.SHOW_NAVBAR_HISTORY] = enabled }
    }

    suspend fun setNavbarHistoryOnEnd(enabled: Boolean) {
        dataStore.edit { it[Keys.Interface.NAVBAR_HISTORY_ON_END] = enabled }
    }

    suspend fun setRomanizeLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.Lyrics.ROMANIZE_LYRICS] = enabled }
    }

    suspend fun removeUserProfile() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.USER_IMAGE_URL)
        }
    }

    suspend fun saveUserProfile(userId: String, username: String?, userImageUrl: String?) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId
            username?.let { prefs[Keys.USERNAME] = it }
            userImageUrl?.let { prefs[Keys.USER_IMAGE_URL] = it }
        }
    }

    suspend fun saveGestures(gestures: List<GestureSetting>) {
        val serialized = json.encodeToString(gestures)
        dataStore.edit { it[Keys.Gesture.GESTURES] = serialized }
    }

    private fun decodeGestures(serialized: String?): List<GestureSetting> {
        if (serialized.isNullOrBlank()) return Gesture.Defaults
        return try {
            return json.decodeFromString(serialized)
        } catch (e: Exception) {
            Gesture.Defaults
        }
    }

    val folders: Flow<List<PlaylistFolder>> = dataStore.data.map { prefs ->
        decodeFolders(prefs[Keys.Folders.FOLDERS])
    }

    suspend fun saveFolders(folders: List<PlaylistFolder>) {
        val serialized = json.encodeToString(folders)
        dataStore.edit { it[Keys.Folders.FOLDERS] = serialized }
    }

    private fun decodeFolders(serialized: String?): List<PlaylistFolder> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serialized)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val cachedUris: Flow<List<String>> = dataStore.data.map { prefs ->
        decodeUris(prefs[Keys.Library.CACHED_URIS])
    }

    suspend fun saveCachedUris(uris: List<String>) {
        val serialized = json.encodeToString(uris)
        dataStore.edit { it[Keys.Library.CACHED_URIS] = serialized }
    }

    private fun decodeUris(serialized: String?): List<String> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serialized)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val searchHistory: Flow<List<SearchHistoryItem>> = dataStore.data.map { prefs ->
        decodeSearchHistory(prefs[Keys.Search.SEARCH_HISTORY])
    }

    suspend fun addSearchHistoryItems(items: List<SearchHistoryItem>) {
        dataStore.edit { prefs ->
            val history = decodeSearchHistory(prefs[Keys.Search.SEARCH_HISTORY]).toMutableList()
            val newUris = items.map { it.uri }.toSet()
            history.removeAll { it.uri in newUris }
            history.addAll(0, items)
            if (history.size > 100) {
                history.subList(100, history.size).clear()
            }
            prefs[Keys.Search.SEARCH_HISTORY] = json.encodeToString(history)
        }
    }

    suspend fun removeSearchHistoryItem(uri: String) {
        dataStore.edit { prefs ->
            val history = decodeSearchHistory(prefs[Keys.Search.SEARCH_HISTORY]).toMutableList()
            history.removeAll { it.uri == uri }
            prefs[Keys.Search.SEARCH_HISTORY] = json.encodeToString(history)
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.Search.SEARCH_HISTORY)
        }
    }

    private fun decodeSearchHistory(serialized: String?): List<SearchHistoryItem> {
        if (serialized.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serialized)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val cachedTops: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.Cached.CACHED_TOPS]
    }

    suspend fun saveCachedTops(json: String) {
        dataStore.edit { it[Keys.Cached.CACHED_TOPS] = json }
    }

    val clientId: Flow<String?> = dataStore.data.map { it[Keys.CLIENT_ID] }
    val clientSecret: Flow<String?> = dataStore.data.map { it[Keys.CLIENT_SECRET] }

    val queueRecommendationsEnabled: Flow<Boolean> = dataStore.data.map {
        it[Keys.Queue.RECOMMENDATIONS_ENABLED] ?: false
    }

    val queueRecommendationRatio: Flow<Float> = dataStore.data.map {
        it[Keys.Queue.RECOMMENDATION_RATIO] ?: 0.3f
    }

    val queueRecommendationConfig: Flow<RecommendationConfig?> = dataStore.data.map { prefs ->
        decodeRecommendationConfig(prefs[Keys.Queue.RECOMMENDATION_CONFIG])
    }

    suspend fun setQueueRecommendationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.Queue.RECOMMENDATIONS_ENABLED] = enabled }
    }

    suspend fun setQueueRecommendationRatio(ratio: Float) {
        dataStore.edit { it[Keys.Queue.RECOMMENDATION_RATIO] = ratio }
    }

    suspend fun setQueueRecommendationConfig(config: RecommendationConfig?) {
        dataStore.edit { prefs ->
            if (config == null) prefs.remove(Keys.Queue.RECOMMENDATION_CONFIG)
            else prefs[Keys.Queue.RECOMMENDATION_CONFIG] = json.encodeToString(config)
        }
    }

    private fun decodeRecommendationConfig(serialized: String?): RecommendationConfig? {
        if (serialized.isNullOrBlank()) return null
        return try {
            json.decodeFromString(serialized)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setClientId(id: String?) {
        dataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.CLIENT_ID)
            else prefs[Keys.CLIENT_ID] = id
        }
    }

    suspend fun setClientSecret(secret: String?) {
        dataStore.edit { prefs ->
            if (secret.isNullOrBlank()) prefs.remove(Keys.CLIENT_SECRET)
            else prefs[Keys.CLIENT_SECRET] = secret
        }
    }

    suspend fun resetSettings() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

data class InterfaceSettings(
    val swipeGesturesEnabled: Boolean = true,
    val flipQueueGestures: Boolean = false,
    // Default gestures
    val gestureSettings: List<GestureSetting> = listOf(
        GestureSetting(
            action = GestureAction.ADD_TO_QUEUE,
            side = Side.End,
            thresholdFraction = 0.05f,
            backgroundHex = 0xC43C8C52,
        ),
        GestureSetting(
            action = GestureAction.START_RADIO,
            side = Side.End,
            thresholdFraction = 0.45f,
        ),
        GestureSetting(
            action = GestureAction.ADD_TO_FAVORITE,
            side = Side.Start,
            thresholdFraction = 0.25f,
            backgroundHex = 0xC43C8C52,
        ),
        GestureSetting(
            action = GestureAction.SHOW_TRACK_INFO,
            trigger = GestureTrigger.LongPress,
        )
    ),
    // Dynamic theme
    val dynamicTheme: Boolean = true,
    val dynamicSystem: Boolean = true,
    val pureBlack: Boolean = false,
    val highContrastCompat: Boolean = false,
    val accentColor: Color = Color.Cyan,

    // Font scaling (1.0 = uniform/no system scaling)
    val fontScale: Float = 1.0f,

    // Monochrome
    val monochromeImages: Boolean = false,
    val monochromeAlbums: Boolean = false,
    val monochromeArtists: Boolean = false,
    val monochromePlaylists: Boolean = false,
    val monochromeTracks: Boolean = false,
    val monochromePlayer: Boolean = false,
    val monochromeHeaders: Boolean = false,

    // Experimental features
    val experimentalFloatingNav: Boolean = true,
    val showNavbarHistory: Boolean = true,
    // Should the history icon be on the start/end of the navbar
    val navbarHistoryOnEnd: Boolean = true,
)

data class PlaybackSettings(
    val gapless: Boolean = false,
    val normalizeAudio: Boolean = false,
    val keepalive: Boolean = true,
    val autoTransfer: Boolean = true,
    val bitrate: Bitrate = Bitrate.KBPS320,
    val deviceName: String = "Outify",
) {
    companion object {
        val Default = PlaybackSettings()
    }
}