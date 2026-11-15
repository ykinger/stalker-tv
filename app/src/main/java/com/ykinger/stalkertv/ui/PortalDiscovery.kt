package com.ykinger.stalkertv.ui

import java.net.URI

/** Candidate API routes stay on the entered origin; the saved address is never rewritten. */
internal fun portalApiCandidates(address: String): List<String> {
    val uri = URI(address)
    if (uri.path.endsWith(".php", ignoreCase = true)) return listOf(address)
    val origin = "${uri.scheme}://${uri.rawAuthority}"
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val base = when {
        path.endsWith("/c", ignoreCase = true) -> path.dropLast(2)
        path.isBlank() -> ""
        else -> path
    }
    // Host-only profiles commonly expose a Stalker API here. Check it before
    // probing the browser entry point or unrelated PHP paths on every launch.
    val routes = if (base.isBlank())
        listOf("/stalker_portal/server/load.php", "/portal.php", "/server/load.php")
    else listOf("$base/portal.php", "$base/server/load.php",
        "/portal.php", "/server/load.php", "/stalker_portal/server/load.php")
    return (if (base.isBlank()) listOf(origin + routes.first(), address) + routes.drop(1).map { origin + it }
        else listOf(address) + routes.map { origin + it }).distinct()
}
