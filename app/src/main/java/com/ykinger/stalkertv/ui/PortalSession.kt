package com.ykinger.stalkertv.ui

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class PortalSessionStatus { Connected, Recovering, RecoveryFailed }

internal const val PORTAL_SESSION_EXPIRED_MESSAGE =
    "Your portal session expired and couldn't be renewed. Try again, or reconnect the playlist."

internal class PortalSessionExpiredException : IllegalStateException(PORTAL_SESSION_EXPIRED_MESSAGE)

/** Non-connected screens never have a session; they still need a stable flow to observe. */
private val idleSessionStatus = MutableStateFlow(PortalSessionStatus.Connected)
internal val idlePortalSessionStatus: StateFlow<PortalSessionStatus> = idleSessionStatus

private const val WATCHDOG_QUERY = "type=watchdog&action=get_events&cur_play_type=0&init_num=0" +
    "&buffering_err=0&need_quality=0&JsHttpRequest=1-xml"
private const val DEFAULT_HEALTH_CHECK_DEBOUNCE_NANOS = 30_000_000_000L
private val sessionStatuses = setOf(401, 403)

/** Fixed labels only: portal error text may contain credentials or media URLs. */
internal fun isPortalSessionError(value: Any?): Boolean {
    val response = value as? JSONObject ?: return false
    val error = response.opt("error") ?: response.optJSONObject("js")?.opt("error")
    return portalErrorKind(error) == "session"
}

private fun isConnectionFailure(error: IOException): Boolean {
    if (error is InterruptedIOException) return false
    // A pooled keep-alive socket closed by the server during idle surfaces as an
    // EOF, not a reset. Retrying once on a fresh connection is the fix.
    if (error is EOFException || error is ConnectException ||
        error is SocketException || error is SSLException) return true
    val message = error.message.orEmpty().lowercase()
    return listOf("unexpected end of stream", "connection reset", "broken pipe",
        "failed to connect", "connection closed").any { it in message }
}

/**
 * Owns the live portal token for one connected playlist. Every portal call goes
 * through [request], so a server-side expiry is repaired in place instead of
 * surfacing an error that only a manual reconnect could clear.
 */
internal class PortalSessionHolder(
    initialToken: String,
    private val healthQuery: String,
    private val transport: suspend (query: String, token: String) -> Any,
    private val refreshSession: suspend () -> String,
    private val healthCheckDebounceNanos: Long = DEFAULT_HEALTH_CHECK_DEBOUNCE_NANOS,
) {
    @Volatile private var token: String = initialToken
    @Volatile private var generation = 0
    @Volatile private var lastContactNanos = System.nanoTime()
    private val refreshMutex = Mutex()
    private val _status = MutableStateFlow(PortalSessionStatus.Connected)
    val status: StateFlow<PortalSessionStatus> = _status

    suspend fun request(query: String): Any {
        val seenGeneration = generation
        val current = token
        val first = try {
            transport(query, current)
        } catch (error: PortalHttpException) {
            if (error.status in sessionStatuses) return retryAfterRefresh(query, seenGeneration)
            throw error
        } catch (error: IOException) {
            if (!isConnectionFailure(error)) throw error
            // OkHttp drops a failed connection, so one retry opens a fresh one.
            // Only connection failures are retried; timeouts are not doubled.
            val retried = try {
                transport(query, current)
            } catch (retryError: PortalHttpException) {
                if (retryError.status in sessionStatuses) return retryAfterRefresh(query, seenGeneration)
                throw retryError
            }
            return checkSession(retried, query, seenGeneration)
        }
        return checkSession(first, query, seenGeneration)
    }

    private suspend fun checkSession(result: Any, query: String, seenGeneration: Int): Any {
        if (!isPortalSessionError(result)) {
            noteContact()
            return result
        }
        return retryAfterRefresh(query, seenGeneration)
    }

    private suspend fun retryAfterRefresh(query: String, seenGeneration: Int): Any {
        val fresh = refresh(seenGeneration)
        val result = try {
            transport(query, fresh)
        } catch (error: PortalHttpException) {
            if (error.status in sessionStatuses) throw PortalSessionExpiredException()
            throw error
        }
        if (isPortalSessionError(result)) throw PortalSessionExpiredException()
        noteContact()
        return result
    }

    /** One handshake for all concurrent requests that saw an expired token. */
    private suspend fun refresh(seenGeneration: Int): String = refreshMutex.withLock {
        if (generation != seenGeneration) return@withLock token
        _status.value = PortalSessionStatus.Recovering
        try {
            val fresh = refreshSession()
            check(fresh.isNotBlank()) { "Portal returned an empty token." }
            token = fresh
            generation++
            noteContact()
            _status.value = PortalSessionStatus.Connected
            fresh
        } catch (cancelled: CancellationException) {
            _status.value = PortalSessionStatus.RecoveryFailed
            throw cancelled
        } catch (error: Exception) {
            _status.value = PortalSessionStatus.RecoveryFailed
            throw PortalSessionExpiredException()
        }
    }

    /** Called on resume: validate a possibly stale token, but never block the UI. */
    suspend fun healthCheck() {
        if (System.nanoTime() - lastContactNanos < healthCheckDebounceNanos) return
        val seenGeneration = generation
        val current = token
        try {
            val result = transport(healthQuery, current)
            if (isPortalSessionError(result)) refreshQuietly(seenGeneration) else noteContact()
        } catch (error: PortalHttpException) {
            if (error.status in sessionStatuses) refreshQuietly(seenGeneration)
        } catch (_: IOException) {
            // Offline on resume; the first real request will recover if needed.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun refreshQuietly(seenGeneration: Int) {
        try {
            refresh(seenGeneration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lazy recovery will give the viewer a specific error on their next action.
        }
    }

    suspend fun keepAlive() {
        request(WATCHDOG_QUERY)
    }

    private fun noteContact() {
        lastContactNanos = System.nanoTime()
    }
}
