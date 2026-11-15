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

    private fun editField(label: String, value: String) {
        compose.onNodeWithContentDescription("Edit $label").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }

    private fun assertField(label: String, value: String) {
        compose.onNodeWithContentDescription("Edit $label").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals(value)
    }

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
            val playlists = compose.onAllNodes(hasText("Playlists", substring = false) and hasClickAction())
            if (playlists.fetchSemanticsNodes().isNotEmpty())
                playlists.onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
    }

    @Test
    fun landingAndProfileValidation() {
        openPlaylistSettings()
        compose.onNode(hasText("Add playlist") or hasText("Add another playlist")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Stalker Portal", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Serial ID (optional)").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val input = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithContentDescription("Edit Portal host/IP").assertIsFocused()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Edit MAC address").assertIsFocused()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Edit Portal host/IP").assertIsFocused()
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
        val name = "Test $serial"
        val editedName = "Edited $serial"
        editField("Portal host/IP", "http://10.0.2.2:8080")
        editField("MAC address", "00:1A:79:00:00:01")
        editField("Serial ID (optional)", serial)
        compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Playlist is processed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channels loaded (first page): 2").assertExists()
        editField("Playlist name", name)
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Select a channel")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Demo Channel 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Featured Programme 1").fetchSemanticsNodes().isNotEmpty()
        }
        val input = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithContentDescription("TV").assertExists()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Movies").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Movies", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Movies").assertExists()
        compose.waitForIdle()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5000) { compose.onAllNodesWithText("Shows").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Movies", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Demo Movies").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Sample Feature").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Play movie").assertExists()
        compose.onNodeWithText("Play movie").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Playing").fetchSemanticsNodes().isNotEmpty() }
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Shows").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Demo Series").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Sample Series").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("A New Beginning").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A New Beginning").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Playing").fetchSemanticsNodes().isNotEmpty() }
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("TV").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("All channels").performSemanticsAction(SemanticsActions.OnClick) { it() }
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
            compose.onAllNodesWithText(name)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add another playlist").assertExists()
        compose.onNodeWithText("Portal host/IP").assertDoesNotExist()
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Portal host/IP").assertExists()
        compose.onNodeWithText("Serial ID (optional)").assertExists()
        compose.onNodeWithText("Selected · Connected").assertExists()
        assertField("Portal host/IP", "http://10.0.2.2:8080")
        editField("Playlist name", editedName)
        compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(editedName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Portal host/IP").assertDoesNotExist()
        compose.onNodeWithText(editedName).performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertField("Playlist name", editedName)
        assertField("Portal host/IP", "http://10.0.2.2:8080")
        assertField("Serial ID (optional)", serial)
        editField("Serial ID (optional)", "$serial-EDIT")
        compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Select a channel").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        openPlaylistSettings()
        compose.onNodeWithText(editedName).performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertField("Playlist name", editedName)
        assertField("Portal host/IP", "http://10.0.2.2:8080")
        assertField("Serial ID (optional)", "$serial-EDIT")
        compose.onNodeWithText("Deselect").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No playlist selected. Not connected.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose a playlist").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
