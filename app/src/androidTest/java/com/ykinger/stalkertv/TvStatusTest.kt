package com.ykinger.stalkertv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.ui.LoadState
import com.ykinger.stalkertv.ui.StateContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineLoadingHasNoRetryUntilFailure() {
        val state = mutableStateOf<LoadState<String>>(LoadState.Loading)
        var retries = 0
        compose.setContent { StateContent(state.value, { retries++ }) { } }
        compose.onNodeWithText("Loading sample content…").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { state.value = LoadState.Message("Offline content unavailable") }
        compose.onNodeWithText("Offline content unavailable").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Retry") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
