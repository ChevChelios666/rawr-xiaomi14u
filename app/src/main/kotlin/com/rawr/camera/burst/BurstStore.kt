package com.rawr.camera.burst

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/** One frame of a burst: its file number (time order; pre-capture frames first) and its files. */
data class BurstFrame(val number: Int, val jpeg: File?, val dng: File?) {
    /** The file to look at (JPEG when there is one). */
    val view: File? get() = jpeg?.takeIf { it.length() > 0 } ?: dng?.takeIf { it.length() > 0 }
}

/** A burst in Rawr's private storage (files/burst_frames/<folder>/) with what its manifest says. */
data class BurstInfo(
    val folder: String,
    val startedAt: Long,
    val fps: Int,
    val preFrames: Int,
    /** File number of the frame at the press. */
    val pressNumber: Int,
    /** File number of the cover (the press frame unless changed). */
    val cover: Int,
    val format: String,
    /** Days the DNGs stay (0 = kept). */
    val rawKeepDays: Int,
    val frames: List<BurstFrame>
) {
    val coverFrame: BurstFrame? get() = frames.firstOrNull { it.number == cover } ?: frames.firstOrNull()
    val lengthSeconds: Float get() = if (fps > 0) frames.size / fps.toFloat() else 0f
    /** Whole days left before the DNGs go (null = kept, or none). */
    fun rawDaysLeft(now: Long = System.currentTimeMillis()): Int? {
        if (rawKeepDays <= 0 || frames.none { it.dng != null }) return null
        val left = startedAt + rawKeepDays * 86_400_000L - now
        return ((left + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
    }
}

/**
 * X14U bursts: frames stay in Rawr's own storage, out of other galleries; the burst viewer reviews them and
 * exports the chosen ones to DCIM/Rawr Bursts/<folder>/.
 */
object BurstStore {
    fun framesRoot(context: Context) = File(context.filesDir, "burst_frames")
    private fun manifestFile(context: Context, folder: String) = File(File(context.filesDir, "bursts"), "$folder.json")

    /** Newest first. */
    fun list(context: Context): List<BurstInfo> =
        framesRoot(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?.mapNotNull { load(context, it.name) }?.filter { it.frames.isNotEmpty() }.orEmpty()

    fun latest(context: Context): BurstInfo? = list(context).firstOrNull()

    fun load(context: Context, folder: String): BurstInfo? {
        val dir = File(framesRoot(context), folder)
        if (!dir.isDirectory) return null
        val numbered = dir.listFiles().orEmpty().mapNotNull { file ->
            val number = file.nameWithoutExtension.substringAfterLast('_').toIntOrNull() ?: return@mapNotNull null
            number to file
        }.groupBy({ it.first }, { it.second })
        val frames = numbered.keys.sorted().map { n ->
            val files = numbered.getValue(n)
            BurstFrame(n, files.firstOrNull { it.extension.equals("jpg", true) },
                files.firstOrNull { it.extension.equals("dng", true) })
        }
        val json = runCatching { JSONObject(manifestFile(context, folder).readText()) }.getOrNull()
        val press = json?.optInt("pressNumber", 1) ?: 1
        return BurstInfo(
            folder = folder,
            startedAt = json?.optLong("startedAt") ?: dir.lastModified(),
            fps = json?.optInt("fps", 20) ?: 20,
            preFrames = json?.optInt("preFrames", 0) ?: 0,
            pressNumber = press,
            cover = json?.optInt("cover", press) ?: press,
            format = json?.optString("format", "Jpeg") ?: "Jpeg",
            rawKeepDays = json?.optInt("rawKeepDays", 0) ?: 0,
            frames = frames
        )
    }

    fun setCover(context: Context, folder: String, number: Int) {
        val file = manifestFile(context, folder)
        val json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        file.parentFile?.mkdirs()
        file.writeText(json.put("cover", number).toString(2))
    }

    fun deleteFrames(context: Context, folder: String, numbers: Set<Int>) {
        val info = load(context, folder) ?: return
        info.frames.filter { it.number in numbers }.forEach { frame -> frame.jpeg?.delete(); frame.dng?.delete() }
        if (load(context, folder)?.frames.isNullOrEmpty()) deleteBurst(context, folder)
    }

    fun deleteBurst(context: Context, folder: String) {
        File(framesRoot(context), folder).deleteRecursively()
        manifestFile(context, folder).delete()
    }

    /**
     * Copies the frames to DCIM/Rawr Bursts/<folder>/ (JPEG, or the DNG of a RAW-only frame) so every gallery sees
     * them. Returns how many were exported.
     */
    fun export(context: Context, folder: String, numbers: Set<Int>, withDng: Boolean = false): Int {
        val info = load(context, folder) ?: return 0
        val resolver = context.contentResolver
        var done = 0
        info.frames.filter { it.number in numbers }.forEach { frame ->
            val files = buildList {
                frame.jpeg?.takeIf { it.length() > 0 }?.let { add(it to "image/jpeg") }
                if (withDng || frame.jpeg == null) frame.dng?.takeIf { it.length() > 0 }?.let { add(it to "image/x-adobe-dng") }
            }
            files.forEach { (file, mime) ->
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "RAWR_${folder}_%03d.${file.extension}".format(frame.number))
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Rawr Bursts/$folder/")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    done++
                }
            }
        }
        return done
    }

    /** JPEG bursts' DNGs go after their keep time; Build A's raw job copies (files/burst_raw) go too. */
    fun cleanup(context: Context) {
        File(context.filesDir, "burst_raw").deleteRecursively()
        val now = System.currentTimeMillis()
        list(context).forEach { info ->
            if (info.rawKeepDays > 0 && now > info.startedAt + info.rawKeepDays * 86_400_000L) {
                info.frames.forEach { frame -> if (frame.jpeg != null) frame.dng?.delete() }
            }
        }
    }

    /** A sampled bitmap for [file] (JPEG via BitmapFactory, DNG via ImageDecoder), longest side about [maxSide]. */
    fun decode(file: File, maxSide: Int): Bitmap? = runCatching {
        if (file.length() == 0L) return@runCatching null
        // ImageDecoder applies the EXIF orientation (BitmapFactory didn't: frames showed turned).
        if (file.extension.equals("jpg", true)) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                var sample = 1
                while (maxOf(info.size.width, info.size.height) / (sample * 2) >= maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = maxSide / maxOf(info.size.width, info.size.height).toFloat()
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }.getOrNull()
}
