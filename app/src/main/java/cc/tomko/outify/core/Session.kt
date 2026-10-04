package cc.tomko.outify.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the librespot session
 */
@Singleton
class Session @Inject constructor() {
    external fun initializeSession(callback: SessionCallback)

    /**
     * Tears down the whole librespot stack.
     *
     * Prefer [cc.tomko.outify.core.spirc.Spirc.requestRestart], which reports
     * when the rebuild finished instead of leaving callers to guess.
     */
    external fun shutdown(): Boolean

    external fun unregisterSessionCallback()
}

interface SessionCallback {
    /**
     * Called when the session gets initialized
     */
    fun onInitialized()

    /**
     * Called when the core session is gone and a rebuild has started.
     * The Connect runtime is unavailable until [onRestarted].
     */
    fun onShutdown()

    /**
     * Called while the Connect runtime is being rebuilt. All playback commands
     * are dropped until [onRestarted].
     */
    fun onRestarting()

    /**
     * Called when the Connect runtime is usable again
     */
    fun onRestarted()

    /**
     * Called when initialization or a rebuild gave up
     */
    fun onFailed(reason: String)
}