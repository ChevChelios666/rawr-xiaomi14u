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

    /** Front / back camera: a single loop arrow (clockwise, arrowhead at the top left). */
    val CameraSwitch: ImageVector by lazy {
        icon("X14u.CameraSwitch") {
            stroke(2f) {
                moveTo(15.75f, 5.5f)
                arcTo(7.5f, 7.5f, 0f, true, true, 8.25f, 5.5f)
                moveTo(8.25f, 5.5f)
                lineTo(6.98f, 8.22f)
                moveTo(8.25f, 5.5f)
                lineTo(5.26f, 5.24f)
            }
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

    /** Tools panel: 3 × 3 grid. */
    val Grid: ImageVector by lazy {
        icon("X14u.Grid") {
            stroke(1.6f) {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                moveTo(9.33f, 4f); lineTo(9.33f, 20f); moveTo(14.67f, 4f); lineTo(14.67f, 20f)
                moveTo(4f, 9.33f); lineTo(20f, 9.33f); moveTo(4f, 14.67f); lineTo(20f, 14.67f)
            }
        }
    }

    /** Tools panel: level (a line with a centre tick, tilted). */
    val Level: ImageVector by lazy {
        icon("X14u.Level") {
            stroke(1.8f) { moveTo(3f, 14f); lineTo(21f, 10f) }
            stroke(1.4f) { moveTo(12f, 7f); lineTo(12f, 17f) }
        }
    }

    /** Tools panel: video stabilisation (a frame held steady between shake marks). */
    val Stabilize: ImageVector by lazy {
        icon("X14u.Stabilize") {
            stroke(1.6f) { moveTo(7.5f, 7.5f); lineTo(16.5f, 7.5f); lineTo(16.5f, 16.5f); lineTo(7.5f, 16.5f); close() }
            stroke(1.4f) {
                moveTo(4.5f, 8f); lineTo(3f, 10f); lineTo(4.5f, 12f); lineTo(3f, 14f); lineTo(4.5f, 16f)
                moveTo(19.5f, 8f); lineTo(21f, 10f); lineTo(19.5f, 12f); lineTo(21f, 14f); lineTo(19.5f, 16f)
            }
        }
    }

    /** Tools panel: histogram bars. */
    val Histogram: ImageVector by lazy {
        icon("X14u.Histogram") {
            stroke(1.6f) {
                moveTo(3.5f, 20f); lineTo(20.5f, 20f)
                moveTo(6f, 20f); lineTo(6f, 15f); moveTo(9f, 20f); lineTo(9f, 9f); moveTo(12f, 20f); lineTo(12f, 5f)
                moveTo(15f, 20f); lineTo(15f, 11f); moveTo(18f, 20f); lineTo(18f, 16f)
            }
        }
    }

    /** Tools panel: raw highlights (sun). */
    val Sun: ImageVector by lazy {
        icon("X14u.Sun") {
            stroke(1.6f) { circle(12f, 12f, 4f) }
            stroke(1.6f) {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    moveTo(12f + 6.5f * kotlin.math.cos(a).toFloat(), 12f + 6.5f * kotlin.math.sin(a).toFloat())
                    lineTo(12f + 9f * kotlin.math.cos(a).toFloat(), 12f + 9f * kotlin.math.sin(a).toFloat())
                }
            }
        }
    }

    /** Tools panel: tonemap shadows (moon). */
    val Moon: ImageVector by lazy {
        icon("X14u.Moon") {
            stroke(1.6f) {
                moveTo(15.5f, 4.5f)
                arcTo(8f, 8f, 0f, true, false, 19.5f, 15.5f)
                arcTo(6.2f, 6.2f, 0f, false, true, 15.5f, 4.5f)
                close()
            }
        }
    }

    /** Tools panel: location pin. */
    val Pin: ImageVector by lazy {
        icon("X14u.Pin") {
            stroke(1.6f) {
                moveTo(12f, 21f)
                curveTo(7f, 15.5f, 5.5f, 12.5f, 5.5f, 9.5f)
                arcTo(6.5f, 6.5f, 0f, false, true, 18.5f, 9.5f)
                curveTo(18.5f, 12.5f, 17f, 15.5f, 12f, 21f)
                close()
            }
            stroke(1.6f) { circle(12f, 9.5f, 2.3f) }
        }
    }

    /** Tools panel: mirror (a shape and its reflection across a dashed axis). */
    val Mirror: ImageVector by lazy {
        icon("X14u.Mirror") {
            stroke(1.6f) { moveTo(10f, 5f); lineTo(3.5f, 19f); lineTo(10f, 19f); close() }
            stroke(1.6f) { moveTo(14f, 5f); lineTo(20.5f, 19f); lineTo(14f, 19f); close() }
            stroke(1.4f) { moveTo(12f, 3f); lineTo(12f, 5f); moveTo(12f, 8f); lineTo(12f, 10f); moveTo(12f, 13f); lineTo(12f, 15f); moveTo(12f, 18f); lineTo(12f, 21f) }
        }
    }

    /** Tools panel arrow (closed: down, open: up). */
    val ChevronDown: ImageVector by lazy {
        icon("X14u.ChevronDown") { stroke(2.2f) { moveTo(6.5f, 9.5f); lineTo(12f, 15f); lineTo(17.5f, 9.5f) } }
    }
    /** Undo: a hooked arrow back to the left. */
    val Undo: ImageVector by lazy {
        icon("X14u.Undo") {
            stroke(2f) {
                moveTo(9f, 14f); lineTo(4.5f, 9.5f); lineTo(9f, 5f)
                moveTo(4.5f, 9.5f); lineTo(14.5f, 9.5f)
                arcTo(5f, 5f, 0f, false, true, 14.5f, 19.5f)
                lineTo(11f, 19.5f)
            }
        }
    }
    val ChevronUp: ImageVector by lazy {
        icon("X14u.ChevronUp") { stroke(2.2f) { moveTo(6.5f, 14.5f); lineTo(12f, 9f); lineTo(17.5f, 14.5f) } }
    }

    /** Burst format JPEG: one photo sheet (a small landscape on it). */
    val SheetOne: ImageVector by lazy {
        icon("X14u.SheetOne") {
            stroke {
                moveTo(8f, 3f); lineTo(16f, 3f)
                arcTo(1.5f, 1.5f, 0f, false, true, 17.5f, 4.5f); lineTo(17.5f, 19.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 16f, 21f); lineTo(8f, 21f)
                arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 19.5f); lineTo(6.5f, 4.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 8f, 3f); close()
            }
            stroke(1.6f) { moveTo(8.8f, 17f); lineTo(11f, 13.8f); lineTo(13f, 15.8f); lineTo(14.2f, 14.6f); lineTo(15.4f, 17f) }
        }
    }

    /** Burst format JPEG + DNG: two overlapping sheets (the back one shows above and right of the front). */
    val SheetTwo: ImageVector by lazy {
        icon("X14u.SheetTwo") {
            stroke { moveTo(9.5f, 5.5f); lineTo(9.5f, 3.5f); arcTo(1f, 1f, 0f, false, true, 10.5f, 2.5f); lineTo(19f, 2.5f)
                arcTo(1f, 1f, 0f, false, true, 20f, 3.5f); lineTo(20f, 17f); arcTo(1f, 1f, 0f, false, true, 19f, 18f); lineTo(16.5f, 18f) }
            stroke {
                moveTo(5.5f, 6f); lineTo(14f, 6f)
                arcTo(1.5f, 1.5f, 0f, false, true, 15.5f, 7.5f); lineTo(15.5f, 20f)
                arcTo(1.5f, 1.5f, 0f, false, true, 14f, 21.5f); lineTo(5.5f, 21.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 4f, 20f); lineTo(4f, 7.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 5.5f, 6f); close()
            }
            stroke(1.6f) { moveTo(6.2f, 18f); lineTo(8.4f, 14.8f); lineTo(10.4f, 16.8f); lineTo(11.6f, 15.6f); lineTo(12.8f, 18f) }
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
