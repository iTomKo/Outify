package cc.tomko.outify.core

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.SocketException

class AuthCallbackServer(
    port: Int = 5588,
    private val packageName: String = "cc.tomko.outify",
    private val onCodeReceived: (code: String, state: String?) -> Unit
) : NanoHTTPD(port) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopDelayMs = 350L

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/login" -> handleCallback(
                    session,
                    success = "auth_complete",
                    failure = "auth_failed"
                )

                "/account/login" -> handleCallback(
                    session,
                    success = "account_auth_complete",
                    failure = "account_auth_failed"
                )

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: SocketException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "")
        } catch (t: Throwable) {
            t.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
        }
    }

    private fun handleCallback(
        session: IHTTPSession,
        success: String,
        failure: String
    ): Response {
        val code = session.parameters["code"]?.firstOrNull()
        val state = session.parameters["state"]?.firstOrNull()

        if (code == null) {
            return redirect("intent://$failure#Intent;scheme=outify;package=$packageName;end")
        }

        scope.launch {
            delay(stopDelayMs)
            try {
                onCodeReceived(code, state)
            } finally {
                try {
                    stop()
                } catch (_: Throwable) {
                }
            }
        }

        return redirect("intent://$success#Intent;scheme=outify;package=$packageName;end")
    }

    private fun redirect(location: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", location)
            addHeader("Connection", "close")
        }
}