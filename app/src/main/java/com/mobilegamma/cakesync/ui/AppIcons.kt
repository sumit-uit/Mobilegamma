package com.mobilegamma.cakesync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small icons not in the core Material set. */
object AppIcons {
    /** A framed picture with mountains and a sun. */
    val Gallery: ImageVector by lazy {
        ImageVector.Builder("Gallery", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(5f, 3f); lineTo(19f, 3f)
                quadTo(21f, 3f, 21f, 5f); lineTo(21f, 19f)
                quadTo(21f, 21f, 19f, 21f); lineTo(5f, 21f)
                quadTo(3f, 21f, 3f, 19f); lineTo(3f, 5f)
                quadTo(3f, 3f, 5f, 3f); close()
                moveTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 5f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 17f); lineTo(10f, 11.5f); lineTo(12.5f, 14.5f); lineTo(14.5f, 12f); lineTo(18f, 17f); close()
                moveTo(14f, 8.5f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                close()
            }
        }.build()
    }

    /** A big and a small four-pointed sparkle. */
    val Sparkle: ImageVector by lazy {
        ImageVector.Builder("Sparkle", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 3f); lineTo(13f, 10f); lineTo(20f, 12f); lineTo(13f, 14f)
                lineTo(11f, 21f); lineTo(9f, 14f); lineTo(2f, 12f); lineTo(9f, 10f); close()
                moveTo(19f, 1.5f); lineTo(19.9f, 4.1f); lineTo(22.5f, 5f); lineTo(19.9f, 5.9f)
                lineTo(19f, 8.5f); lineTo(18.1f, 5.9f); lineTo(15.5f, 5f); lineTo(18.1f, 4.1f); close()
            }
        }.build()
    }
}
