package com.rawr.camera.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** X14U capture-screen icons, drawn as strokes (the Icon tint colours them). */
internal object X14uIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float = 1.8f, body: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = body
        )
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** Look: three overlapping circles (colour filters). */
    val Look: ImageVector by lazy {
        icon("X14u.Look") {
            stroke(1.6f) { circle(12f, 8.6f, 5f) }
            stroke(1.6f) { circle(8.6f, 14.6f, 5f) }
            stroke(1.6f) { circle(15.4f, 14.6f, 5f) }
        }
    }

    /** Front / back camera: two curved arrows around a lens. */
    val CameraSwitch: ImageVector by lazy {
        icon("X14u.CameraSwitch") {
            stroke {
                moveTo(4.5f, 10.5f)
                arcTo(8f, 8f, 0f, false, true, 19.5f, 10.5f)
                moveTo(19.5f, 10.5f)
                lineTo(21.6f, 7.9f)
                moveTo(19.5f, 10.5f)
                lineTo(16.6f, 9.6f)
            }
            stroke {
                moveTo(19.5f, 13.5f)
                arcTo(8f, 8f, 0f, false, true, 4.5f, 13.5f)
                moveTo(4.5f, 13.5f)
                lineTo(2.4f, 16.1f)
                moveTo(4.5f, 13.5f)
                lineTo(7.4f, 14.4f)
            }
            stroke(1.6f) { circle(12f, 12f, 2.4f) }
        }
    }

    /** LOG: a log-shaped curve over an axis. */
    val LogCurve: ImageVector by lazy {
        icon("X14u.LogCurve") {
            stroke(1.2f) {
                moveTo(3.5f, 20f)
                lineTo(20.5f, 20f)
                moveTo(3.5f, 20f)
                lineTo(3.5f, 3.5f)
            }
            stroke(2f) {
                moveTo(4f, 19f)
                curveTo(6f, 10f, 11f, 6.5f, 20.5f, 5.5f)
            }
        }
    }

    /** Microphone. */
    val Mic: ImageVector by lazy {
        icon("X14u.Mic") {
            stroke {
                moveTo(9f, 6f)
                arcTo(3f, 3f, 0f, false, true, 15f, 6f)
                lineTo(15f, 11f)
                arcTo(3f, 3f, 0f, false, true, 9f, 11f)
                close()
                moveTo(5.5f, 11f)
                arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f)
                moveTo(12f, 17.5f)
                lineTo(12f, 21f)
            }
        }
    }

    /** Microphone, struck through (muted). */
    val MicOff: ImageVector by lazy {
        icon("X14u.MicOff") {
            stroke {
                moveTo(9f, 6f)
                arcTo(3f, 3f, 0f, false, true, 15f, 6f)
                lineTo(15f, 11f)
                arcTo(3f, 3f, 0f, false, true, 9f, 11f)
                close()
                moveTo(5.5f, 11f)
                arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f)
                moveTo(12f, 17.5f)
                lineTo(12f, 21f)
                moveTo(4f, 3f)
                lineTo(20f, 21f)
            }
        }
    }
}
