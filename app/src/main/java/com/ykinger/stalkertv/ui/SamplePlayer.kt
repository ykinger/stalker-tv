package com.ykinger.stalkertv.ui

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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.core.PlaybackSource

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SamplePlayer(source: PlaybackSource, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember(source) { mutableStateOf(false) }
    var resumePlayback by remember(source) { mutableStateOf(true) }
    val player =
        remember(context, source) {
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
            val mediaSource = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            ExoPlayer.Builder(context).setMediaSourceFactory(mediaSource).build().apply {
                setMediaItem(MediaItem.fromUri(source.uri))
                prepare()
            }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(playbackError: PlaybackException) {
                    error = true
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
            source.label + " • All demo titles use this sample",
            modifier = Modifier.padding(12.dp),
        )
        if (error) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Sample playback failed. Retry or return to details.")
                Button(
                    onClick = {
                        error = false
                        player.prepare()
                        player.play()
                    }
                ) {
                    Text("Retry playback")
                }
                Button(onClick = onBack) { Text("Back to details") }
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
