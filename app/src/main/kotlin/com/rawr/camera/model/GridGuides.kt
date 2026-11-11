package com.rawr.camera.model

import kotlin.math.sqrt

/** Composition guide geometry in frame-normalised coordinates (0..1 on each axis, y down). */
object GridGuides {
    val PHI: Float = ((1.0 + sqrt(5.0)) / 2.0).toFloat()

    sealed interface Shape
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Shape
    /** Quarter-ellipse around (cx, cy), radii rx / ry; angles in degrees, 0 = right, 90 = down. */
    data class Arc(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val startDeg: Float, val sweepDeg: Float) : Shape
    /** A small + marker. */
    data class Point(val x: Float, val y: Float) : Shape

    /** Mirror axes the spiral / triangle variants draw with (applied about the frame centre). */
    fun flips(mode: GridMode): Pair<Boolean, Boolean> = when (mode) {
        GridMode.SpiralBottomRight -> true to false
        GridMode.SpiralTopLeft -> false to true
        GridMode.SpiralTopRight -> true to true
        GridMode.GoldenTrianglesMirrored -> true to false
        else -> false to false
    }

    fun shapes(mode: GridMode): List<Shape> = when (mode) {
        GridMode.Off -> emptyList()
        GridMode.Thirds -> grid(1f / 3f, 2f / 3f)
        GridMode.FourByFour -> grid(.25f, .5f, .75f)
        GridMode.Cross -> listOf(Line(.5f, .2f, .5f, .8f), Line(.2f, .5f, .8f, .5f))
        GridMode.ThirdsPoints -> listOf(1f / 3f, 2f / 3f).flatMap { x -> listOf(1f / 3f, 2f / 3f).map { y -> Point(x, y) } }
        GridMode.GoldenRatio -> (1f - 1f / PHI).let { a -> grid(a, 1f - a) }
        GridMode.Diagonals -> diagonals45()
        GridMode.GoldenTriangles, GridMode.GoldenTrianglesMirrored -> triangles()
        GridMode.SpiralBottomLeft, GridMode.SpiralBottomRight, GridMode.SpiralTopLeft, GridMode.SpiralTopRight -> spiral()
    }

    private fun grid(vararg at: Float): List<Shape> = at.flatMap { f -> listOf(Line(f, 0f, f, 1f), Line(0f, f, 1f, f)) }

    /**
     * Diagonal method: 45° lines from each corner (Lightroom's "Diagonal" overlay). In a 3:4 portrait frame the
     * 45° line from a top corner meets the far side at 3/4 of the height. [aspect] = width / height.
     */
    fun diagonals45(aspect: Float = 3f / 4f): List<Shape> {
        // Along x by the width, y moves the same distance: width/height in normalised y.
        val dy = aspect.coerceAtMost(1f)
        val dx = (1f / aspect).coerceAtMost(1f)
        return listOf(
            Line(0f, 0f, dx, dy), Line(1f, 0f, 1f - dx, dy),
            Line(0f, 1f, dx, 1f - dy), Line(1f, 1f, 1f - dx, 1f - dy)
        )
    }

    /** Golden triangles: one diagonal, and the perpendiculars to it from the two other corners. */
    fun triangles(aspect: Float = 3f / 4f): List<Shape> {
        // Perpendicularity is in real (not normalised) space: work in width = aspect, height = 1.
        val w = aspect
        fun foot(px: Float, py: Float): Pair<Float, Float> {
            val t = (px * w + py * 1f) / (w * w + 1f)
            return t * w to t
        }
        val (ax, ay) = foot(w, 0f)
        val (bx, by) = foot(0f, 1f)
        return listOf(Line(0f, 0f, 1f, 1f), Line(1f, 0f, ax / w, ay), Line(0f, 1f, bx / w, by))
    }

    /**
     * Golden spiral: the frame is cut by 1/φ of its height (top), width (right), height (bottom), width (left),
     * ... and each cut piece holds a quarter-ellipse joining the previous one. The eye lands in the lower left.
     */
    fun spiral(steps: Int = 12): List<Shape> {
        var x = 0f
        var y = 0f
        var w = 1f
        var h = 1f
        val out = ArrayList<Shape>(steps)
        for (i in 0 until steps) {
            when (i % 4) {
                0 -> { // top piece; centre at its bottom-left
                    val c = h / PHI
                    out += Arc(x, y + c, w, c, 270f, 90f)
                    y += c; h -= c
                }
                1 -> { // right piece; centre at its top-left
                    val c = w / PHI
                    out += Arc(x + w - c, y, c, h, 0f, 90f)
                    w -= c
                }
                2 -> { // bottom piece; centre at its top-right
                    val c = h / PHI
                    out += Arc(x + w, y + h - c, w, c, 90f, 90f)
                    h -= c
                }
                else -> { // left piece; centre at its bottom-right
                    val c = w / PHI
                    out += Arc(x + c, y + h, c, h, 180f, 90f)
                    x += c; w -= c
                }
            }
        }
        return out
    }
}
