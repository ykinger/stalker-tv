package com.ykinger.stalkertv.ui

import java.net.URI

/** Follow only redirects that keep portal credentials on the configured server. */
internal fun portalRedirect(current: URI, location: String?): URI {
    require(!location.isNullOrBlank()) { "Portal redirect has no destination." }
    val destination = current.resolve(URI(location))
    require(destination.scheme == "http" || destination.scheme == "https") {
        "Portal redirect uses an unsupported scheme."
    }
    require(destination.host != null && destination.userInfo == null &&
        destination.host.equals(current.host, ignoreCase = true)) {
        "Portal redirect changes the server or contains credentials."
    }
    val sameScheme = destination.scheme.equals(current.scheme, ignoreCase = true)
    val upgrade = current.scheme.equals("http", ignoreCase = true) &&
        destination.scheme.equals("https", ignoreCase = true)
    require((sameScheme && effectivePort(destination) == effectivePort(current)) || upgrade) {
        "Portal redirect changes the port or downgrades HTTPS."
    }
    // Fragments are browser-only. Keep the server's path and query exactly as supplied.
    return URI(destination.toString().substringBefore('#'))
}

private fun effectivePort(uri: URI): Int = when {
    uri.port != -1 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}
