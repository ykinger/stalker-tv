package com.ykinger.stalkertv

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
