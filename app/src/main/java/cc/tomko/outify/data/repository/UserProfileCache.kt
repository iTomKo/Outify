package cc.tomko.outify.data.repository

import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the cached identity (display name and avatar) from the
 * playback-authenticated session so screens such as settings can show it
 * without re-reading the network themselves.
 *
 * The cache is written once per freshly activated playback session and is
 * guarded by [CollectionSessionState.ensureCurrent], so a late result from a
 * replaced session can never overwrite the current account's profile.
 *
 * Uses the same native profile path as the home screen ([UserProfile]), which
 * parses the internal user-profile endpoint correctly.
 */
@Singleton
class UserProfileCache @Inject constructor(
    private val userProfile: UserProfile,
    private val settingsRepository: SettingsRepository,
    private val sessions: CollectionSessionState,
    private val json: Json,
) {
    /** Observes newly authenticated sessions and keeps [settingsRepository] warm. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            sessions.current
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { lease ->
                    try {
                        refresh(lease)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The cache is best-effort; a failed fetch keeps the previous value.
                    }
                }
        }
    }

    /** Fetches the current playback user and persists the display name and avatar. */
    private suspend fun refresh(lease: CollectionLease) {
        val accountId = lease.session.accountId
        val raw = withContext(Dispatchers.IO) { userProfile.getUserProfile(accountId) }
        sessions.ensureCurrent(lease)

        val profile = raw?.let { json.decodeFromString<Profile>(it) }
        val displayName = profile?.name?.takeIf { it.isNotBlank() } ?: accountId

        settingsRepository.saveUserProfile(
            accountId,
            displayName,
            profile?.imageUrl,
        )
    }
}
