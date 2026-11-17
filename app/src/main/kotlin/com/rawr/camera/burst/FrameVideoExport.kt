package com.rawr.camera.burst

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * X14U video export (burst viewer): a burst's or timed run's frames as a time-lapse / burst video, encoded on the
 * phone. HEVC or H.264 in MP4 (hardware encoder, 8-bit SDR: the frames are 8-bit JPEGs), or a GIF for short loops.
 * Deflicker evens out frame-to-frame brightness jumps (timed runs whose exposure followed the light).
 */
object FrameVideoExport {
    enum class Format(val label: String) { Hevc("HEVC"), H264("H.264"), Gif("GIF") }

    data class Options(
        val format: Format = Format.Hevc,
        val fps: Int = 24,
        /** 1080 or 2160 (the frame's short side); GIF ignores it. */
        val shortSide: Int = 1080,
        val deflicker: Boolean = false
    )

    /** A GIF's long side, and the most frames it takes (GIFs get big fast). */
    const val GIF_LONG_SIDE = 480
    const val GIF_MAX_FRAMES = 150

    data class Result(val uri: Uri?, val message: String)

    /** Runs on the caller's (background) thread; [progress] gets (done, total) and returns false to stop. */
    fun export(context: Context, info: BurstInfo, frames: List<BurstFrame>, options: Options,
               progress: (Int, Int) -> Boolean): Result {
        val files = frames.mapNotNull { it.view }
        if (files.size < 2) return Result(null, "Needs at least 2 frames")
        return when (options.format) {
            Format.Gif -> exportGif(context, info, files.take(GIF_MAX_FRAMES), options, progress)
            else -> exportMp4(context, info, files, options, progress)
        }
    }

    // ---- Brightness (deflicker) ----

    /** Mean luma (0..255) of a small decode of each frame. */
    private fun lumaOf(file: File): Float {
        val bmp = BurstStore.decode(file, 64) ?: return 0f
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        var sum = 0.0
        for (c in px) sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        return (sum / px.size).toFloat()
    }

    /** Per frame gain toward a moving average (9 frames) of the brightness: the trend stays, the jumps go. */
    private fun deflickerGains(files: List<File>): FloatArray {
        val luma = FloatArray(files.size) { lumaOf(files[it]) }
        val half = 4
        return FloatArray(files.size) { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(files.size - 1)
            var s = 0f
            for (k in from..to) s += luma[k]
            val target = s / (to - from + 1)
            if (luma[i] < 1f) 1f else (target / luma[i]).coerceIn(.7f, 1.4f)
        }
    }

