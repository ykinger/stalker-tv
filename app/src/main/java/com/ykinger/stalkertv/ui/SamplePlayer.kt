package com.ykinger.stalkertv.ui

import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.core.PlaybackSource

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SamplePlayer(source: PlaybackSource, demo: Boolean = true, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var resumePlayback by remember(source) { mutableStateOf(true) }
    val player =
        remember(context, source) {
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
            // Media3 otherwise overrides configured Accept-Encoding with identity.
            // Allow gzip for HLS manifests only; keep segment/range requests unchanged.
            val streamHttp = if (source.headers["Accept-Encoding"] == "gzip")
                ResolvingDataSource.Factory(http, ResolvingDataSource.Resolver { spec ->
                    if (spec.uri.path?.endsWith(".m3u8", ignoreCase = true) == true)
                        spec.buildUpon().setFlags(spec.flags or DataSpec.FLAG_ALLOW_GZIP).build()
                    else spec
                }) else http
            val mediaSource = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, streamHttp))
            ExoPlayer.Builder(context).setMediaSourceFactory(mediaSource).build().apply {
                setMediaItem(MediaItem.fromUri(source.uri))
                prepare()
            }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    if (BuildConfig.DEBUG && !demo)
                        Log.d(PORTAL_TAG, "stage=playback.player playing=$isPlaying")
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (BuildConfig.DEBUG && !demo) Log.d(PORTAL_TAG,
                        "stage=playback.player state=${when (playbackState) {
                            Player.STATE_IDLE -> "idle"
                            Player.STATE_BUFFERING -> "buffering"
                            Player.STATE_READY -> "ready"
                            Player.STATE_ENDED -> "ended"
                            else -> "other"
                        }}")
                }
                override fun onPlayerError(playbackError: PlaybackException) {
                    error = true
                    if (BuildConfig.DEBUG && !demo) {
                        val cause = playbackError.cause
                        val status = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        Log.w(PORTAL_TAG, "stage=playback.player failure=${playbackError.errorCodeName} " +
                            "cause=${cause?.javaClass?.simpleName ?: "none"} " +
                            "http=${status ?: "none"}")
                    }
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleStartEffect(player) {
        player.playWhenReady = resumePlayback
        onStopOrDispose {
            resumePlayback = player.playWhenReady
            player.playWhenReady = false
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            source.label + if (demo) " • All demo titles use this sample" else "",
            modifier = Modifier.padding(12.dp),
        )
        if (!demo) Text(if (playing) "Playing" else "Paused or buffering")
        if (error) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Playback failed. Retry or go back.")
                Button(
                    onClick = {
                        error = false
                        player.prepare()
                        player.play()
                    }
                ) {
                    Text("Retry playback")
                }
                Button(onClick = onBack) { Text("Back") }
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        id = R.id.sample_player
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        this.player = player
                        useController = true
                        controllerAutoShow = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        requestFocus()
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
            )
        }
    }
}
