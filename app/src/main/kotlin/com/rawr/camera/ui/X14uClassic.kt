package com.rawr.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawr.camera.model.ControlSurfaceStyle
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * X14U Classic style (Nov 18): the capture screen drawn as a camera body after the user's mockups: brushed-metal top
 * plate and readout strip, black pebbled leather, metal dials with conical brushing, a glass window for the rulers.
 * Only drawing: layout and behaviour are the Minimal style's. Textures are made once, in code (no image assets).
 */
internal object ClassicColors {
    /** The mockup's orange (selected values, lit mode); replaces the coral accent in this style. */
    val Accent = Color(0xFFE8763E)
    /** Top bar labels: warm, like engraving filled with copper paint. */
    val Copper = Color(0xFFC08A62)
    val Engraved = Color(0xFFD9D2C7)
    val EngravedDim = Color(0xFF8D877E)
    /** Plates: the top bar a step lighter than the body plates, the mode band a step darker. */
    val MetalTop = Color(0xFF262625)
    val Metal = Color(0xFF1E1E1D)
    val MetalDark = Color(0xFF181817)
    val Groove = Color(0xFF050505)
    val Red = Color(0xFFC23A2E)
    val Glass = Color(0xFF0A0A0A)
}

@Composable
internal fun isClassicStyle(): Boolean = LocalControlSurfaceStyle.current == ControlSurfaceStyle.Classic

/** The textures, made on first use: brushed streaks (an overlay), leather grain, the knurl of the ruler window. */
internal object ClassicTextures {
    val brushed: ShaderBrush by lazy { tiled(brushedBitmap(512, 192, 7)) }
    val leather: ShaderBrush by lazy { tiled(leatherBitmap(11)) }
    val knurl: ShaderBrush by lazy { tiled(knurlBitmap(7)) }

