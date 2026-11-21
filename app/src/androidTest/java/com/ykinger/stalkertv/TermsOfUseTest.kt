package com.ykinger.stalkertv

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.ui.TERMS_PREFERENCES
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermsOfUseTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun clearAcceptanceAndProfiles() {
        compose.activity.runOnUiThread {
            compose.activity.getSharedPreferences(TERMS_PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().commit()
            compose.activity.getSharedPreferences("viewer-profiles-v1", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        compose.activityRule.scenario.recreate()
    }

    @Test fun acceptanceIsRequiredOnceAndPersists() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Terms of Use").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("About VibeTV").assertExists()
        compose.onNodeWithText("Create profile").assertDoesNotExist()
        compose.onNodeWithText("Accept").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Create profile").fetchSemanticsNodes().isNotEmpty()
        }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Create profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Terms of Use").assertDoesNotExist()
    }
}
