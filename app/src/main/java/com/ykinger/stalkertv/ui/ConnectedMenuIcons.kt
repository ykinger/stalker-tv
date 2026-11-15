package com.ykinger.stalkertv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original, lightweight navigation glyphs matching the connected TV menu's roles. */
internal object ConnectedMenuIcons {
    val tv: ImageVector = ImageVector.Builder("TV", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 18f); lineTo(3f, 18f); close()
            moveTo(9f, 21f); lineTo(15f, 21f)
            moveTo(12f, 18f); lineTo(12f, 21f)
        }
    }.build()

    val movies: ImageVector = ImageVector.Builder("Movies", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 10f); lineTo(21f, 10f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(3f, 10f); lineTo(3f, 7f); lineTo(19.5f, 3f); lineTo(21f, 6f); lineTo(3f, 10f)
            moveTo(7f, 6f); lineTo(9f, 9f)
            moveTo(13f, 4.5f); lineTo(15f, 7.5f)
            moveTo(7f, 10f); lineTo(10f, 14f)
            moveTo(15f, 10f); lineTo(18f, 14f)
        }
    }.build()

    val shows: ImageVector = ImageVector.Builder("Shows", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 5f); lineTo(17f, 5f); lineTo(17f, 8f)
            moveTo(3f, 5f); lineTo(3f, 17f); lineTo(6f, 17f)
            moveTo(7f, 9f); lineTo(21f, 9f); lineTo(21f, 21f); lineTo(7f, 21f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 11.5f); lineTo(12f, 18.5f); lineTo(18f, 15f); close()
        }
    }.build()

    val recordings: ImageVector = ImageVector.Builder("Recordings", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2f, 6f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(2f, 18f); close()
            // Small DVR lettering remains legible in the collapsed rail.
            moveTo(5f, 9f); lineTo(5f, 15f); lineTo(7f, 15f); lineTo(8.5f, 13.5f)
            lineTo(8.5f, 10.5f); lineTo(7f, 9f); close()
            moveTo(10f, 9f); lineTo(12f, 15f); lineTo(14f, 9f)
            moveTo(16f, 15f); lineTo(16f, 9f); lineTo(18.5f, 9f)
            lineTo(20f, 10.5f); lineTo(18.5f, 12f); lineTo(16f, 12f)
            moveTo(18f, 12f); lineTo(20f, 15f)
        }
    }.build()
}
