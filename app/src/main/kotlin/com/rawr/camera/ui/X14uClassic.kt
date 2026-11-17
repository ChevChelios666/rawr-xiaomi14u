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
    val leather: ShaderBrush by lazy { tiled(leatherBitmap(240, 12, 11)) }
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
     * Pebbled leather: a jittered grid of grains (cellular noise, wrapping at the tile's edges), each a low dome lit
     * from the top left, dark creases between them.
     */
    private fun leatherBitmap(size: Int, cell: Int, seed: Int): ImageBitmap {
        val n = size / cell
        val fx = FloatArray(n * n)
        val fy = FloatArray(n * n)
        val tone = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            fx[j * n + i] = (i + .15f + .7f * unit(i, j, seed)) * cell
            fy[j * n + i] = (j + .15f + .7f * unit(i, j, seed + 1)) * cell
            tone[j * n + i] = unit(i, j, seed + 2)
        }
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val ci = x / cell
            val cj = y / cell
            var d1 = Float.MAX_VALUE
            var d2 = Float.MAX_VALUE
            var nx = 0f
            var ny = 0f
            var bt = 0f
            for (dj in -1..1) for (di in -1..1) {
                val i = ci + di
                val j = cj + dj
                val wi = (i + n) % n
                val wj = (j + n) % n
                val px = fx[wj * n + wi] + (i - wi) * cell
                val py = fy[wj * n + wi] + (j - wj) * cell
                val dx = x - px
                val dy = y - py
                val d = sqrt(dx * dx + dy * dy)
                if (d < d1) {
                    d2 = d1
                    d1 = d
                    nx = dx
                    ny = dy
                    bt = tone[wj * n + wi]
                } else if (d < d2) d2 = d
            }
            val edge = d2 - d1
            val dome = (1f - d1 / (cell * .8f)).coerceIn(0f, 1f)
            // Lit from the top left: the side of a grain facing it is lighter.
            val lit = (-(nx + ny) / (cell * 1.4f)).coerceIn(-1f, 1f)
            var v = 15f + bt * 4f + dome * 9f + lit * 7f + (unit(x, y, seed + 5) - .5f) * 3f
            // Soft creases between the grains.
            if (edge < 1.2f) v *= .55f + .375f * edge
            val g = v.toInt().coerceIn(0, 255)
            pixels[y * size + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
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
 * The ruler's glass window: a dark recessed pane with knurled blocks at both ends (drawn over the ruler's faded
 * edges), a soft shadow at the top inside and a hairline highlight on the bottom edge.
 */
internal fun Modifier.classicWindow(knurlWidth: Dp = 34.dp): Modifier = drawWithContent {
    val corner = CornerRadius(5.dp.toPx())
    drawRoundRect(Color(0xFF040404), cornerRadius = corner)
    val inset = 2.dp.toPx()
    drawRoundRect(ClassicColors.Glass, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), corner)
    drawContent()
    val k = knurlWidth.toPx().coerceAtMost(size.width / 4f)
    val h = size.height - inset * 2
    drawRect(ClassicTextures.knurl, Offset(inset, inset), Size(k, h))
    drawRect(ClassicTextures.knurl, Offset(size.width - inset - k, inset), Size(k, h))
    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .1f), Color.Black.copy(alpha = .6f)), inset, inset + k),
        Offset(inset, inset), Size(k, h))
    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Black.copy(alpha = .1f)),
        size.width - inset - k, size.width - inset), Offset(size.width - inset - k, inset), Size(k, h))
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent), inset, inset + 8.dp.toPx()),
        Offset(inset, inset), Size(size.width - inset * 2, 8.dp.toPx()))
    drawRect(Color.White.copy(alpha = .08f), Offset(inset, size.height - inset - 1f), Size(size.width - inset * 2, 1f))
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
