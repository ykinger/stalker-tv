package com.ykinger.stalkertv

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openPlaylistSettings() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Manage playlists").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Select a channel").fetchSemanticsNodes().isNotEmpty()
        }
        val manage = compose.onAllNodesWithText("Manage playlists")
        if (manage.fetchSemanticsNodes().isNotEmpty())
            manage.onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        else {
            if (compose.onAllNodesWithText("Select a channel").fetchSemanticsNodes().isNotEmpty()) {
                val input = InstrumentationRegistry.getInstrumentation()
                input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            compose.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.OnClick) { it() }
            if (compose.onAllNodesWithText("Playlists").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithText("Playlists").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
    }

    @Test
    fun landingAndProfileValidation() {
        openPlaylistSettings()
        compose.onNode(hasText("Add playlist") or hasText("Add another playlist")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Stalker Portal", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Serial ID (optional)").assertExists()
        compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Enter a valid HTTP(S) host", substring = true).assertExists()
    }

    // Requires the local Docker portal running on laptop port 8080.
    @Test
    fun dockerProfileCanBeSavedAndReloaded() {
        openPlaylistSettings()
        compose.onNode(hasText("Add playlist") or hasText("Add another playlist")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Stalker Portal", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        val serial = "DEMO-${System.nanoTime()}"
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("http://10.0.2.2:8080")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("00:1A:79:00:00:01")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput(serial)
        compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Playlist is processed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channels: 5").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("Test Playlist")
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Select a channel")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Demo Channel 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Demo Channel 5").assertExists()
        compose.onNodeWithText("Demo Channel 1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Connecting…").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText("Demo Channel 1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Playing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Select a channel").fetchSemanticsNodes().isNotEmpty()
        }
        openPlaylistSettings()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Serial ID: $serial")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add another playlist").assertExists()
        compose.onNodeWithText("Playlist 1 · Test Playlist").assertExists()
        compose.onNodeWithText("Selected · Connected").assertExists()
        compose.onNodeWithText("Deselect playlist").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No playlist selected. Not connected.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose a playlist").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
