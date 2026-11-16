package com.rawr.camera.x14u

import java.nio.ByteBuffer

/** X14U 50 MP (camera's own pipeline): the plain still laid out as the RGGB mosaic natively (camera/HalRawSynthNative). */
object HalRawSynthNative {
    init { System.loadLibrary("rawrcam_native") }

    @JvmStatic external fun jpegSize(jpeg: ByteArray): IntArray?

    /** [lut]: 256 entries (8-bit code -> linear 0..65535); [out] a direct buffer of [width] x [height] uint16. */
    @JvmStatic external fun jpegMosaic(jpeg: ByteArray, out: ByteBuffer, width: Int, height: Int, lut: IntArray): Boolean

    /** [lut]: 255 * 16 + 1 entries at 1/16 code steps. */
    @JvmStatic external fun nv21Mosaic(nv21: ByteArray, width: Int, height: Int, out: ByteBuffer, lut: IntArray): Boolean
}
