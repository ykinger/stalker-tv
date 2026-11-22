package com.ykinger.stalkertv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun clearViewerLibrary() {
        compose.activity.runOnUiThread {
            listOf("viewer-profiles-v1", "portal-profile", "vod-category-kinds").forEach {
                compose.activity.getSharedPreferences(it, android.content.Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
            compose.activity.getSharedPreferences(com.ykinger.stalkertv.ui.TERMS_PREFERENCES,
                android.content.Context.MODE_PRIVATE).edit()
                .putInt("accepted_version", com.ykinger.stalkertv.ui.TERMS_VERSION).commit()
        }
        compose.activityRule.scenario.recreate()
    }

    @Test fun firstLaunchCreatesProfileThenAddsPlaylistBeforeAccess() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Create profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Open offline demo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit Profile name").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Alice")
        compose.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick) { it() }

        // A fresh profile goes to Add playlist first; access is chosen after Stalker.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Stalker Portal") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Who can use this playlist?").assertDoesNotExist()
        compose.onNode(hasText("Stalker Portal") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Who can use this playlist?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Just me").assertExists()
        compose.onNodeWithText("Everyone").assertExists()
        compose.onNodeWithText("Just me").performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connection details").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Stalker Portal").assertExists()
        compose.onNodeWithContentDescription("Edit MAC address").assertExists()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Who's watching?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Alice", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Add your own playlist to start watching")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Add playlist", substring = false) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        // The same Add playlist → Stalker → access order applies from the landing page.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Stalker Portal") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Who can use this playlist?").assertDoesNotExist()
        compose.onNode(hasText("Stalker Portal") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Who can use this playlist?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Everyone").assertExists()
    }
}
