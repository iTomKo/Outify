package cc.tomko.outify.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import cc.tomko.outify.data.repository.CollectionSession

@Serializable
data class Device(
    val id: String?,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("is_private_session")
    val isPrivateSession: Boolean,
    @SerialName("is_restricted")
    val isRestricted: Boolean,
    val name: String,
    val type: String,
    @SerialName("volume_percent")
    val volumePercentage: Int?,
    @SerialName("supports_volume")
    val supportsVolume: Boolean
)

@Serializable
data class DevicesResponse(
    val devices: List<Device>,
    @SerialName("local_device_id") val localDeviceId: String? = null,
    @SerialName("active_device_id") val activeDeviceId: String? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val session: CollectionSession? = null,
)
