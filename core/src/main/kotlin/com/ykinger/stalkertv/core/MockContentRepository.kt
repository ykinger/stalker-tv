package com.ykinger.stalkertv.core

import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

enum class MockScenario {
    SUCCESS,
    DELAYED,
    EMPTY,
    UNSUPPORTED,
    FAILURE,
}

class MockContentRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val scenario: MockScenario = MockScenario.SUCCESS,
    private val delayMillis: Long = 1_500L,
) : ContentRepository {
    private val categories =
        listOf(
            Category("discover", "Discover"),
            Category("cinema", "Cinema"),
            Category("nature", "Nature"),
        )
    private val movieNames =
        listOf(
            "Northern Lights",
            "The Last Horizon",
            "Ocean Stories",
            "Quiet Cities",
            "Beyond the Summit",
            "Golden Hour",
            "A Distant Shore",
            "Night Transit",
            "The Open Road",
            "Blue Planet",
            "Summer Letters",
            "Into the Wild",
        )
    private val seriesNames =
        listOf(
            "Wild Frontiers",
            "City After Dark",
            "The Observatory",
            "Coastal Lives",
            "New Beginnings",
            "Hidden Worlds",
            "Long Way Home",
            "Small Wonders",
        )

    private fun titles(names: List<String>, kind: ContentKind) =
        names.mapIndexed { index, name ->
            Title(
                "${kind.name.lowercase()}-$index",
                name,
                kind,
                categories[index % categories.size].id,
                if (index == 3) null
                else
                    "An original demo catalogue entry. Explore extraordinary places and the stories that connect us. Playback uses sample media, not this fictional title.",
                if (index == 3) null else listOf("mountain", "orbit", "ocean")[index % 3],
                2020 + index % 6,
            )
        }

    private val movies = titles(movieNames, ContentKind.MOVIE)
    private val series = titles(seriesNames, ContentKind.SERIES)
    private val channels =
        (1..12).map { number ->
            Title(
                "channel-$number",
                listOf("World News", "Cinema One", "Nature Live", "Culture", "Travel", "Music")[
                    (number - 1) % 6] + " $number",
                ContentKind.CHANNEL,
                categories[(number - 1) % categories.size].id,
                artworkKey = if (number == 4) null else "orbit",
            )
        }
    private val allSeasons =
        series.flatMap { show ->
            (1..2).map { season ->
                Season(
                    "${show.id}-season-$season",
                    show.id,
                    season,
                    (1..6).map { episode ->
                        Title(
                            "${show.id}-s$season-e$episode",
                            "Episode $episode",
                            ContentKind.EPISODE,
                            show.categoryId,
                            "A fictional episode for testing browsing and sample playback.",
                            show.artworkKey,
                        )
                    },
                )
            }
        }
    private val all = movies + series + channels + allSeasons.flatMap { it.episodes }

    private suspend fun <T> respond(value: () -> ProviderResult<T>): ProviderResult<T> {
        if (scenario == MockScenario.DELAYED) delay(delayMillis)
        return when (scenario) {
            MockScenario.EMPTY -> ProviderResult.Empty
            MockScenario.UNSUPPORTED -> ProviderResult.Unsupported
            MockScenario.FAILURE ->
                ProviderResult.Failure(
                    "Simulated connection failure. Retry or choose Success in Settings."
                )
            else -> value()
        }
    }

    override suspend fun catalogue(): ProviderResult<Catalogue> = respond {
        ProviderResult.Success(Catalogue(categories, channels + movies + series))
    }

    override suspend fun details(id: String): ProviderResult<Title> = respond {
        all.find { it.id == id }?.let { ProviderResult.Success(it) } ?: ProviderResult.Empty
    }

    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = respond {
        allSeasons
            .filter { it.seriesId == seriesId }
            .takeIf { it.isNotEmpty() }
            ?.let { ProviderResult.Success(it) } ?: ProviderResult.Empty
    }

    override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = respond {
        if (channels.none { it.id == channelId } || channelId == "channel-4")
            return@respond ProviderResult.Empty
        val start = clock.instant().truncatedTo(ChronoUnit.HOURS)
        ProviderResult.Success(
            (0..3).map { offset ->
                Programme(
                    "$channelId-$offset",
                    channelId,
                    listOf(
                        "Morning Discoveries",
                        "World Stories",
                        "On Location",
                        "The Evening Edit",
                    )[offset],
                    start.plus(offset.toLong(), ChronoUnit.HOURS),
                    start.plus(offset + 1L, ChronoUnit.HOURS),
                )
            }
        )
    }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = respond {
        val title = all.find { it.id == id } ?: return@respond ProviderResult.Empty
        if (title.kind == ContentKind.SERIES) return@respond ProviderResult.Unsupported
        ProviderResult.Success(
            PlaybackSource(
                "asset:///big_buck_bunny_sample.mp4",
                "Sample excerpt: Big Buck Bunny • Blender Foundation • CC BY 3.0",
            )
        )
    }
}