    private fun tiled(image: ImageBitmap) = ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))

    private fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 374761393 + y * 668265263 + seed * 144269504
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    private fun unit(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) and 0xFFFF) / 65535f

    /**
     * Horizontal brushing: each row a random brightness (lightly smoothed), drifting along the row with whole sine
     * periods so the tile repeats seamlessly; light and dark streaks as white / black at low alpha over the plate.
     */
    private fun brushedBitmap(w: Int, h: Int, seed: Int): ImageBitmap {
        val row = FloatArray(h) { unit(0, it, seed) }
        val smooth = FloatArray(h) { i -> (row[(i - 1 + h) % h] + 2f * row[i] + row[(i + 1) % h]) / 4f }
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val c1 = 1 + (hash(1, y, seed) and 1)
            val c2 = 3 + (hash(2, y, seed) and 3)
            val p1 = unit(3, y, seed) * 6.2832f
            val p2 = unit(4, y, seed) * 6.2832f
            for (x in 0 until w) {
                val t = x.toFloat() / w * 6.2832f
                val streak = (smooth[y] - .5f) * 1.1f + .22f * sin(c1 * t + p1) + .1f * sin(c2 * t + p2)
                val grain = unit(x, y, seed + 9) - .5f
                val v = streak * .45f + grain * .55f
                val alpha = (abs(v) * 42f).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (alpha shl 24) or (if (v > 0f) 0xFFFFFF else 0x000000)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * Pebbled leather (v2, Nov 18, after the user's mockup): small irregular grains a little wider than tall (cellular
     * noise over a 24 x 41 grid, wrapping at the tile's edges) as a height field: rounded domes steep at the grooves,
     * each a little higher or lower, fine grain; softened once, then lit from the top left (diffuse plus a tight
     * highlight on the shoulders), grooves kept dark, and a few broad lighter and darker patches. Sized from the
     * screen density so the grain is the same in dp on any phone (tuned at 2.625 px per dp).
     */
    private fun leatherBitmap(seed: Int): ImageBitmap {
        val k = (android.content.res.Resources.getSystem().displayMetrics.density / 2.625f).coerceIn(.5f, 2f)
        val nx = 24
        val ny = 41
        val size = (288 * k).toInt().coerceAtLeast(96)
        val sx = size.toFloat() / nx
        val sy = size.toFloat() / ny
        val fx = FloatArray(nx * ny)
        val fy = FloatArray(nx * ny)
        val lift = FloatArray(nx * ny)
        for (j in 0 until ny) for (i in 0 until nx) {
            fx[j * nx + i] = (i + .1f + .8f * unit(i, j, seed)) * sx
            fy[j * nx + i] = (j + .1f + .8f * unit(i, j, seed + 1)) * sy
            lift[j * nx + i] = unit(i, j, seed + 2)
        }
        val edge = FloatArray(size * size)
        val height = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val ci = (x / sx).toInt()
            val cj = (y / sy).toInt()
            var d1 = Float.MAX_VALUE
            var d2 = Float.MAX_VALUE
            var bt = 0f
            for (dj in -1..1) for (di in -1..1) {
                val i = ci + di
                val j = cj + dj
                val wi = ((i % nx) + nx) % nx
                val wj = ((j % ny) + ny) % ny
                val px = fx[wj * nx + wi] + (i - wi) * sx
                val py = fy[wj * nx + wi] + (j - wj) * sy
                val dx = (x - px) / sx
                val dy = (y - py) / sy
                val d = sqrt(dx * dx + dy * dy)
                if (d < d1) {
                    d2 = d1
                    d1 = d
                    bt = lift[wj * nx + wi]
                } else if (d < d2) d2 = d
            }
            val e = d2 - d1
            val t = (e / .6f).coerceIn(0f, 1f)
            edge[y * size + x] = e
            height[y * size + x] = sqrt(t * (2f - t)) * (.7f + .3f * bt) + (unit(x, y, seed + 7) - .5f) * .08f
        }
        fun at(a: FloatArray, x: Int, y: Int) = a[((y + size) % size) * size + (x + size) % size]
        val h = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0f
            for (b in -1..1) for (a in -1..1) sum += at(height, x + a, y + b)
            h[y * size + x] = sum / 9f
        }
        val g = FloatArray(36) { unit(it % 6, it / 6, seed + 9) }
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val gx = (at(h, x + 1, y) - at(h, x - 1, y)) * 3f * k
            val gy = (at(h, x, y + 1) - at(h, x, y - 1)) * 3f * k
            val n = sqrt(gx * gx + gy * gy + 1f)
            val diff = (gx * .55f + gy * .65f + .52f) / n
            val c = diff.coerceIn(0f, 1f)
            val c2 = c * c
            val c4 = c2 * c2
            val spec = c4 * c4
            val hv = h[y * size + x]
            val groove = (edge[y * size + x] / .12f).coerceIn(0f, 1f)
            var v = 10f + 10f * hv + 34f * (diff - .3f).coerceIn(0f, 1f) + 60f * spec
            v *= (.2f + .8f * groove) * (.45f + .55f * (hv * 1.6f).coerceIn(0f, 1f))
            // Broad sheen: smooth noise on a 6 x 6 grid, wrapping.
            val xx = x.toFloat() / size * 6f
            val yy = y.toFloat() / size * 6f
            val x0 = xx.toInt()
            val y0 = yy.toInt()
            var tx = xx - x0
            var ty = yy - y0
            tx = tx * tx * (3f - 2f * tx)
            ty = ty * ty * (3f - 2f * ty)
            fun gv(i: Int, j: Int) = g[(j % 6) * 6 + i % 6]
            val sheen = (gv(x0, y0) * (1 - tx) + gv(x0 + 1, y0) * tx) * (1 - ty) + (gv(x0, y0 + 1) * (1 - tx) + gv(x0 + 1, y0 + 1) * tx) * ty
            v *= .8f + .4f * sheen
            val v8 = v.toInt().coerceIn(0, 255)
            pixels[y * size + x] = (0xFF shl 24) or (v8 shl 16) or (v8 shl 8) or v8
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Knurled metal (the ruler window's ends): a grid of small pyramids, lit from the top left. */
    private fun knurlBitmap(cell: Int): ImageBitmap {
        val pixels = IntArray(cell * cell)
        val c = (cell - 1) / 2f
        for (y in 0 until cell) for (x in 0 until cell) {
            val dx = x - c
            val dy = y - c
            val ridge = maxOf(abs(dx), abs(dy)) / c
            val lit = if (dx + dy < 0f) 16 else -10
            val g = (54 + lit - (ridge * 22f).toInt()).coerceIn(0, 255)
            pixels[y * cell + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(pixels, cell, cell, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/** A brushed-metal plate over [topLeft]/[size]: base colour, brushing, a light top edge and a dark bottom edge. */
internal fun DrawScope.drawMetalPlate(topLeft: Offset, size: Size, base: Color, bevel: Boolean = true) {
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(base, topLeft, size)
    drawRect(ClassicTextures.brushed, topLeft, size)
    if (bevel) {
        val hair = 1.dp.toPx()
        drawRect(Color.White.copy(alpha = .09f), topLeft, Size(size.width, hair))
        drawRect(Color.Black.copy(alpha = .75f), Offset(topLeft.x, topLeft.y + size.height - hair * 1.5f), Size(size.width, hair * 1.5f))
    }
}

internal fun DrawScope.drawLeather(topLeft: Offset, size: Size) {
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(ClassicTextures.leather, topLeft, size)
}

/** Conical brushing of a round metal cap: highlights at the 1:30 and 7:30 positions, fine turned rings. */
private fun DrawScope.drawCap(center: Offset, radius: Float, light: Float = 1f) {
    fun g(v: Int) = Color((v * light).toInt().coerceIn(0, 255), (v * light).toInt().coerceIn(0, 255), (v * light).toInt().coerceIn(0, 255))
    drawCircle(
        Brush.sweepGradient(listOf(g(42), g(104), g(38), g(22), g(96), g(36), g(42)), center),
        radius, center
    )
    val step = 1.3.dp.toPx()
    val hair = .6f
    var r = step
    while (r < radius) {
        drawCircle(Color.White.copy(alpha = .03f), r, center, style = Stroke(hair))
        r += step
    }
    // Domed: darker toward the rim.
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .38f)), center, radius), radius, center)
    drawArc(
        Color.White.copy(alpha = .16f), 200f, 130f, false,
        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2f, radius * 2f),
        style = Stroke(1.dp.toPx())
    )
}

/**
 * A metal dial button: a dark collar, a bevelled metal ring, then the brushed cap. [ring] puts a coloured ring between
 * collar and cap (the shutter's red one). Returns the cap's radius (for what goes on it).
 */
internal fun DrawScope.drawMetalKnob(center: Offset, radius: Float, ring: Color? = null, pressed: Boolean = false): Float {
    drawCircle(Color(0xFF040404), radius, center)
    drawCircle(
        Brush.sweepGradient(
            listOf(Color(0xFF4E4E4D), Color(0xFF141414), Color(0xFF62625F), Color(0xFF181818), Color(0xFF4E4E4D)), center
        ),
        radius * .93f, center
    )
    var cap = radius * .86f
    drawCircle(Color(0xFF080808), cap, center)
    if (ring != null) {
        drawCircle(ring, cap - .5.dp.toPx(), center)
        drawCircle(Color.Black.copy(alpha = .35f), cap - .5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        cap -= 3.5.dp.toPx()
        drawCircle(Color(0xFF080808), cap, center)
    }
    cap -= 1.5.dp.toPx()
    drawCap(center, cap, if (pressed) .8f else 1f)
    return cap
}

/** A round metal button of [size] with [content] on its cap. */
@Composable
internal fun ClassicKnob(
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    pressed: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier.size(size).drawBehind { drawMetalKnob(center, this.size.minDimension / 2f, ring, pressed) },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** The small knurled dial (exposure, right of the lens row): a ridged rim with engraved ticks around a metal cap. */
internal fun DrawScope.drawKnurledDial(center: Offset, radius: Float) {
    drawCircle(Color(0xFF040404), radius, center)
    val ridges = 48
    for (k in 0 until ridges) {
        val a = Math.toRadians(k * 360.0 / ridges)
        val cos = kotlin.math.cos(a).toFloat()
        val sinA = kotlin.math.sin(a).toFloat()
        val lit = .25f + .2f * kotlin.math.cos(a + Math.PI * .75).toFloat()
        drawLine(
            Color(0xFF8A8A88).copy(alpha = lit.coerceIn(.05f, .5f)),
            Offset(center.x + cos * radius * .8f, center.y + sinA * radius * .8f),
            Offset(center.x + cos * radius * .97f, center.y + sinA * radius * .97f),
            strokeWidth = .8.dp.toPx()
        )
    }
    drawCircle(Color(0xFF0A0A0A), radius * .78f, center)
    drawCap(center, radius * .74f, .85f)
    // Engraved scale on the cap's edge.
    for (k in 0 until 24) {
        val a = Math.toRadians(k * 15.0 - 90.0)
        val major = k % 6 == 0
        val r1 = radius * (if (major) .56f else .62f)
        drawLine(
            Color.White.copy(alpha = if (major) .5f else .25f),
            Offset(center.x + kotlin.math.cos(a).toFloat() * r1, center.y + kotlin.math.sin(a).toFloat() * r1),
            Offset(center.x + kotlin.math.cos(a).toFloat() * radius * .7f, center.y + kotlin.math.sin(a).toFloat() * radius * .7f),
            strokeWidth = .7.dp.toPx()
        )
    }
}

/** A colour wheel set in a metal ring (the Look button). */
internal fun DrawScope.drawHueWheel(center: Offset, radius: Float, dim: Boolean) {
    drawCircle(Color(0xFF040404), radius, center)
    drawCircle(
        Brush.sweepGradient(listOf(Color(0xFF3C3C3B), Color(0xFF151515), Color(0xFF4A4A48), Color(0xFF161616), Color(0xFF3C3C3B)), center),
        radius * .93f, center
    )
    val wheel = radius * .7f
    val a = if (dim) .45f else .95f
    drawCircle(
        Brush.sweepGradient(
            listOf(Color(0xFFFF4D4D), Color(0xFFFFC24D), Color(0xFFB8F04D), Color(0xFF4DE0A0), Color(0xFF4DB8FF),
                Color(0xFF7A5CFF), Color(0xFFE04DC8), Color(0xFFFF4D4D)).map { it.copy(alpha = a) },
            center
        ),
        wheel, center
    )
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .85f * a), Color.Transparent), center, wheel * .85f), wheel, center)
    drawCircle(Color.Black.copy(alpha = .5f), wheel, center, style = Stroke(1.dp.toPx()))
}

