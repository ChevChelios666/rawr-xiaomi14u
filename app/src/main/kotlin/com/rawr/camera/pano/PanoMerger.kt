package com.rawr.camera.pano

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OpenCV stitcher (librawrcam_pano), loaded the first time a set is merged. */
internal object PanoStitcherNative {
    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("rawrcam_pano") }.isSuccess }
    val available: Boolean get() = loaded

    /** Frames as file descriptors (closed natively) with their EXIF rotations; "" on success. */
    external fun stitch(
        fds: IntArray,
        rotations: IntArray,
        composeMegapixels: Float,
        outFd: Int,
        quality: Int,
        crop: Boolean
    ): String
}

/**
 * X14U Pano merge on the phone: once a set ends, waits for its JPEG frames to be saved ("…_PANO2231_01.jpg" …),
 * stitches them with OpenCV and saves "…_PANO2231.jpg" next to them with the first frame's main EXIF. The frames stay.
 */
internal class PanoMerger(private val context: Context) {
    private val lock = Mutex()

    private data class Frame(val uri: Uri, val name: String, val relativePath: String, val taken: Long)

    /** [status] gets short messages for the viewfinder (null clears it). */
    suspend fun merge(tag: String, frames: Int, startedAtMillis: Long, status: (String?) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!PanoStitcherNative.available) {
                status("Pano merge isn't available")
                return@withContext
            }
            status("Pano: waiting for $frames frames…")
            val found = waitForFrames(tag, frames, startedAtMillis)
            if (found.size < 2) {
                status(if (found.isEmpty()) "Pano merge needs JPEG frames" else "Pano merge: only ${found.size} frame saved")
                return@withContext
            }
            status("Merging pano (${found.size} frames)…")
            val started = System.currentTimeMillis()
            val error = runCatching { stitch(found) }.getOrElse { it.message ?: "stitch failed" }
            if (error.isEmpty()) {
                status("Pano saved (${(System.currentTimeMillis() - started) / 1000} s)")
            } else {
                status("Pano merge failed: $error")
            }
        }
    }

    private suspend fun waitForFrames(tag: String, frames: Int, startedAtMillis: Long): List<Frame> {
        var last = emptyList<Frame>()
        var stableSince = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            val now = query(tag, startedAtMillis)
            if (now.size >= frames) return now
            if (now.size != last.size) stableSince = System.currentTimeMillis()
            last = now
            // Nothing new for a while (DNG-only, or a frame that never saved): go with what there is.
            if (System.currentTimeMillis() - stableSince > 45_000L) break
            delay(1000L)
        }
        return last
    }

    private fun query(tag: String, startedAtMillis: Long): List<Frame> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} = ? " +
            "AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf("%_PANO${tag}_%", "image/jpeg", ((startedAtMillis / 1000L) - 5L).toString())
        val out = mutableListOf<Frame>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                // Frames only ("…_PANO2231_03.jpg"), not an earlier merge of the same set.
                if (!Regex("_PANO${tag}_\\d+\\.jpe?g$", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
                out += Frame(ContentUris.withAppendedId(collection, c.getLong(0)), name, c.getString(2) ?: "DCIM/Camera/",
                    c.getLong(3))
            }
        }
        return out.sortedBy { frameNumber(it.name) }
    }

    private fun frameNumber(name: String): Int =
        Regex("_(\\d+)\\.jpe?g$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** "" on success. */
    private fun stitch(frames: List<Frame>): String {
        val resolver = context.contentResolver
        val rotations = frames.map { frame ->
            resolver.openFileDescriptor(frame.uri, "r")?.use { pfd ->
                when (runCatching { ExifInterface(pfd.fileDescriptor) }.getOrNull()
                    ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.toIntArray()
        val first = frames.first()
        val outName = first.name.replace(Regex("_\\d+(\\.jpe?g)$", RegexOption.IGNORE_CASE), "$1")
        val outUri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, first.relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: return "couldn't create the file"
        val fds = IntArray(frames.size) { -1 }
        try {
            frames.forEachIndexed { i, frame ->
                fds[i] = resolver.openFileDescriptor(frame.uri, "r")?.detachFd() ?: -1
            }
            val readable = fds.indices.filter { fds[it] >= 0 }
            val outFd = resolver.openFileDescriptor(outUri, "rwt")?.detachFd()
                ?: return "couldn't write the file".also { resolver.delete(outUri, null, null) }
            // Composite size per frame: full size for small sets, less for big ones (memory).
            val composeMp = (90f / readable.size).coerceIn(3f, 50f)
            val error = PanoStitcherNative.stitch(
                readable.map { fds[it] }.toIntArray(),
                readable.map { rotations[it] }.toIntArray(),
                composeMp,
                outFd,
                quality = 92,
                crop = true
            )
            readable.forEach { fds[it] = -1 } // closed natively
            if (error.isNotEmpty()) {
                resolver.delete(outUri, null, null)
                return error
            }
            copyExif(first.uri, outUri, frames.size)
            resolver.update(outUri, ContentValues().apply {
                if (first.taken > 0L) put(MediaStore.Images.Media.DATE_TAKEN, first.taken)
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return ""
        } catch (t: Throwable) {
            runCatching { resolver.delete(outUri, null, null) }
            throw t
        } finally {
            fds.filter { it >= 0 }.forEach { runCatching { android.os.ParcelFileDescriptor.adoptFd(it).close() } }
        }
    }

    private fun copyExif(from: Uri, to: Uri, frames: Int) {
        val resolver = context.contentResolver
        runCatching {
            val source = resolver.openFileDescriptor(from, "r")?.use { ExifInterface(it.fileDescriptor) } ?: return
            resolver.openFileDescriptor(to, "rw")?.use { pfd ->
                val target = ExifInterface(pfd.fileDescriptor)
                listOf(
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_SOFTWARE
                ).forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
                target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                target.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Panorama merged on the phone from $frames frames")
                target.saveAttributes()
            }
        }
    }
}
