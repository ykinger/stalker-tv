@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.ykinger.stalkertv.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.Title
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

private val posterCache = LruCache<String, ImageBitmap>(24)
private val unavailablePosters = LruCache<String, Boolean>(24)
private val posterLocks = Array(32) { Mutex() }

@Composable
internal fun TvApp(container: AppContainer, profileName: String, onProfileSettings: () -> Unit,
    onSwitchProfile: () -> Unit, sessionStatus: StateFlow<PortalSessionStatus> = idlePortalSessionStatus) {
    ConnectedTvScreen(container, onProfileSettings, profileName, sessionStatus, onSwitchProfile)
}

@Composable
internal fun PosterCard(title: Title, modifier: Modifier = Modifier, showDetails: Boolean = true,
    onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.then(if (showDetails) Modifier else
        Modifier.semantics { contentDescription = title.name }),
        scale = CardDefaults.scale(focusedScale = 1.04f)) {
        Column {
            Artwork(title, Modifier.fillMaxWidth().aspectRatio(if (title.kind == ContentKind.CHANNEL) 2.3f else 0.70f))
            if (showDetails) {
                Column(Modifier.padding(10.dp)) {
                    Text(title.name, maxLines = 2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(if (title.kind == ContentKind.CHANNEL) "View guide & watch"
                        else "${title.year ?: ""}  •  ${title.kind.name.lowercase()}",
                        fontSize = 10.sp, color = Color(0xFFABB0B3))
                }
            }
        }
    }
}

@Composable
internal fun Artwork(title: Title, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop) {
    val drawable = when (title.artworkKey) {
        "mountain" -> R.drawable.poster_mountain
        "ocean" -> R.drawable.poster_ocean
        "orbit" -> R.drawable.poster_orbit
        else -> null
    }
    val remote by produceState<ImageBitmap?>(null, title.artworkKey) {
        val address = title.artworkKey ?: return@produceState
        if (drawable != null) return@produceState
        value = loadPoster(address)
    }
    Box(modifier.background(Color(0xFF303436)), contentAlignment = Alignment.Center) {
        if (drawable != null)
            Image(painterResource(drawable), contentDescription = null, contentScale = contentScale,
                modifier = Modifier.fillMaxSize())
        else if (remote != null)
            Image(bitmap = remote!!, contentDescription = null, contentScale = contentScale,
                modifier = Modifier.fillMaxSize())
        if (drawable == null && remote == null)
            Text(title.name, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

/** Artwork requests are anonymous: never forward the portal token or device cookie to an image host. */
private suspend fun loadPoster(address: String): ImageBitmap? = withContext(Dispatchers.IO) {
    posterCache.get(address)?.let { return@withContext it }
    if (unavailablePosters.get(address) == true) return@withContext null
    posterLocks[(address.hashCode() and Int.MAX_VALUE) % posterLocks.size].withLock {
        posterCache.get(address) ?: if (unavailablePosters.get(address) == true) null else fetchPoster(address)
    }
}

private fun fetchPoster(address: String): ImageBitmap? {
    try {
        var uri = URI(address)
        val visited = mutableSetOf<URI>()
        repeat(5) {
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
                !visited.add(uri)) return null
            AppContainer().portalClient.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = uri.resolve(response.header("Location") ?: return null)
                    if (uri.scheme == "https" && next.scheme != "https") return null
                    uri = next
                    return@repeat
                }
                if (response.code in setOf(404, 410)) {
                    unavailablePosters.put(address, true)
                    return null
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > 2_000_000) return null
                val input = body.byteStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val data = output.toByteArray()
                if (data.size > 2_000_000) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    unavailablePosters.put(address, true)
                    return null
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
                }
                return BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
                    ?.also { posterCache.put(address, it) }
            }
        }
        return null
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { return null }
}
