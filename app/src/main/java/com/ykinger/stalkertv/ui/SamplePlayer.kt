package com.ykinger.stalkertv.ui

import android.util.Log
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import androidx.media3.ui.DefaultTimeBar
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.core.PlaybackSource

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SamplePlayer(source: PlaybackSource, live: Boolean = false,
    onChannelUp: () -> Unit = {}, onChannelDown: () -> Unit = {},
    onNextEpisode: () -> Unit = {}, onPreviousEpisode: () -> Unit = {},
    episode: Boolean = false, status: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember(source) { mutableStateOf(false) }
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
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING)
                        Log.d(PORTAL_TAG, "stage=playback.player playing=$isPlaying")
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
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
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) {
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    RemotePlayerView(viewContext, live).apply {
                        id = R.id.sample_player
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        bindPlayer(player)
                        channelUp = onChannelUp
                        channelDown = onChannelDown
                        nextEpisode = onNextEpisode
                        previousEpisode = onPreviousEpisode
                        episodeControls = episode
                        useController = true
                        controllerAutoShow = !live
                        if (live) setControllerShowTimeoutMs(3_000)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // AndroidView is not attached yet in factory. Focus the player after
                        // attachment; its visibility listener restores focus when controls hide.
                        post {
                            if (isAttachedToWindow && !isControllerFullyVisible) requestFocus()
                        }
                    }
                },
                update = {
                    it.bindPlayer(player)
                    it.channelUp = onChannelUp
                    it.channelDown = onChannelDown
                    it.nextEpisode = onNextEpisode
                    it.previousEpisode = onPreviousEpisode
                    it.episodeControls = episode
                },
                onRelease = { it.clearPlayer() },
            )
            if (status != null) Text(status, modifier = Modifier.align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.8f)).padding(12.dp))
            }
        }
    }
}

