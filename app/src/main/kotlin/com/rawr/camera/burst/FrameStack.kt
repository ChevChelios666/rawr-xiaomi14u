package com.rawr.camera.burst

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** OpenCV frame stacking (StackJni.cpp in librawrcam_pano), loaded the first time a run is stacked. */
internal object FrameStackNative {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("rawrcam_pano") }.isSuccess }
    val available: Boolean get() = loaded

    interface Progress {
        /** False stops the stack. */
        fun onProgress(done: Int, total: Int): Boolean
    }

    /** "OK|<frames used> of <frames>[, <n> aligned]" on success, else why not. */
    external fun stack(paths: Array<String>, mode: Int, align: Boolean, outPath: String, quality: Int, progress: Progress?): String
}

/**
 * X14U Stack (burst viewer): a burst's or timed run's JPEGs into one photo, saved with the run's exports
 * (DCIM/Rawr Timed or DCIM/Rawr Bursts) with the first frame's EXIF and how it was made.
 */
object FrameStack {
    enum class Mode(val id: Int, val label: String, val note: String, val suffix: String) {
        Average(0, "Average", "long exposure", "AVERAGE"),
        Lighten(1, "Lighten", "star / light trails", "LIGHTEN"),
        Clean(2, "Clean", "removes movers", "CLEAN")
    }

    data class Result(val uri: Uri?, val message: String)

    /** Runs on the caller's (background) thread; [progress] gets (done, total) and returns false to stop. */
    fun stack(
        context: Context,
        info: BurstInfo,
        frames: List<BurstFrame>,
        mode: Mode,
        align: Boolean,
        progress: (Int, Int) -> Boolean
    ): Result {
        if (!FrameStackNative.available) return Result(null, "Stacking isn't available on this build")
        val files = frames.mapNotNull { it.jpeg?.takeIf { f -> f.length() > 0 } }
        if (files.size < 2) return Result(null, "Needs at least 2 JPEG frames")
        val temp = File(context.cacheDir, "stack_${System.currentTimeMillis()}.jpg")
        try {
            val summary = FrameStackNative.stack(
                files.map { it.absolutePath }.toTypedArray(), mode.id, align, temp.absolutePath, 95,
                object : FrameStackNative.Progress {
                    override fun onProgress(done: Int, total: Int) = progress(done, total)
                }
            )
            if (!summary.startsWith("OK|")) return Result(null, summary)
            val how = "${mode.label} stack of ${summary.removePrefix("OK|")}"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "RAWR_${info.folder}_${mode.suffix}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, BurstStore.exportDir(info))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result(null, "Can't save the photo")
            resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
            copyExif(context, files.first(), uri, how)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return Result(uri, "Saved: $how")
        } finally {
            temp.delete()
        }
    }

    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS: the framework ExifInterface has no newer name for 0x8827.
    private fun copyExif(context: Context, from: File, to: Uri, description: String) {
        runCatching {
            val source = ExifInterface(from.path)
            context.contentResolver.openFileDescriptor(to, "rw")?.use { pfd ->
                val target = ExifInterface(pfd.fileDescriptor)
                listOf(
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_SOFTWARE
                ).forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
                // OpenCV wrote the pixels upright.
                target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                target.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                target.setAttribute(ExifInterface.TAG_USER_COMMENT, description)
                target.saveAttributes()
            }
        }
    }
}