    /** [file] drawn into a [w] x [h] frame (fit, black bars), brightened by [gain]. */
    private fun frameBitmap(file: File, w: Int, h: Int, gain: Float): Bitmap? {
        val src = BurstStore.decode(file, maxOf(w, h)) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = (src.width * scale).roundToInt()
        val dh = (src.height * scale).roundToInt()
        val left = (w - dw) / 2
        val top = (h - dh) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (kotlin.math.abs(gain - 1f) > .005f) {
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                setScale(gain, gain, gain, 1f)
            })
        }
        canvas.drawBitmap(src, null, Rect(left, top, left + dw, top + dh), paint)
        src.recycle()
        return out
    }

    /** The output frame size: [shortSide] on the frames' short side, even, within what the encoder takes. */
    private fun frameSize(first: File, shortSide: Int, mime: String): Pair<Int, Int>? {
        val probe = BurstStore.decode(first, 256) ?: return null
        val aspect = probe.width.toFloat() / probe.height
        probe.recycle()
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
        var short = shortSide
        while (short >= 480) {
            val (w, h) = if (aspect >= 1f) ((short * aspect).roundToInt() and -16) to (short and -16)
                else (short and -16) to ((short / aspect).roundToInt() and -16)
            if (caps.isEmpty() || caps.any { it.isSizeSupported(w, h) }) return w to h
            short -= 120
        }
        return null
    }

    private fun exportMp4(context: Context, info: BurstInfo, files: List<File>, options: Options,
                          progress: (Int, Int) -> Boolean): Result {
        val mime = if (options.format == Format.Hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val (w, h) = frameSize(files.first(), options.shortSide, mime) ?: return Result(null, "No encoder for that size")
        val gains = if (options.deflicker) deflickerGains(files) else FloatArray(files.size) { 1f }
        val temp = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val pixels = w.toLong() * h
        // About 0.12 bits per pixel per frame at 24-30 fps for HEVC, half as much again for H.264.
        val bitrate = (pixels * options.fps * (if (options.format == Format.Hevc) .12 else .18)).toLong()
            .coerceIn(4_000_000L, 120_000_000L).toInt()
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var track = -1
        var written = 0
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val info2 = MediaCodec.BufferInfo()
            val frameUs = 1_000_000L / options.fps
            var queued = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (queued >= files.size) {
                            codec.queueInputBuffer(index, 0, 0, queued * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bmp = frameBitmap(files[queued], w, h, gains[queued])
                            val image = codec.getInputImage(index)
                            if (bmp != null && image != null) {
                                writeYuv(bmp, image)
                                bmp.recycle()
                            }
                            codec.queueInputBuffer(index, 0, w * h * 3 / 2, queued * frameUs, 0)
                            queued++
                            if (!progress(queued, files.size)) {
                                temp.delete()
                                return Result(null, "Stopped")
                            }
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info2, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mux.addTrack(codec.outputFormat)
                        mux.start()
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)
                        if (buffer != null && info2.size > 0 && info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) {
                            buffer.position(info2.offset)
                            buffer.limit(info2.offset + info2.size)
                            mux.writeSampleData(track, buffer, info2)
                            written++
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } catch (e: Exception) {
            temp.delete()
            return Result(null, "Video failed: ${e.message}")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (track >= 0) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (written == 0) { temp.delete(); return Result(null, "Video failed: nothing encoded") }
        val seconds = files.size / options.fps.toFloat()
        val label = "${options.format.label} ${h}p ${options.fps} fps" + if (options.deflicker) ", deflickered" else ""
        val uri = publish(context, info, temp, "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "RAWR_${info.folder}_${if (info.isTimed) "TIMELAPSE" else "BURST"}.mp4")
        temp.delete()
        return Result(uri, if (uri != null) "Saved %.1f s video (%s)".format(java.util.Locale.US, seconds, label) else "Can't save the video")
    }

    /** ARGB → the encoder's YUV 4:2:0 (BT.601 limited range), any plane layout. */
    private fun writeYuv(bmp: Bitmap, image: android.media.Image) {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuf = y.buffer
        val yRow = y.rowStride
        val yPix = y.pixelStride
        for (row in 0 until h) {
            val base = row * w
            val rowStart = row * yRow
            for (col in 0 until w) {
                val c = argb[base + col]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yBuf.put(rowStart + col * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        val uBuf = u.buffer
        val vBuf = v.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val c = argb[(row * 2) * w + col * 2]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(row * u.rowStride + col * u.pixelStride, cb.toByte())
                vBuf.put(row * v.rowStride + col * v.pixelStride, cr.toByte())
            }
        }
    }

    private fun publish(context: Context, info: BurstInfo, file: File, mime: String, collection: Uri, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, BurstStore.exportDir(info))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    // ---- GIF ----

    private fun exportGif(context: Context, info: BurstInfo, files: List<File>, options: Options,
                          progress: (Int, Int) -> Boolean): Result {
        val probe = BurstStore.decode(files.first(), 256) ?: return Result(null, "Can't read the frames")
        val aspect = probe.width.toFloat() / probe.height
        probe.recycle()
        val (w, h) = if (aspect >= 1f) GIF_LONG_SIDE to (GIF_LONG_SIDE / aspect).roundToInt()
            else (GIF_LONG_SIDE * aspect).roundToInt() to GIF_LONG_SIDE
        val gains = if (options.deflicker) deflickerGains(files) else FloatArray(files.size) { 1f }
        // One palette for the whole loop, from a few frames spread over it.
        val samples = mutableListOf<Int>()
        listOf(0, files.size / 3, 2 * files.size / 3, files.size - 1).distinct().forEach { i ->
            frameBitmap(files[i], w, h, gains[i])?.let { bmp ->
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                for (k in px.indices step 7) samples += px[k]
            }
        }
        val palette = GifPalette(samples.toIntArray())
        val temp = File(context.cacheDir, "export_${System.currentTimeMillis()}.gif")
        try {
            temp.outputStream().buffered().use { out ->
                val gif = GifWriter(out, w, h, palette.colors)
                val delayCs = (100f / options.fps).roundToInt().coerceAtLeast(2)
                files.forEachIndexed { i, file ->
                    val bmp = frameBitmap(file, w, h, gains[i]) ?: return@forEachIndexed
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    gif.frame(ByteArray(px.size) { k -> palette.index(px[k]).toByte() }, delayCs)
                    if (!progress(i + 1, files.size)) return Result(null, "Stopped")
                }
                gif.finish()
            }
            val uri = publish(context, info, temp, "image/gif", MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "RAWR_${info.folder}.gif")
            return Result(uri, if (uri != null) "Saved GIF (${files.size} frames, ${w}×$h)" else "Can't save the GIF")
        } catch (e: Exception) {
            return Result(null, "GIF failed: ${e.message}")
        } finally {
            temp.delete()
        }
    }
}

/** 256 colours by median cut over [samples]; nearest-colour lookup through a 32³ cube. */
private class GifPalette(samples: IntArray) {
    val colors: IntArray
    private val cube = ByteArray(32 * 32 * 32)

    init {
        val boxes = ArrayList<IntArray>()
        boxes += samples.ifEmpty { intArrayOf(0) }
        while (boxes.size < 256) {
            // Split the box with the widest channel range.
            var best = -1
            var bestRange = 0
            var bestShift = 0
            boxes.forEachIndexed { i, box ->
                if (box.size < 2) return@forEachIndexed
                for (shift in intArrayOf(16, 8, 0)) {
                    var lo = 255
                    var hi = 0
                    for (c in box) { val v = (c shr shift) and 0xFF; if (v < lo) lo = v; if (v > hi) hi = v }
                    if (hi - lo > bestRange) { bestRange = hi - lo; best = i; bestShift = shift }
                }
            }
            if (best < 0 || bestRange == 0) break
            val box = boxes.removeAt(best)
            val sorted = box.sortedBy { (it shr bestShift) and 0xFF }
            boxes += sorted.subList(0, sorted.size / 2).toIntArray()
            boxes += sorted.subList(sorted.size / 2, sorted.size).toIntArray()
        }
        colors = IntArray(256) { i ->
            val box = boxes.getOrNull(i) ?: return@IntArray 0
            var r = 0L; var g = 0L; var b = 0L
            for (c in box) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            val n = box.size.coerceAtLeast(1)
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        val used = boxes.size.coerceIn(1, 256)
        for (ri in 0 until 32) for (gi in 0 until 32) for (bi in 0 until 32) {
            val r = ri * 8 + 4; val g = gi * 8 + 4; val b = bi * 8 + 4
            var best = 0
            var bestD = Int.MAX_VALUE
            for (k in 0 until used) {
                val c = colors[k]
                val dr = ((c shr 16) and 0xFF) - r
                val dg = ((c shr 8) and 0xFF) - g
                val db = (c and 0xFF) - b
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestD) { bestD = d; best = k }
            }
            cube[(ri shl 10) or (gi shl 5) or bi] = best.toByte()
        }
    }

    fun index(argb: Int): Int =
        cube[(((argb shr 19) and 31) shl 10) or (((argb shr 11) and 31) shl 5) or ((argb shr 3) and 31)].toInt() and 0xFF
}

/** A minimal GIF89a writer: one global 256-colour palette, LZW frames, looping. */
private class GifWriter(private val out: OutputStream, private val w: Int, private val h: Int, palette: IntArray) {
    init {
        out.write("GIF89a".toByteArray())
        short(w); short(h)
        out.write(0xF7)  // global colour table, 8 bits, 256 entries
        out.write(0); out.write(0)
        for (c in palette) { out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
        // Loop forever (NETSCAPE2.0).
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    fun frame(indices: ByteArray, delayCs: Int) {
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        short(delayCs)
        out.write(0); out.write(0)
        out.write(0x2C)
        short(0); short(0); short(w); short(h)
        out.write(0)
        lzw(indices)
    }

    fun finish() { out.write(0x3B); out.flush() }

    // LZW as in Kevin Weiner's GIF encoder (the decoder's code-size steps are matched exactly).
    private fun lzw(data: ByteArray) {
        val initBits = 9
        out.write(initBits - 1)
        val block = ByteArray(255)
        var blockLen = 0
        var bitBuf = 0
        var bitCount = 0
        fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }
        val maxMax = 4096
        var nBits = initBits
        var maxCode = (1 shl nBits) - 1
        val clearCode = 1 shl (initBits - 1)
        val eofCode = clearCode + 1
        var freeEnt = clearCode + 2
        var clearFlag = false
        val table = HashMap<Int, Int>(8192)
        fun output(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += nBits
            while (bitCount >= 8) {
                block[blockLen++] = (bitBuf and 0xFF).toByte()
                if (blockLen == 255) flushBlock()
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = initBits
                    maxCode = (1 shl nBits) - 1
                    clearFlag = false
                } else {
                    nBits++
                    maxCode = if (nBits == 12) maxMax else (1 shl nBits) - 1
                }
            }
        }
        output(clearCode)
        var ent = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 0xFF
            val key = (c shl 12) + ent
            val found = table[key]
            if (found != null) {
                ent = found
                continue
            }
            output(ent)
            ent = c
            if (freeEnt < maxMax) {
                table[key] = freeEnt++
            } else {
                table.clear()
                freeEnt = clearCode + 2
                clearFlag = true
                output(clearCode)
            }
        }
        output(ent)
        output(eofCode)
        if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}
