package com.ykinger.stalkertv.core

import java.time.Instant

// Provider-neutral immutable models. No Stalker response or authentication types reach the UI.
enum class ContentKind {
    CHANNEL,
    MOVIE,
    SERIES,
    EPISODE,
}

data class Category(val id: String, val name: String)

data class Title(
    val id: String,
    val name: String,
    val kind: ContentKind,
    val categoryId: String,
    val description: String? = null,
    val artworkKey: String? = null,
    val year: Int? = null,
)

data class Season(val id: String, val seriesId: String, val number: Int, val episodes: List<Title>)

data class EpisodePage(val episodes: List<Title>, val hasMore: Boolean = false)

data class Programme(
    val id: String,
    val channelId: String,
    val title: String,
    val start: Instant,
    val end: Instant,
)

data class Catalogue(val categories: List<Category>, val titles: List<Title>, val hasMore: Boolean = false)

data class PlaybackSource(
    val uri: String,
    val label: String,
    val headers: Map<String, String> = emptyMap(),
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>

    data object Empty : ProviderResult<Nothing>

    data object Unsupported : ProviderResult<Nothing>

    data class Failure(val message: String) : ProviderResult<Nothing>
}

interface ContentRepository {
    suspend fun catalogue(): ProviderResult<Catalogue>

    suspend fun details(id: String): ProviderResult<Title>

    suspend fun seasons(seriesId: String): ProviderResult<List<Season>>

    suspend fun episodes(seriesId: String, seasonId: String, page: Int): ProviderResult<EpisodePage> =
        ProviderResult.Unsupported

    suspend fun programmes(channelId: String): ProviderResult<List<Programme>>

    suspend fun resolve(id: String): ProviderResult<PlaybackSource>
}

/** Optional on-demand browsing capability; the offline demo supplies a full catalogue. */
interface PagedContentRepository : ContentRepository {
    suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue>

    /** Optional server-side VOD filtering; implementations without it retain ordinary browsing. */
    suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int,
        search: String, sort: String): ProviderResult<Catalogue> =
        loadPage(kind, categoryId, page)
}
