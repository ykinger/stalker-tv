package com.ykinger.stalkertv

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun openOfflineDemo() {
        compose.onNodeWithText("Open offline demo").performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun bundledSamplePlaysAndReleasesItsViewOnBack() {
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Northern Lights").performClick()
        compose.onNodeWithText("Play sample").performClick()
        var playerView: PlayerView? = null
        compose.waitUntil(timeoutMillis = 60_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playerView = compose.activity.findViewById(R.id.sample_player)
                val player = playerView?.player
                ready =
                    player?.playbackState == Player.STATE_READY &&
                        player.currentPosition > 0 &&
                        player.videoSize.width > 0
            }
            ready
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Play sample").assertExists()
        compose.runOnIdle { assertNull(playerView?.player) }
    @Test
    fun sidebarSupportsDpadAndBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        compose.onAllNodesWithText("Movies").assertCountEquals(2)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        compose.onNodeWithText("All").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onAllNodes(hasText("Movies") and hasClickAction()).onFirst().assertIsFocused()
    }

    @Test
    fun browseMovieAndReturnToCatalogue() {
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Northern Lights").performClick()
        compose.onNodeWithText("Play sample").assertExists()
        compose.onNodeWithText("Back", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Northern Lights").assertExists()
    }

    @Test
    fun scenarioFailureCanRecoverThroughSettings() {
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Failure").performClick()
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Success", substring = true).performClick()
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Northern Lights").assertExists()
    }
}
