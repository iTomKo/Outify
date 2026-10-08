package cc.tomko.outify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
    data class Error(val message: String?) : UpdateState
}

class AboutViewModel : ViewModel() {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdates() {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking

        viewModelScope.launch {
            _updateState.value = withContext(Dispatchers.IO) {
                UpdateChecker.check(BuildConfig.VERSION_NAME)
            }
        }
    }
}

object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/iTomKo/Outify/releases/latest"
    private const val FALLBACK_PAGE = "https://github.com/iTomKo/Outify/releases/latest"

    /** Blocking, call from Dispatchers.IO. */
    fun check(currentVersion: String): UpdateState = try {
        fetch(currentVersion)
    } catch (e: Exception) {
        UpdateState.Error(e.message)
    }

    private fun fetch(currentVersion: String): UpdateState {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            // GitHub's API rejects requests without a User-Agent
            setRequestProperty("User-Agent", "Outify")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return UpdateState.Error("HTTP $code")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val url = json.optString("html_url").ifBlank { FALLBACK_PAGE }

            return if (isNewer(tag, currentVersion)) {
                UpdateState.Available(version = tag.removePrefix("v").removePrefix("V"), url = url)
            } else {
                UpdateState.UpToDate
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Compares dotted numeric versions, ignoring a leading "v" and any "-suffix" / "+build". */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
