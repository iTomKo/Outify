package cc.tomko.outify.core.jam

/** Invoked on the native callback dispatcher, not the Android main thread. */
internal interface JamCallback {
    fun onChanged(json: String)
    fun onError(reason: String)
}

internal interface JamRequestCallback {
    fun onSuccess(json: String)
    fun onFailure(reason: String)
}

internal interface JamBackend {
    fun registerListener(callback: JamCallback): Boolean
    fun unregisterListener()
    fun start(callback: JamRequestCallback): Boolean
    fun current(callback: JamRequestCallback): Boolean
    fun leave(callback: JamRequestCallback): Boolean
}

internal object JamNative : JamBackend {
    external override fun registerListener(callback: JamCallback): Boolean
    external override fun unregisterListener()
    external override fun start(callback: JamRequestCallback): Boolean
    external override fun current(callback: JamRequestCallback): Boolean
    external override fun leave(callback: JamRequestCallback): Boolean
}
