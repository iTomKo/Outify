package cc.tomko.outify.core.connect

import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.data.repository.CollectionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

interface ConnectDevicesClient {
    suspend fun devices(): DevicesResponse
    suspend fun transfer(deviceId: String, expected: CollectionSession)
}

/** All blocking JNI calls stay off the UI thread; no Web API fallback exists. */
class NativeConnectDevicesClient @Inject constructor(
    private val spClient: SpClient,
    private val json: Json,
) : ConnectDevicesClient {
    override suspend fun devices(): DevicesResponse = withContext(Dispatchers.IO) {
        val raw = requireNotNull(spClient.getDevices()) { "Connect device lookup returned no response" }
        json.decodeFromString<DevicesResponse>(raw)
    }

    override suspend fun transfer(deviceId: String, expected: CollectionSession) = withContext(Dispatchers.IO) {
        check(spClient.transferPlaybackDevice(deviceId, json.encodeToString(expected))) {
            "Spotify Connect rejected playback transfer"
        }
    }
}
