package cc.tomko.outify.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.data.repository.LikedRepository
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class LikedTileService : TileService() {
    @Inject
    lateinit var spircWrapper: SpircWrapper

    @Inject
    lateinit var likedRepository: LikedRepository

    @Inject
    lateinit var authManager: AuthManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onClick() {
        super.onClick()

        qsTile.label = "Play liked tracks"
        qsTile.state = Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            qsTile.subtitle = "Loading..."
        }
        qsTile.updateTile()

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                spircWrapper.shuffleLoad(OutifyUri.Liked.toUriString())
            }
            qsTile.state = if (success) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                qsTile.subtitle = if (success) "Playing ♫" else "Error"
            }
            if (!success) {
                Log.w("LikedTileService", "shuffleLoad returned false")
            }
            qsTile.updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile.label = "Play liked tracks"
        qsTile.state = if (spircWrapper.isUsable) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            qsTile.subtitle = if (authManager.hasCachedCredentials())
                "${likedRepository.likedCountState.value} songs"
            else
                "Login first"
        }
        qsTile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}