package com.ykinger.stalkertv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.compose.runtime.withFrameNanos

private val statusAccent = Color(0xFF168DD0)
private val statusMuted = Color(0xFFABB0B3)
private val statusSurface = Color(0xFF24282A)

internal enum class TvStatusShape { POSTERS, GUIDE, NONE }

/** Loading has no dead-end Retry button; failures have an explicit remote-focusable action. */
@Composable
internal fun TvStatusPanel(title: String, detail: String? = null, loading: Boolean,
    modifier: Modifier = Modifier, shape: TvStatusShape = TvStatusShape.POSTERS,
    retryLabel: String = "Retry", onRetry: (() -> Unit)? = null,
    retryModifier: Modifier = Modifier) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(loading) {
        if (!loading && onRetry != null) {
            withFrameNanos { }
            try { retryFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    val pulse = if (loading) rememberInfiniteTransition(label = "Loading status")
        .animateFloat(0.45f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "Loading indicator").value else 1f
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(48.dp).height(4.dp).alpha(pulse).background(statusAccent, RoundedCornerShape(2.dp)))
        Text(title, color = Color.White, fontSize = 23.sp)
        detail?.let { Text(it, color = statusMuted, fontSize = 15.sp) }
        if (loading) {
            Spacer(Modifier.height(6.dp))
            when (shape) {
                TvStatusShape.POSTERS -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { index -> StatusPlaceholder(110.dp, 150.dp, index) }
                }
                TvStatusShape.GUIDE -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(3) { index -> StatusPlaceholder(440.dp, 42.dp, index) }
                }
                TvStatusShape.NONE -> Unit
            }
        } else if (onRetry != null) {
            Button(onClick = onRetry, modifier = retryModifier.focusRequester(retryFocus)) { Text(retryLabel) }
        }
    }
}

@Composable
private fun StatusPlaceholder(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, index: Int) {
    Box(Modifier.width(width).height(height).background(
        Brush.linearGradient(listOf(statusSurface, Color(0xFF30383C).copy(alpha = 1f - index * 0.08f))),
        RoundedCornerShape(8.dp)))
}

@Composable
internal fun TvInlineStatus(message: String, loading: Boolean, modifier: Modifier = Modifier,
    retryLabel: String = "Retry", onRetry: (() -> Unit)? = null) {
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height(22.dp).background(statusAccent, RoundedCornerShape(2.dp)))
        Text(message, color = statusMuted, fontSize = 15.sp)
        if (!loading && onRetry != null) Button(onClick = onRetry) { Text(retryLabel) }
    }
}
