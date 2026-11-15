package com.ykinger.stalkertv.ui

import com.ykinger.stalkertv.core.Category
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.Title

private val movieLabel = Regex("\\b(?:movies?|films?)\\b", RegexOption.IGNORE_CASE)
private val showLabel = Regex("\\b(?:tv[\\s-]*shows?|shows|series|serials?)\\b", RegexOption.IGNORE_CASE)

/** Category metadata has no type field. Only exclude names exclusive to the other section. */
internal fun vodCategoryVisible(category: Category, kind: ContentKind, loadedTitles: List<Title>): Boolean {
    if (category.id == "*" || loadedTitles.any { it.categoryId == category.id && it.kind == kind }) return true
    val movies = movieLabel.containsMatchIn(category.name)
    val shows = showLabel.containsMatchIn(category.name)
    return when (kind) {
        ContentKind.MOVIE -> !shows || movies
        ContentKind.SERIES -> !movies || shows
        else -> true
    }
}
