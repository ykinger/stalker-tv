package com.ykinger.stalkertv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.ui.TvStatusPanel
import com.ykinger.stalkertv.ui.TvStatusShape
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectingShowsIndeterminateProgressUntilMeasuredWorkStarts() {
        var progress by mutableStateOf<Float?>(null)
        compose.setContent {
            TvStatusPanel("Connecting to playlist…", loading = true, shape = TvStatusShape.NONE,
                progress = progress, showIndeterminateProgress = true)
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.runOnIdle { progress = 0.45f }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0.45f, 0f..1f))).assertExists()
    }
}