/**
 * The ruler's window, after the user's mockup: a metal rim, then a recess where the scale turns on a drum (shaded
 * darker toward both ends, as a cylinder), knurled rollers at the ends, a soft shadow under the rim at the top, a
 * faint reflection on the glass and a hairline catch-light along the bottom.
 */
internal fun Modifier.classicWindow(knurlWidth: Dp = 30.dp): Modifier = drawWithContent {
    val outer = CornerRadius(7.dp.toPx())
    val inner = CornerRadius(5.dp.toPx())
    // The rim: brushed metal with a light top edge and a dark lower one.
    drawRoundRect(Color(0xFF040404), cornerRadius = outer)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF4A4A48), Color(0xFF2A2A29), Color(0xFF161615))),
        Offset(1f, 1f), Size(size.width - 2f, size.height - 2f), outer
    )
    val inset = 3.dp.toPx()
    val pane = Size(size.width - inset * 2, size.height - inset * 2)
    drawRoundRect(Color(0xFF020202), Offset(inset - 1f, inset - 1f), Size(pane.width + 2f, pane.height + 2f), inner)
    // The drum.
    drawRoundRect(
        Brush.horizontalGradient(
            0f to Color(0xFF050505), .22f to Color(0xFF151515), .5f to Color(0xFF1C1C1B), .78f to Color(0xFF151515), 1f to Color(0xFF050505),
            startX = inset, endX = inset + pane.width
        ),
        Offset(inset, inset), pane, inner
    )
    drawContent()
    // Knurled rollers at the ends, rounded like the drum: lit in the middle, dark at the top and bottom.
    val k = knurlWidth.toPx().coerceAtMost(pane.width / 4f)
    listOf(inset, inset + pane.width - k).forEach { x ->
        drawRect(ClassicTextures.knurl, Offset(x, inset), Size(k, pane.height))
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .75f), .35f to Color.Black.copy(alpha = .05f), .6f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f),
                startY = inset, endY = inset + pane.height
            ),
            Offset(x, inset), Size(k, pane.height)
        )
        drawRect(Color.Black.copy(alpha = .7f), Offset(if (x == inset) x + k - 1.dp.toPx() else x, inset), Size(1.dp.toPx(), pane.height))
    }
    // Curvature over the scale: darker toward both ends.
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = .7f), .3f to Color.Transparent, .7f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f),
            startX = inset + k, endX = inset + pane.width - k
        ),
        Offset(inset + k, inset), Size(pane.width - k * 2, pane.height)
    )
    // Shadow under the rim, the glass's reflection, the catch-light.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent), inset, inset + 9.dp.toPx()),
        Offset(inset, inset), Size(pane.width, 9.dp.toPx()))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent), inset + 2.dp.toPx(), inset + pane.height * .4f),
        Offset(inset, inset + 2.dp.toPx()), Size(pane.width, pane.height * .4f))
    drawRect(Color.White.copy(alpha = .12f), Offset(inset, size.height - inset), Size(pane.width, 1f))
}

