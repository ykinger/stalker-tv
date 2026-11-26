package com.ykinger.stalkertv

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ykinger.stalkertv.core.PlaybackSource
import com.ykinger.stalkertv.ui.SamplePlayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun vodVideoUsesFullScreenWithoutTitleOrStatusBar() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Sample title")) {}
            }
        }
        compose.waitUntil(30_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ready = (compose.activity.findViewById<PlayerView>(R.id.sample_player)?.width ?: 0) > 0
            }
            ready
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = compose.activity.findViewById<PlayerView>(R.id.sample_player)
            val content = compose.activity.findViewById<android.view.View>(android.R.id.content)
            org.junit.Assert.assertEquals(content.height, view.height)
            org.junit.Assert.assertEquals(content.width, view.width)
            org.junit.Assert.assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
        }
        compose.onNodeWithText("Sample title").assertDoesNotExist()
        compose.onNodeWithText("Paused or buffering").assertDoesNotExist()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun remoteKeysRevealControlsSeekAndPauseSample() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Sample")) {}
            }
        }
        lateinit var view: PlayerView
        compose.waitUntil(30_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val found = compose.activity.findViewById<PlayerView>(R.id.sample_player)
                if (found?.player?.playbackState == Player.STATE_READY) {
                    view = found
                    ready = true
                }
            }
            ready
        }
        val remote = InstrumentationRegistry.getInstrumentation()
        remote.runOnMainSync {
            view.player!!.pause()
            view.player!!.seekTo(2_000)
            view.hideController()
            assertTrue(view.requestFocus())
        }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.runOnMainSync { assertTrue(view.player!!.currentPosition >= 10_000) }
        remote.runOnMainSync { view.hideController(); view.requestFocus() }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.runOnMainSync { assertTrue(view.player!!.currentPosition <= 4_000) }
        remote.runOnMainSync { view.hideController(); view.requestFocus() }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(5_000) {
            var visible = false
            remote.runOnMainSync { visible = view.isControllerFullyVisible }
            visible
        }
        remote.runOnMainSync { view.hideController(); view.requestFocus() }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.runOnMainSync { assertTrue(view.player!!.playWhenReady) }
        remote.runOnMainSync { view.hideController(); view.requestFocus() }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.runOnMainSync {
            assertFalse("playWhenReady=${view.player!!.playWhenReady} " +
                "viewFocus=${view.hasFocus()} focused=${view.findFocus()?.javaClass?.simpleName} " +
                "controls=${view.isControllerFullyVisible} state=${view.player!!.playbackState}",
                view.player!!.playWhenReady)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun visibleControlsNavigateAndFocusedProgressSeeks() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Sample")) {}
            }
        }
        lateinit var view: PlayerView
        compose.waitUntil(30_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.findViewById<PlayerView>(R.id.sample_player)?.let {
                    if (it.player?.playbackState == Player.STATE_READY) { view = it; ready = true }
                }
            }
            ready
        }
        val remote = InstrumentationRegistry.getInstrumentation()
        remote.runOnMainSync {
            view.player!!.pause()
            view.player!!.seekTo(2_000)
            view.hideController()
            assertTrue(view.requestFocus())
        }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            var focused = false
            remote.runOnMainSync {
                focused = view.isControllerFullyVisible &&
                    view.findFocus() === view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)
            }
            focused
        }
        remote.runOnMainSync { assertTrue(view.player!!.playWhenReady) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        remote.runOnMainSync { assertFalse(view.player!!.playWhenReady) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.runOnMainSync {
            assertTrue("Right should navigate away from Play/Pause", view.findFocus() !==
                view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause))
            assertTrue("Navigation must not seek", view.player!!.currentPosition < 10_000)
            assertFalse("Navigation must not toggle playback", view.player!!.playWhenReady)
        }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        remote.runOnMainSync { assertTrue("Left should return to Play/Pause", view.findFocus() ===
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.runOnMainSync { assertTrue("Down should focus the progress bar", view.findFocus() ===
            view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        // Media3 commits a D-pad scrub after its one-second debounce.
        compose.waitUntil(5_000) {
            var sought = false
            remote.runOnMainSync { sought = view.player!!.currentPosition >= 10_000 }
            sought
        }
        remote.runOnMainSync { assertTrue("Progress should seek position=${view.player!!.currentPosition} " +
            "focus=${view.findFocus()?.javaClass?.simpleName} enabled=" +
            "${view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).isEnabled}",
            view.player!!.currentPosition >= 10_000) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        remote.runOnMainSync { assertTrue("Up should leave progress for Play/Pause", view.findFocus() ===
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)) }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.runOnMainSync { assertTrue("Settings should be reachable by D-pad; focused=" +
            "${view.findFocus()?.id}", view.findFocus() ===
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun livePlayerHasSettingsOnlyAndHidesControls() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Live sample"),
                    live = true) {}
            }
        }
        lateinit var view: PlayerView
        compose.waitUntil(30_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.findViewById<PlayerView>(R.id.sample_player)?.let {
                    if (it.player?.playbackState == Player.STATE_READY) { view = it; ready = true }
                }
            }
            ready
        }
        val remote = InstrumentationRegistry.getInstrumentation()
        remote.runOnMainSync {
            view.hideController()
            view.requestFocus()
            assertTrue(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress).visibility ==
                android.view.View.GONE)
            assertTrue(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls).visibility ==
                android.view.View.GONE)
        }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) {
            var focused = false
            remote.runOnMainSync { focused = view.isControllerFullyVisible &&
                view.findFocus() === view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings) }
            focused
        }
        var before = 0L
        remote.runOnMainSync { before = view.player!!.currentPosition }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote.runOnMainSync {
            assertTrue("Live Right must not seek", view.player!!.currentPosition - before < 5_000)
            assertTrue("Live Right must not pause", view.player!!.playWhenReady)
        }
        compose.waitUntil(8_000) {
            var hidden = false
            remote.runOnMainSync { hidden = !view.isControllerFullyVisible }
            hidden
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun hiddenLiveControlsUseUpAndDownToChangeChannels() {
        var up = 0
        var down = 0
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Live"),
                    live = true, onChannelUp = { up++ }, onChannelDown = { down++ }) {}
            }
        }
        val remote = InstrumentationRegistry.getInstrumentation()
        lateinit var view: PlayerView
        compose.waitUntil(30_000) {
            var ready = false
            remote.runOnMainSync {
                compose.activity.findViewById<PlayerView>(R.id.sample_player)?.let {
                    if (it.player?.playbackState == Player.STATE_READY) { view = it; ready = true }
                }
            }
            ready
        }
        remote.runOnMainSync { view.hideController(); view.requestFocus() }
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        remote.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        remote.runOnMainSync {
            assertEquals(1, up)
            assertEquals(1, down)
            assertFalse(view.isControllerFullyVisible)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun episodeButtonsAdvanceAndRestartBeforePrevious() {
        var next = 0
        var previous = 0
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                SamplePlayer(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Episode"),
                    episode = true, onNextEpisode = { next++ },
                    onPreviousEpisode = { previous++ }) {}
            }
        }
        val remote = InstrumentationRegistry.getInstrumentation()
        lateinit var view: PlayerView
        compose.waitUntil(30_000) {
            var ready = false
            remote.runOnMainSync {
                compose.activity.findViewById<PlayerView>(R.id.sample_player)?.let {
                    if (it.player?.playbackState == Player.STATE_READY) { view = it; ready = true }
                }
            }
            ready
        }
        remote.runOnMainSync {
            view.player!!.pause()
            view.player!!.seekTo(12_000)
            view.showController()
        }
        compose.waitUntil(5_000) {
            var enabled = false
            remote.runOnMainSync {
                enabled = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev).isEnabled &&
                    view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next).isEnabled
            }
            enabled
        }
        remote.runOnMainSync {
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_next).performClick()
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev).performClick()
            assertEquals(1, next)
            assertEquals(0, previous)
            assertTrue(view.player!!.currentPosition < 1_000)
            view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_prev).performClick()
            assertEquals(1, previous)
        }
    }
}
