package com.ykinger.stalkertv.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MockContentRepositoryTest {
    private val now = Instant.parse("2026-11-12T15:27:00Z")
    private val repository = MockContentRepository(Clock.fixed(now, ZoneOffset.UTC))

    private fun <T> ProviderResult<T>.value(): T = (this as ProviderResult.Success<T>).value

    @Test
    fun `catalogue IDs are unique and every category and detail resolves`() = runTest {
        val catalogue = repository.catalogue().value()
        assertEquals(catalogue.titles.size, catalogue.titles.map { it.id }.toSet().size)
        assertTrue(catalogue.titles.size > 24)
        for (title in catalogue.titles) {
            assertTrue(catalogue.categories.any { it.id == title.categoryId })
            assertEquals(title, repository.details(title.id).value())
        }
        assertTrue(catalogue.titles.any { it.artworkKey == null && it.description == null })
    }

    @Test
    fun `every series has ordered seasons and independently playable episodes`() = runTest {
        val series = repository.catalogue().value().titles.filter { it.kind == ContentKind.SERIES }
        for (show in series) {
            val seasons = repository.seasons(show.id).value()
            assertEquals(listOf(1, 2), seasons.map { it.number })
            for (season in seasons) for (episode in season.episodes) {
                assertEquals(episode, repository.details(episode.id).value())
                assertTrue(repository.resolve(episode.id) is ProviderResult.Success)
            }
            assertEquals(ProviderResult.Unsupported, repository.resolve(show.id))
        }
    }

    @Test
    fun `guide uses injected clock and covers current and next programme`() = runTest {
        val programmes = repository.programmes("channel-1").value()
        assertTrue(programmes.first().start <= now && programmes.first().end > now)
        assertEquals(programmes[0].end, programmes[1].start)
        assertEquals(ProviderResult.Empty, repository.programmes("channel-4"))
        assertEquals(ProviderResult.Empty, repository.programmes("unknown"))
    }

    @Test
    fun `unknown IDs never resolve to a sample stream`() = runTest {
        assertEquals(ProviderResult.Empty, repository.resolve("unknown"))
        assertEquals(ProviderResult.Empty, repository.details("unknown"))
        assertEquals(ProviderResult.Empty, repository.seasons("unknown"))
    }

    @Test
    fun `empty unsupported and failure scenarios are distinguishable`() = runTest {
        assertEquals(
            ProviderResult.Empty,
            MockContentRepository(scenario = MockScenario.EMPTY).catalogue(),
        )
        assertEquals(
            ProviderResult.Unsupported,
            MockContentRepository(scenario = MockScenario.UNSUPPORTED).catalogue(),
        )
        assertTrue(
            MockContentRepository(scenario = MockScenario.FAILURE).catalogue()
                is ProviderResult.Failure
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `delayed scenario suspends and can be cancelled`() = runTest {
        val delayed = MockContentRepository(scenario = MockScenario.DELAYED, delayMillis = 1500)
        val request = async { delayed.catalogue() }
        advanceTimeBy(1000)
        assertFalse(request.isCompleted)
        request.cancel()
        assertTrue(request.isCancelled)
        val result = async { delayed.catalogue() }
        advanceTimeBy(1501)
        assertTrue(result.await() is ProviderResult.Success)
    }
}