/**
 * Where the plates go, top to bottom (all in the layout's own coordinates): the top bar, the bezel around the
 * picture, the readout strip, the mode band (a metal plate while it shows the modes; the action row's leather shows
 * when a control is open). Everything else is leather.
 */
internal class ClassicBodyPlan(
    val topBar: ClosedFloatingPointRange<Float>,
    val bezel: ClosedFloatingPointRange<Float>,
    val picture: androidx.compose.ui.geometry.Rect,
    val readouts: ClosedFloatingPointRange<Float>,
    val band: ClosedFloatingPointRange<Float>?
)

@Composable
internal fun X14uClassicBody(plan: ClassicBodyPlan, modifier: Modifier) {
    Box(modifier.drawBehind {
        drawLeather(Offset.Zero, size)
        // The status bar stays black.
        drawRect(Color.Black, Offset.Zero, Size(size.width, plan.topBar.start))
        fun plate(range: ClosedFloatingPointRange<Float>, color: Color) =
            drawMetalPlate(Offset(0f, range.start), Size(size.width, range.endInclusive - range.start), color)
        plate(plan.topBar, ClassicColors.MetalTop)
        // The bezel: metal around the picture with a dark recess just outside it, then the corner screws' place left
        // plain (the corner marks are the screen's).
        plate(plan.bezel, ClassicColors.Metal)
        val p = plan.picture
        val recess = 3.dp.toPx()
        drawRoundRect(
            ClassicColors.Groove, Offset(p.left - recess, p.top - recess), Size(p.width + recess * 2, p.height + recess * 2),
            CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            Color.White.copy(alpha = .06f), Offset(p.left - recess, p.top - recess), Size(p.width + recess * 2, p.height + recess * 2),
            CornerRadius(4.dp.toPx()), style = Stroke(1f)
        )
        plate(plan.readouts, ClassicColors.Metal)
        plan.band?.let { plate(it, ClassicColors.MetalDark) }
        // A stitched seam along the leather under the bezel.
        val seamY = (plan.bezel.endInclusive + plan.readouts.start) / 2f
        if (plan.readouts.start - plan.bezel.endInclusive > 4.dp.toPx()) {
            drawLine(
                Color(0xFF2E2C29), Offset(0f, seamY), Offset(size.width, seamY), strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            )
        }
    })
}

/**
 * Classic video: where a plate (top bar, readouts, mode band) crosses the picture running under it, the plate turns to
 * smoked glass over it: its colour at half strength, the brushing faint, and its light top edge.
 */
@Composable
internal fun X14uClassicGlass(plan: ClassicBodyPlan, modifier: Modifier) {
    Box(modifier.drawBehind {
        val p = plan.picture
        fun glass(range: ClosedFloatingPointRange<Float>, color: Color) {
            val top = maxOf(range.start, p.top)
            val bottom = minOf(range.endInclusive, p.bottom)
            if (bottom <= top) return
            val o = Offset(p.left, top)
            val sz = Size(p.width, bottom - top)
            drawRect(color.copy(alpha = .5f), o, sz)
            drawRect(ClassicTextures.brushed, o, sz, alpha = .6f)
            if (range.start >= p.top) drawRect(Color.White.copy(alpha = .07f), o, Size(p.width, 1.dp.toPx()))
        }
        glass(plan.topBar, ClassicColors.MetalTop)
        glass(plan.readouts, ClassicColors.Metal)
        plan.band?.let { glass(it, ClassicColors.MetalDark) }
    })
}
