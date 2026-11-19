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

    /** Frames as file descriptors (closed natively) with their EXIF rotations; "OK|<how>" on success, else why not. */
    external fun stitch(
        fds: IntArray,
        rotations: IntArray,
        composeMegapixels: Float,
        outFd: Int,
        quality: Int,
        crop: Boolean,
        projection: Int,
        spanDegrees: Float,
        vertical: Boolean
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
    suspend fun merge(
        tag: String,
        frames: Int,
        startedAtMillis: Long,
        spanDegrees: Float,
        projection: com.rawr.camera.settings.model.PanoProjection,
        keepFrames: Boolean,
        /** The sweep went up or down (as the frames are saved): levelled and projected for that. */
        vertical: Boolean,
        status: (String?) -> Unit
    ) {
        // Sets merge one at a time; a set taken while another merges says so (Nov 19: the last set showed
        // nothing while it waited).
        if (lock.isLocked) status("Pano queued: another one is merging…")
        val saved = lock.withLock {
            withContext(Dispatchers.IO) {
                if (!PanoStitcherNative.available) {
                    status("Pano merge isn't available")
                    return@withContext false
                }
                status("Pano: waiting for $frames frames…")
                val found = waitForFrames(tag, frames, startedAtMillis)
                if (found.size < 2) {
                    status(if (found.isEmpty()) "Pano merge needs JPEG frames" else "Pano merge: only ${found.size} frame saved")
                    return@withContext false
                }
                status("Merging pano (${found.size} frames)…")
                val started = System.currentTimeMillis()
                val error = runCatching { stitch(found, projection.ordinal, spanDegrees, vertical) }
                    .getOrElse { it.message ?: "stitch failed" }
                if (error.isEmpty()) {
                    status("Pano saved (${(System.currentTimeMillis() - started) / 1000} s)")
                    true
                } else {
                    status("Pano merge failed: $error")
                    false
                }
            }
        }
        // Deleting the frames looks again over about a minute (late DNGs): outside the lock, so the next set
        // doesn't wait for it (it held the queue for ~85 s).
        if (saved && !keepFrames) withContext(Dispatchers.IO) { deleteFrames(tag, startedAtMillis) }
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
                // Frames only ("…_PANO2231_03.jpg", or "…_PANO2231_03_MF6.jpg" when Rexture merged the frame), not
                // an earlier merge of the same set. (Nov 16: the _MF6 frames didn't match, so the set never merged.)
                if (!Regex("_PANO${tag}_\\d+(_MF\\d+)?\\.jpe?g$", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
                out += Frame(ContentUris.withAppendedId(collection, c.getLong(0)), name, c.getString(2) ?: "DCIM/Camera/",
                    c.getLong(3))
            }
        }
        return out.sortedBy { frameNumber(it.name) }
    }

    private fun frameNumber(name: String): Int =
        Regex("_PANO\\d*_(\\d+)(_MF\\d+)?\\.jpe?g$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * The set's frames (JPEG and DNG, "…_PANO2231_03[_MF6].jpg/.dng") this app saved, never the panorama itself
     * ("…_PANO2231.jpg"): each row's name is checked before it goes. DNGs can land late, so it looks a few times.
     */
    private suspend fun deleteFrames(tag: String, startedAtMillis: Long) {
        val frameName = Regex("_PANO${tag}_\\d+(_MF\\d+)?\\.(jpe?g|dng)$", RegexOption.IGNORE_CASE)
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        for (wait in longArrayOf(0L, 5_000L, 20_000L, 60_000L)) {
            delay(wait)
            runCatching {
                val ids = mutableListOf<Long>()
                context.contentResolver.query(
                    files,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? " +
                        "AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                    arrayOf("%_PANO${tag}_%", context.packageName, ((startedAtMillis / 1000L) - 5L).toString()),
                    null
                )?.use { c ->
                    while (c.moveToNext()) if (frameName.containsMatchIn(c.getString(1) ?: "")) ids += c.getLong(0)
                }
                ids.forEach { id -> runCatching { context.contentResolver.delete(ContentUris.withAppendedId(files, id), null, null) } }
            }
        }
    }

    /** "" on success. */
    private fun stitch(frames: List<Frame>, projection: Int, spanDegrees: Float, vertical: Boolean): String {
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
        // "…_PANO2231_01.jpg" or "…_PANO2231_01_MF6.jpg" → "…_PANO2231.jpg". (Nov 17: the _MF6 name was kept whole,
        // so the panorama looked like a frame and "delete frames after merge" deleted it too.)
        val outName = first.name.replace(Regex("_\\d+(_MF\\d+)?(\\.jpe?g)$", RegexOption.IGNORE_CASE), "$2")
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
            val result = PanoStitcherNative.stitch(
                readable.map { fds[it] }.toIntArray(),
                readable.map { rotations[it] }.toIntArray(),
                composeMp,
                outFd,
                quality = 92,
                crop = true,
                projection = projection,
                spanDegrees = spanDegrees,
                vertical = vertical
            )
            readable.forEach { fds[it] = -1 } // closed natively
            if (!result.startsWith("OK|")) {
                resolver.delete(outUri, null, null)
                return result.ifEmpty { "stitch failed" }
            }
            // How the frames were taken: Rexture-merged ("_MF6") or single frames.
            val merged = Regex("_MF(\\d+)\\.jpe?g$", RegexOption.IGNORE_CASE).find(first.name)?.groupValues?.get(1)
            val taken = if (merged != null) "frames merged by Rexture ($merged each)" else "single-frame frames"
            copyExif(first.uri, outUri, "Panorama merged on the phone: ${result.removePrefix("OK|")}, $taken")
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

    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS: the framework ExifInterface has no newer name for 0x8827.
    private fun copyExif(from: Uri, to: Uri, description: String) {
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
                    ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_SOFTWARE
                ).forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
                target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                // Projection (and whether Auto chose it), frames stitched of those taken, matching, crop, frame type.
                target.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                target.setAttribute(ExifInterface.TAG_USER_COMMENT, description)
                target.saveAttributes()
            }
        }
    }
}
