package com.rawr.camera.x14u

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * X14U 50 MP through the camera's own pipeline, made developable by Rawr (GCam's way: its 50 MP "RAW" is
 * synthesized from the full-size output). The still comes as YUV with the plain keys (unit white-balance gains,
 * identity colour matrix, AWB, noise reduction, sharpening, distortion and aberration off, sRGB curve): that's
 * camera-native RGB after the HAL's remosaic, demosaic and lens shading. Here the sRGB curve is undone and the
 * linear camera RGB is laid out as an RGGB mosaic in a 16-bit DNG with the camera's colour matrices and the
 * viewfinder's white balance, so any DNG developer (Rawr's renderer) treats it like the sensor's RAW.
 */
object HalRawSynth {
    const val WHITE = 65535

    /** Per-shot values the DNG carries. */
    data class ShotInfo(
        val exposureNs: Long?,
        val iso: Int?,
        val aperture: Float?,
        val focalLength: Float?,
        /** The viewfinder's AWB gains (R, G, B) before the still. */
        val wbGains: FloatArray?,
        val deviceRotationDegrees: Int
    )

    /** Writes [nv21] (full-range BT.601, sRGB-coded camera RGB) as a CFA DNG at [file]. */
    fun writeDng(file: File, nv21: ByteArray, width: Int, height: Int, chars: CameraCharacteristics, shot: ShotInfo) {
        // sRGB decode at 1/16 code steps (the YUV->RGB conversion lands between 8-bit codes).
        val lut = IntArray(256 * 16 + 1) { i ->
            val v = i / (255.0 * 16)
            val lin = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            (lin * WHITE).roundToInt().coerceIn(0, WHITE)
        }
        val ifd = buildIfd(width, height, chars, shot)
        BufferedOutputStream(FileOutputStream(file), 1 shl 20).use { out ->
            out.write(ifd)
            val row = ByteArray(width * 2)
            val uvBase = width * height
            for (y in 0 until height) {
                val yRow = y * width
                val uvRow = uvBase + (y shr 1) * width
                val evenRow = (y and 1) == 0
                for (x in 0 until width) {
                    val yy = (nv21[yRow + x].toInt() and 0xFF).toFloat()
                    val uvi = uvRow + (x and 1.inv())
                    val v = (nv21[uvi].toInt() and 0xFF) - 128f
                    val u = (nv21[uvi + 1].toInt() and 0xFF) - 128f
                    val evenCol = (x and 1) == 0
                    // RGGB: R at (even, even), B at (odd, odd), G elsewhere.
                    val c = when {
                        evenRow && evenCol -> yy + 1.402f * v
                        !evenRow && !evenCol -> yy + 1.772f * u
                        else -> yy - 0.344136f * u - 0.714136f * v
                    }
                    val code = (c * 16f).roundToInt().coerceIn(0, 255 * 16)
                    val s = lut[code]
                    row[x * 2] = s.toByte()
                    row[x * 2 + 1] = (s shr 8).toByte()
                }
                out.write(row)
            }
        }
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun shorts(vararg v: Int) = le(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array()
    private fun longs(vararg v: Long) = le(v.size * 4).apply { v.forEach { putInt(it.toInt()) } }.array()
    private fun ascii(s: String) = (s + "\u0000").toByteArray(Charsets.US_ASCII)
    private fun rationals(vararg v: Double, signed: Boolean = false) = le(v.size * 8).apply {
        v.forEach { d ->
            val den = 1_000_000
            putInt((d * den).roundToInt()); putInt(den)
        }
    }.array()
    private fun matrix(t: ColorSpaceTransform?): ByteArray? = t?.let {
        val values = DoubleArray(9) { i -> it.getElement(i % 3, i / 3).toDouble() }
        rationals(*values, signed = true)
    }

    private fun buildIfd(width: Int, height: Int, chars: CameraCharacteristics, shot: ShotInfo): ByteArray {
        val turns = ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - shot.deviceRotationDegrees + 360) % 360
        val orientation = when (turns) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
        val gains = shot.wbGains
        val neutral = if (gains != null && gains.all { it > 0f }) {
            doubleArrayOf(gains[1] / gains[0].toDouble(), 1.0, gains[1] / gains[2].toDouble())
        } else doubleArrayOf(0.5, 1.0, 0.6)
        val entries = mutableListOf(
            Entry(254, 4, 1, longs(0)),
            Entry(256, 4, 1, longs(width.toLong())),
            Entry(257, 4, 1, longs(height.toLong())),
            Entry(258, 3, 1, shorts(16)),
            Entry(259, 3, 1, shorts(1)),
            Entry(262, 3, 1, shorts(32803)),
            Entry(271, 2, 0, ascii(Build.MANUFACTURER)),
            Entry(272, 2, 0, ascii(Build.MODEL)),
            Entry(273, 4, 1, longs(0)),  // patched below
            Entry(274, 3, 1, shorts(orientation)),
            Entry(277, 3, 1, shorts(1)),
            Entry(278, 4, 1, longs(height.toLong())),
            Entry(279, 4, 1, longs(width.toLong() * height * 2)),
            Entry(284, 3, 1, shorts(1)),
            Entry(305, 2, 0, ascii("Rawr X14U (50 MP from the camera's full-size output)")),
            Entry(33421, 3, 2, shorts(2, 2)),
            Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2)),
            Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0)),
            Entry(50707, 1, 4, byteArrayOf(1, 1, 0, 0)),
            Entry(50708, 2, 0, ascii("${Build.MANUFACTURER} ${Build.MODEL}")),
            Entry(50714, 4, 1, longs(0)),
            Entry(50717, 4, 1, longs(WHITE.toLong())),
            Entry(50728, 5, 3, rationals(*neutral))
        )
        shot.exposureNs?.let { entries += Entry(33434, 5, 1, rationals(it / 1e9)) }
        shot.aperture?.let { entries += Entry(33437, 5, 1, rationals(it.toDouble())) }
        shot.iso?.let { entries += Entry(34855, 3, 1, shorts(it.coerceIn(1, 65535))) }
        shot.focalLength?.let { entries += Entry(37386, 5, 1, rationals(it.toDouble())) }
        matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))?.let { entries += Entry(50721, 10, 9, it) }
        matrix(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))?.let { entries += Entry(50722, 10, 9, it) }
        matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))?.let { entries += Entry(50723, 10, 9, it) }
        matrix(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))?.let { entries += Entry(50724, 10, 9, it) }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.let { entries += Entry(50778, 3, 1, shorts(it)) }
        chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.let { entries += Entry(50779, 3, 1, shorts(it.toInt())) }
        matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))?.let { entries += Entry(50964, 10, 9, it) }
        matrix(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))?.let { entries += Entry(50965, 10, 9, it) }
        entries.sortBy { it.tag }

        // Layout: header, IFD, out-of-line values, then the pixels (strip offset patched in).
        val ifdSize = 2 + entries.size * 12 + 4
        var extra = 8 + ifdSize
        val extraData = java.io.ByteArrayOutputStream()
        val offsets = IntArray(entries.size)
        entries.forEachIndexed { i, e ->
            if (e.data.size > 4) {
                offsets[i] = extra + extraData.size()
                extraData.write(e.data)
                if (extraData.size() % 2 == 1) extraData.write(0)
            }
        }
        val pixelOffset = (8 + ifdSize + extraData.size() + 15) and 15.inv()
        val buf = le(pixelOffset)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        buf.putShort(entries.size.toShort())
        entries.forEachIndexed { i, e ->
            buf.putShort(e.tag.toShort()).putShort(e.type.toShort())
            val count = if (e.type == 2) e.data.size else e.count
            buf.putInt(count)
            when {
                e.tag == 273 -> buf.putInt(pixelOffset)
                e.data.size > 4 -> buf.putInt(offsets[i])
                else -> buf.put(e.data.copyOf(4))
            }
        }
        buf.putInt(0)
        buf.put(extraData.toByteArray())
        return buf.array()
    }

    /** The viewfinder's AWB gains from a preview result (R, G, B). */
    fun wbGains(result: CaptureResult): FloatArray? = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g: RggbChannelVector ->
        floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue)
    }
}
