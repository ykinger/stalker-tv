package com.ykinger.stalkertv

import android.app.Application
import com.ykinger.stalkertv.core.ContentRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Android's recommended manual dependency injection via an application container.
class AppContainer(private val connectedRepository: ContentRepository? = null) {
    companion object {
        private val sharedPortalClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        private val sharedHandshakeClient: OkHttpClient = sharedPortalClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        // get_all_channels returns ~17 MiB and takes ~16s on a real portal. It must
        // not inherit the ordinary 5s read timeout or the 1 MiB response limit.
        private val sharedChannelIndexClient: OkHttpClient = sharedPortalClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    val portalClient: OkHttpClient get() = sharedPortalClient
    val handshakeClient: OkHttpClient get() = sharedHandshakeClient
    val channelIndexClient: OkHttpClient get() = sharedChannelIndexClient
    val repository: ContentRepository
        get() = requireNotNull(connectedRepository) { "No portal repository is connected." }
}

class TvApplication : Application() {
    val container = AppContainer()
}
