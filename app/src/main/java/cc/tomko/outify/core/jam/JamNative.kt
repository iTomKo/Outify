package cc.tomko.outify.core.jam

import androidx.annotation.StringDef
import cc.tomko.outify.ui.components.bottomsheet.jam.JamListenTarget

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
    fun kick(id: String, callback: JamRequestCallback): Boolean
    fun info(token: String, callback: JamRequestCallback): Boolean
    fun join(token: String, joinModeRust: String, callback: JamRequestCallback): Boolean
}

internal object JamNative : JamBackend {
    external override fun registerListener(callback: JamCallback): Boolean
    external override fun unregisterListener()
    external override fun start(callback: JamRequestCallback): Boolean
    external override fun current(callback: JamRequestCallback): Boolean
    external override fun leave(callback: JamRequestCallback): Boolean
    external override fun kick(id: String, callback: JamRequestCallback): Boolean
    external override fun info(token: String, callback: JamRequestCallback): Boolean
    external override fun join(token: String, joinModeRust: String, callback: JamRequestCallback): Boolean
}
