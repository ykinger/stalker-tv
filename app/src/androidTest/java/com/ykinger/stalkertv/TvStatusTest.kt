package com.ykinger.stalkertv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.ui.TvStatusPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingHasNoRetryUntilFailure() {
        val failure = mutableStateOf<String?>(null)
        var retries = 0
        compose.setContent {
            TvStatusPanel(if (failure.value == null) "Loading channels…" else "Could not load channels",
                detail = failure.value, loading = failure.value == null,
                onRetry = if (failure.value == null) null else {{ retries++ }})
        }
        compose.onNodeWithText("Loading channels…").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { failure.value = "Portal content unavailable" }
        compose.onNodeWithText("Portal content unavailable").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Retry") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
