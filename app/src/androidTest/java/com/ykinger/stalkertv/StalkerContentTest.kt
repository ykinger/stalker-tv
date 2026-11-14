package com.ykinger.stalkertv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.ProviderResult
import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.StalkerContentRepository
import com.ykinger.stalkertv.ui.portalRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the local Docker mock portal at 10.0.2.2:8080. */
@RunWith(AndroidJUnit4::class)
class StalkerContentTest {
    @Test fun epgAndVodRoundTrip() = runBlocking {
        val profile = PortalProfile("", "http://10.0.2.2:8080/server/load.php", "00:1A:79:00:00:01")
        val token = (portalRequest(profile, "type=stb&action=handshake") as JSONObject).getString("token")
        val repository = StalkerContentRepository(profile, token)
        val catalogue = (repository.catalogue() as ProviderResult.Success).value
        assertEquals(5, catalogue.titles.count { it.kind == ContentKind.CHANNEL })
        assertEquals(2, catalogue.titles.count { it.kind == ContentKind.MOVIE })
        assertEquals(1, catalogue.titles.count { it.kind == ContentKind.SERIES })
        val guide = (repository.programmes("1") as ProviderResult.Success).value
        assertEquals(4, guide.size)
        assertTrue(guide.all { it.channelId == "1" && it.end > it.start })
        val movie = catalogue.titles.first { it.kind == ContentKind.MOVIE }
        assertEquals("mountain", movie.artworkKey)
        assertTrue((repository.resolve(movie.id) as ProviderResult.Success).value.uri.endsWith("/sample.mp4"))
        val series = catalogue.titles.first { it.kind == ContentKind.SERIES }
        val seasons = (repository.seasons(series.id) as ProviderResult.Success).value
        assertEquals(2, seasons.size)
        assertEquals(2, seasons.first().episodes.size)
        val episode = seasons.first().episodes.first()
        assertEquals(episode, (repository.details(episode.id) as ProviderResult.Success).value)
        assertTrue((repository.resolve(episode.id) as ProviderResult.Success).value.uri.endsWith("/sample.mp4"))
    }
}
