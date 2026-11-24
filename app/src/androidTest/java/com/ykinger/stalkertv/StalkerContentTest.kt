package com.ykinger.stalkertv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.ProviderResult
import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.PortalSessionHolder
import com.ykinger.stalkertv.ui.StalkerContentRepository
import com.ykinger.stalkertv.ui.portalProfileQuery
import com.ykinger.stalkertv.ui.portalRequest
import com.ykinger.stalkertv.ui.testPortal
import com.ykinger.stalkertv.ui.verifyPortal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the local Docker mock portal at 10.0.2.2:8080. */
@RunWith(AndroidJUnit4::class)
class StalkerContentTest {
    private val profile = PortalProfile("", "http://10.0.2.2:8080/server/load.php", "00:1A:79:00:00:01")

    private fun sessionFor(token: String, host: String = profile.host,
        container: AppContainer = AppContainer()) = PortalSessionHolder(
        initialToken = token,
        healthQuery = portalProfileQuery(profile.copy(host = host)),
        transport = { query, activeToken ->
            portalRequest(profile.copy(host = host), query, activeToken, container)
        },
        refreshSession = { verifyPortal(profile.copy(host = host), container).token },
    )

    /** Mock-only control route; production portals never expose this. */
    private fun expireMockSessions() {
        val request = Request.Builder().url("http://10.0.2.2:8080/test/expire-sessions").build()
        OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) }
    }

    @Test fun epgAndVodRoundTrip() = runBlocking {
        val token = (portalRequest(profile, "type=stb&action=handshake") as JSONObject).getString("token")
        val repository = StalkerContentRepository(profile, sessionFor(token))
        val catalogue = (repository.catalogue() as ProviderResult.Success).value
        assertEquals(2, catalogue.titles.count { it.kind == ContentKind.CHANNEL })
        assertTrue(catalogue.hasMore)
        val second = (repository.loadPage(ContentKind.CHANNEL, null, 2) as ProviderResult.Success).value
        val third = (repository.loadPage(ContentKind.CHANNEL, null, 3) as ProviderResult.Success).value
        assertEquals(2, second.titles.size)
        assertEquals(1, third.titles.size)
        assertFalse(third.hasMore)
        val movies = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value
        val shows = (repository.loadPage(ContentKind.SERIES, null, 2) as ProviderResult.Success).value
        assertEquals(2, movies.titles.count { it.kind == ContentKind.MOVIE })
        assertEquals(1, shows.titles.count { it.kind == ContentKind.SERIES })
        assertTrue(movies.categories.any { it.id == "*" && it.name == "All" })
        val guide = (repository.programmes("1") as ProviderResult.Success).value
        assertEquals(4, guide.size)
        assertTrue(guide.all { it.channelId == "1" && it.end > it.start })
        val movie = movies.titles.first { it.kind == ContentKind.MOVIE }
        assertEquals("http://10.0.2.2:8080/posters/mountain.png", movie.artworkKey)
        assertTrue((repository.resolve(movie.id) as ProviderResult.Success).value.uri.endsWith("/sample.mp4"))
        val series = shows.titles.first { it.kind == ContentKind.SERIES }
        val seasons = (repository.seasons(series.id) as ProviderResult.Success).value
        assertEquals(2, seasons.size)
        val firstEpisodes = (repository.episodes(series.id, seasons.first().id, 1) as ProviderResult.Success).value
        assertEquals(2, firstEpisodes.episodes.size)
        val episode = firstEpisodes.episodes.first()
        assertEquals(episode, (repository.details(episode.id) as ProviderResult.Success).value)
        assertTrue((repository.resolve(episode.id) as ProviderResult.Success).value.uri.endsWith("/sample.mp4"))
    }

    @Test fun manualVerifyAlwaysIssuesAFreshToken() = runBlocking {
        val first = withContext(Dispatchers.IO) { testPortal(profile, AppContainer()) }
        val second = withContext(Dispatchers.IO) { testPortal(profile, AppContainer()) }
        assertNotEquals(first.token, second.token)
    }

    @Test fun expiredSessionRecoversWithoutReconnecting() = runBlocking {
        val verified = withContext(Dispatchers.IO) { testPortal(profile, AppContainer()) }
        val repository = StalkerContentRepository(
            profile.copy(host = verified.apiHost),
            sessionFor(verified.token, verified.apiHost),
        )
        val before = repository.catalogue() as ProviderResult.Success
        assertTrue(before.value.titles.any { it.kind == ContentKind.CHANNEL })
        withContext(Dispatchers.IO) { expireMockSessions() }
        val after = repository.loadPage(ContentKind.CHANNEL, null, 1)
        assertTrue(after is ProviderResult.Success)
        assertEquals(2, (after as ProviderResult.Success).value.titles.size)
    }
}
