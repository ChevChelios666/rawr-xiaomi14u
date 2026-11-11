package com.rawr.camera.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGuidesTest {
    private fun point(a: GridGuides.Arc, deg: Float): Pair<Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        return (a.cx + a.rx * cos(r)).toFloat() to (a.cy + a.ry * sin(r)).toFloat()
    }

    @Test
    fun spiralIsOneContinuousCurveFromTheTopLeftCorner() {
        val arcs = GridGuides.spiral().map { it as GridGuides.Arc }
        val (sx, sy) = point(arcs.first(), arcs.first().startDeg)
        assertEquals(0f, sx, 1e-5f)
        assertEquals(0f, sy, 1e-5f)
        arcs.zipWithNext().forEach { (a, b) ->
            val (ex, ey) = point(a, a.startDeg + a.sweepDeg)
            val (nx, ny) = point(b, b.startDeg)
            assertEquals(ex, nx, 1e-5f)
            assertEquals(ey, ny, 1e-5f)
        }
        // The eye ends in the lower-left quarter.
        val last = arcs.last()
        assertTrue(last.cx < .5f && last.cy > .5f)
    }

    @Test
    fun trianglePerpendicularsMeetTheDiagonalAtRightAngles() {
        val aspect = 3f / 4f
        val lines = GridGuides.triangles(aspect).map { it as GridGuides.Line }
        val d = lines[0]
        lines.drop(1).forEach { l ->
            val dot = ((d.x1 - d.x0) * aspect) * ((l.x1 - l.x0) * aspect) + (d.y1 - d.y0) * (l.y1 - l.y0)
            assertEquals(0f, dot, 1e-5f)
            // The foot lies on the diagonal (x == y in normalised space).
            assertEquals(l.x1, l.y1, 1e-5f)
        }
    }

    @Test
    fun diagonalsRunAt45DegreesInTheRealFrame() {
        val aspect = 3f / 4f
        GridGuides.diagonals45(aspect).map { it as GridGuides.Line }.forEach { l ->
            assertEquals(abs((l.x1 - l.x0) * aspect), abs(l.y1 - l.y0), 1e-5f)
        }
    }

    @Test
    fun everyModeDraws() {
        GridMode.entries.filter { it != GridMode.Off }.forEach { assertTrue(it.name, GridGuides.shapes(it).isNotEmpty()) }
    }
}
