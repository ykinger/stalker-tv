package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.PortalHttpException
import com.ykinger.stalkertv.ui.PortalSessionExpiredException
import com.ykinger.stalkertv.ui.PortalSessionHolder
import com.ykinger.stalkertv.ui.PortalSessionStatus
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortalSessionTest {
    private fun sessionError() = JSONObject().put("error", "Session expired or invalid")
    private fun ok() = JSONObject().put("data", "ok")

    private fun holder(
        transport: suspend (String, String) -> Any,
        refresh: suspend () -> String = { "new" },
        debounceNanos: Long = 30_000_000_000L,
    ) = PortalSessionHolder("old", "type=stb&action=get_profile", transport, refresh, debounceNanos)

    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

    @Test fun http401TriggersOneRefreshAndRetry() = runBlocking {
        val tokens = mutableListOf<String>()
        val holder = holder(transport = { _, token ->
            tokens += token
            if (token == "old") throw PortalHttpException(401) else ok()
        })
        assertTrue(holder.request("type=itv&action=get_ordered_list") is JSONObject)
        assertEquals(listOf("old", "new"), tokens)
        assertEquals(PortalSessionStatus.Connected, holder.status.value)
    }

    @Test fun sessionErrorJsonTriggersRefresh() = runBlocking {
        val tokens = mutableListOf<String>()
        val holder = holder(transport = { _, token ->
            tokens += token
            if (token == "old") sessionError() else ok()
        })
        assertTrue(holder.request("q") is JSONObject)
        assertEquals(listOf("old", "new"), tokens)
    }

    @Test fun concurrentExpiriesShareOneHandshake() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var refreshes = 0
        val holder = holder(
            transport = { _, token -> if (token == "old") throw PortalHttpException(401) else ok() },
            refresh = { refreshes++; gate.await(); "new" },
        )
        val calls = (1..3).map { async { holder.request("q$it") } }
        gate.complete(Unit)
        calls.awaitAll()
        assertEquals(1, refreshes)
        assertEquals(PortalSessionStatus.Connected, holder.status.value)
    }

    @Test fun failedRefreshSurfacesSessionError() = runBlocking {
        val holder = holder(
            transport = { _, _ -> throw PortalHttpException(403) },
            refresh = { throw IOException("offline") },
        )
        assertTrue(failureOf { holder.request("q") } is PortalSessionExpiredException)
        assertEquals(PortalSessionStatus.RecoveryFailed, holder.status.value)
    }

    @Test fun repeatedSessionErrorStopsAfterOneRetry() = runBlocking {
        var refreshes = 0
        var attempts = 0
        val holder = holder(
            transport = { _, _ -> attempts++; sessionError() },
            refresh = { refreshes++; "new" },
        )
        assertTrue(failureOf { holder.request("q") } is PortalSessionExpiredException)
        assertEquals(1, refreshes)
        assertEquals(2, attempts)
    }

    @Test fun nonSessionErrorIsReturnedWithoutRefresh() = runBlocking {
        var refreshes = 0
        var attempts = 0
        val result = holder(
            transport = { _, _ -> attempts++; JSONObject().put("error", "Not found") },
            refresh = { refreshes++; "new" },
        ).request("q") as JSONObject
        assertEquals("Not found", result.getString("error"))
        assertEquals(1, attempts)
        assertEquals(0, refreshes)
    }

    @Test fun connectionResetIsRetriedWithoutHandshake() = runBlocking {
        var attempts = 0
        var refreshes = 0
        val holder = holder(
            transport = { _, _ ->
                attempts++
                if (attempts == 1) throw SocketException("Connection reset") else ok()
            },
            refresh = { refreshes++; "new" },
        )
        assertTrue(holder.request("q") is JSONObject)
        assertEquals(2, attempts)
        assertEquals(0, refreshes)
    }

    @Test fun staleKeepAliveConnectionIsRetriedWithoutHandshake() = runBlocking {
        var attempts = 0
        var refreshes = 0
        val holder = holder(
            transport = { _, _ ->
                attempts++
                if (attempts == 1) throw IOException("unexpected end of stream") else ok()
            },
            refresh = { refreshes++; "new" },
        )
        assertTrue(holder.request("q") is JSONObject)
        assertEquals(2, attempts)
        assertEquals(0, refreshes)
    }

    @Test fun eofIsRetriedWithoutHandshake() = runBlocking {
        var attempts = 0
        val holder = holder(transport = { _, _ ->
            attempts++
            if (attempts == 1) throw EOFException() else ok()
        })
        assertTrue(holder.request("q") is JSONObject)
        assertEquals(2, attempts)
    }

    @Test fun timeoutIsNotRetried() = runBlocking {
        var attempts = 0
        val holder = holder(transport = { _, _ -> attempts++; throw SocketTimeoutException("timeout") })
        assertTrue(failureOf { holder.request("q") } is SocketTimeoutException)
        assertEquals(1, attempts)
    }

    @Test fun healthCheckRefreshesStaleSession() = runBlocking {
        val tokens = mutableListOf<String>()
        val holder = holder(
            transport = { _, token ->
                tokens += token
                if (token == "old") throw PortalHttpException(401) else ok()
            },
            debounceNanos = 0,
        )
        holder.healthCheck()
        assertEquals(PortalSessionStatus.Connected, holder.status.value)
        assertTrue(holder.request("q") is JSONObject)
        assertEquals(listOf("old", "new"), tokens)
    }

    @Test fun recentContactSkipsHealthCheck() = runBlocking {
        var calls = 0
        val holder = holder(transport = { _, _ -> calls++; ok() })
        holder.healthCheck()
        assertEquals(0, calls)
    }
}