/** TV remote shortcuts on the focused player surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class RemotePlayerView(context: Context, private val live: Boolean) : PlayerView(context) {
    var channelUp: () -> Unit = {}
    var channelDown: () -> Unit = {}
    var nextEpisode: () -> Unit = {}
    var previousEpisode: () -> Unit = {}
    var episodeControls: Boolean = false
    var restartedEpisode = false
    private val episodeButtonListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            post { enableEpisodeButtons() }
        }
    }

    fun bindPlayer(playback: Player) {
        if (player === playback) return
        if (isAttachedToWindow) player?.removeListener(episodeButtonListener)
        restartedEpisode = false
        player = playback
        if (isAttachedToWindow) playback.addListener(episodeButtonListener)
    }

    fun clearPlayer() {
        if (isAttachedToWindow) player?.removeListener(episodeButtonListener)
        player = null
    }

    private fun enableEpisodeButtons() {
        if (!episodeControls || !isControllerFullyVisible) return
        findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.apply {
            isEnabled = true
            isFocusable = true
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_next)?.apply {
            isEnabled = true
            isFocusable = true
        }
    }
    private val focusLogger = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
            "stage=playback.remote focus=${focusTarget(old)}->${focusTarget(new)}")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player?.addListener(episodeButtonListener)
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) viewTreeObserver.addOnGlobalFocusChangeListener(focusLogger)
        if (live) {
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setShowPreviousButton(false)
            setShowNextButton(false)
            setShowSubtitleButton(false)
            listOf(androidx.media3.ui.R.id.exo_time, androidx.media3.ui.R.id.exo_progress,
                androidx.media3.ui.R.id.exo_progress_placeholder,
                androidx.media3.ui.R.id.exo_center_controls,
                androidx.media3.ui.R.id.exo_minimal_controls,
                androidx.media3.ui.R.id.exo_controls_background).forEach { id ->
                findViewById<View>(id)?.visibility = View.GONE
            }
            findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            setShowPreviousButton(episodeControls)
            setShowNextButton(episodeControls)
            findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                isFocusable = true
                setKeyTimeIncrement(10_000L)
            }
            if (episodeControls) {
                findViewById<View>(androidx.media3.ui.R.id.exo_next)?.setOnClickListener { nextEpisode() }
                findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.setOnClickListener {
                    val playback = player
                    if (!restartedEpisode) {
                        playback?.seekTo(0)
                        restartedEpisode = true
                    } else {
                        restartedEpisode = false
                        previousEpisode()
                    }
                }
            }
            post { enableEpisodeButtons() }
        }
        if (isControllerFullyVisible) {
            findViewById<View>(if (live) androidx.media3.ui.R.id.exo_settings
                else androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
        } else {
            requestFocus()
        }
    }

    override fun onDetachedFromWindow() {
        player?.removeListener(episodeButtonListener)
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) viewTreeObserver.removeOnGlobalFocusChangeListener(focusLogger)
        super.onDetachedFromWindow()
    }

    init {
        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val visible = visibility == View.VISIBLE
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=playback.remote controls=${if (visible) "visible" else "hidden"} " +
                    "focus=${focusTarget(findFocus())}")
            if (isAttachedToWindow) {
                if (visible) {
                    post { enableEpisodeButtons() }
                    // Focus Play/Pause before the newly visible controller is drawn.
                    findViewById<View>(if (live) androidx.media3.ui.R.id.exo_settings
                        else androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                } else {
                    // A hidden controller cannot handle D-pad input. Return it to the
                    // player surface so the next arrow can reveal the controls again.
                    requestFocus()
                }
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                    "stage=playback.remote focus_restored=${focusTarget(findFocus())}")
            }
        })
    }

    private fun focusTarget(focused: View?): String = when (focused) {
        this -> "player"
        null -> "none"
        findViewById<View>(androidx.media3.ui.R.id.exo_play_pause) -> "play_pause"
        findViewById<View>(androidx.media3.ui.R.id.exo_progress) -> "progress"
        findViewById<View>(androidx.media3.ui.R.id.exo_prev) -> "previous"
        findViewById<View>(androidx.media3.ui.R.id.exo_next) -> "next"
        findViewById<View>(androidx.media3.ui.R.id.exo_settings) -> "settings"
        else -> "other"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playback = player ?: return super.dispatchKeyEvent(event)
        val key = event.keyCode
        val seek = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
        val reveal = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
        val toggle = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
        if (!seek && !reveal && !toggle) return super.dispatchKeyEvent(event)
        if (live) {
            val settings = findViewById<View>(androidx.media3.ui.R.id.exo_settings)
            if (isControllerFullyVisible && findFocus() !== this) {
                // Settings and its popup retain ordinary Media3 navigation. Never
                // pass seek keys from the settings icon to the player surface.
                if (seek && findFocus() === settings) return true
                return super.dispatchKeyEvent(event)
            }
            if (!isControllerFullyVisible && reveal) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP) channelUp() else channelDown()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                showController()
                settings?.requestFocus()
            }
            return true
        }
        // Visible controls own their D-pad and OK events. In particular, let
        // DefaultTimeBar seek and let each focused Media3 button handle OK.
        if (isControllerFullyVisible && findFocus() !== this) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING && event.action == KeyEvent.ACTION_DOWN) Log.d(PORTAL_TAG,
                "stage=playback.remote key=controller focus=${focusTarget(findFocus())}")
            return super.dispatchKeyEvent(event)
        }
        if (isControllerFullyVisible && !toggle) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=playback.remote key=${when {
                    seek -> if (key == KeyEvent.KEYCODE_DPAD_LEFT) "left" else "right"
                    reveal -> if (key == KeyEvent.KEYCODE_DPAD_UP) "up" else "down"
                    else -> "select"
                }} controls=${isControllerFullyVisible} focus=${focusTarget(findFocus())} " +
                    "seekable=${playback.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}")
            when {
                seek -> if (playback.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    val offset = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                    val position = (playback.currentPosition + offset).coerceAtLeast(0L)
                    playback.seekTo(if (playback.duration > 0) position.coerceAtMost(playback.duration) else position)
                }
                toggle -> if (playback.playWhenReady) playback.pause() else playback.play()
            }
            showController()
        }
        return true
    }
}
