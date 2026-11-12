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

data class Programme(
    val id: String,
    val channelId: String,
    val title: String,
    val start: Instant,
    val end: Instant,
)

data class Catalogue(val categories: List<Category>, val titles: List<Title>)

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

    suspend fun programmes(channelId: String): ProviderResult<List<Programme>>

    suspend fun resolve(id: String): ProviderResult<PlaybackSource>
}
