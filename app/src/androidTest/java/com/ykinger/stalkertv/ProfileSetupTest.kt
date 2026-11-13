package com.ykinger.stalkertv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun landingAndProfileValidation() {
        compose.onNodeWithText("Add playlist").assertExists()
        compose.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasText("Add playlist") or hasText("Add another playlist")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Stalker Portal", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Serial ID (optional)").assertExists()
        compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Enter a valid HTTP(S) host", substring = true).assertExists()
    }

    // Requires the local Docker portal running on laptop port 8080.
    @Test
    fun dockerProfileCanBeSavedAndReloaded() {
        compose.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasText("Add playlist") or hasText("Add another playlist")).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Stalker Portal", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        val serial = "DEMO-${System.nanoTime()}"
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("http://10.0.2.2:8080")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("00:1A:79:00:00:01")
        compose.onAllNodes(hasSetTextAction())[2].performTextInput(serial)
        compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Home")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("STALKER PROFILE").assertExists()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Demo Channel 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Demo Channel 5").assertExists()
        compose.onNodeWithText("Demo Channel 1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Watch channel").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Playing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Serial ID: $serial")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add another playlist").assertExists()
        compose.onNodeWithText("No playlist selected. Not connected.").assertExists()
    }
}
