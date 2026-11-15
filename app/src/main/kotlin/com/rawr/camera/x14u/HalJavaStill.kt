package com.rawr.camera.x14u

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * X14U HAL 50 MP probe through the Java Camera2 API, laid out exactly like GCam's live 50 MP sessions (dumpsys
 * 15 Nov): PRIVATE 1440x1080 preview + 640x480 YUV + the 8192x6144 JPEG (aux lenses) or YUV (main, physical 2 of
 * logical 0), NORMAL operation mode, clientName com.android.camera, enableMFNR 0, ZSL off on the still.
 *
 * Why Java: the same layout through the NDK (HalStillSession) came back 1920x1440 even for a YUV still, so the
 * camera service itself sized our 8192x6144 stream down; GCam (Java API) keeps 8192x6144. Xiaomi's framework
 * changes live in the Java camera classes, which NDK clients never pass through.
 *
 * The caller has closed the viewfinder camera; this opens the camera itself and closes it before returning.
 * Logs HALJ_* lines (tag RawrCamCapture).
 */
object HalJavaStill {
    private const val TAG = "RawrCamCapture"
    private val CLIENT = CaptureRequest.Key("com.xiaomi.sessionparams.clientName", String::class.java)
    private val MFNR = CaptureRequest.Key("org.codeaurora.qcamera3.sessionParameters.enableMFNR", Int::class.javaObjectType)
    private val MODE = CaptureResult.Key("org.codeaurora.qcamera3.sensor_meta_data.current_mode", Int::class.javaObjectType)

    private fun log(message: String) = Log.i(TAG, "HALJ_$message")

    /** True when the JPEG was written to [path]. */
    @SuppressLint("MissingPermission")
    fun shoot(context: Context, cameraId: String, physicalId: String, yuvStill: Boolean, path: String): Boolean {
        val started = System.nanoTime()
        fun ms() = "ms=%.0f".format((System.nanoTime() - started) / 1e6)
        val thread = HandlerThread("RawrHalJava").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val manager = context.getSystemService(CameraManager::class.java)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val readers = mutableListOf<ImageReader>()
        var written = false
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // Open, retrying while the viewfinder's device is still closing.
            val deadline = System.nanoTime() + 4_000_000_000L
            while (device == null && System.nanoTime() < deadline) {
                val latch = CountDownLatch(1)
                val opened = AtomicReference<CameraDevice?>()
                try {
                    manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { opened.set(d); latch.countDown() }
                        override fun onDisconnected(d: CameraDevice) { d.close(); latch.countDown() }
                        override fun onError(d: CameraDevice, error: Int) { d.close(); latch.countDown() }
                    })
                    latch.await(1500, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    log("OPEN_RETRY ${e.javaClass.simpleName}")
                }
                device = opened.get()
                if (device == null) Thread.sleep(100)
            }
            val cam = device ?: run { log("FAILED reason=open ${ms()}"); return false }
            log("OPENED camera=$cameraId physical=${physicalId.ifEmpty { "none" }} ${ms()}")

            val preview = ImageReader.newInstance(1440, 1080, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            val analysis = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)
            val still = ImageReader.newInstance(8192, 6144, if (yuvStill) ImageFormat.YUV_420_888 else ImageFormat.JPEG, 2)
            readers += listOf(preview, analysis, still)
            for (r in listOf(preview, analysis)) r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            val stillImage = AtomicReference<ByteArray?>()
            val stillSize = AtomicReference("none")
            val stillLatch = CountDownLatch(1)
            still.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    stillSize.set("${image.width}x${image.height}")
                    stillImage.compareAndSet(null, if (yuvStill) nv21(image) else jpegBytes(image))
                } finally {
                    image.close()
                }
                stillLatch.countDown()
            }, handler)

            val outputs = listOf(preview, analysis, still).map { r ->
                OutputConfiguration(r.surface).also { if (physicalId.isNotEmpty()) it.setPhysicalCameraId(physicalId) }
            }
            val params = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                runCatching { set(CLIENT, "com.android.camera") }
                runCatching { set(MFNR, 0) }
            }.build()
            val configured = CountDownLatch(1)
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; configured.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.countDown() }
                }).apply { sessionParameters = params }
            cam.createCaptureSession(config)
            configured.await(3, TimeUnit.SECONDS)
            val s = session ?: run { log("FAILED reason=session ${ms()}"); return false }
            log("SESSION preview=1440x1080 private+640x480yuv still=${if (yuvStill) "yuv" else "jpeg"} 8192x6144 ${ms()}")

            val frames = AtomicInteger()
            val afState = AtomicInteger(-1)
            val lastLine = AtomicReference("")
            fun describe(r: CaptureResult) = "exposureNs=${r.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                "iso=${r.get(CaptureResult.SENSOR_SENSITIVITY)} aperture=${r.get(CaptureResult.LENS_APERTURE)} " +
                "focus=${r.get(CaptureResult.LENS_FOCUS_DISTANCE)} afState=${r.get(CaptureResult.CONTROL_AF_STATE)} " +
                "sensorMode=${runCatching { r.get(MODE) }.getOrNull()}"
            val previewCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    frames.incrementAndGet()
                    afState.set(r.get(CaptureResult.CONTROL_AF_STATE) ?: -1)
                    lastLine.set(describe(r))
                }
            }
            fun CaptureRequest.Builder.common() = apply {
                runCatching { set(CLIENT, "com.android.camera") }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            val repeating = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).common().apply {
                addTarget(preview.surface)
                addTarget(analysis.surface)
            }
            s.setRepeatingRequest(repeating.build(), previewCallback, handler)
            val previewDeadline = System.nanoTime() + 3_000_000_000L
            while (frames.get() < 8 && System.nanoTime() < previewDeadline) Thread.sleep(20)
            log("PREVIEW frames=${frames.get()} ${lastLine.get()} ${ms()}")

            val trigger = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).common().apply {
                addTarget(preview.surface)
                addTarget(analysis.surface)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            s.capture(trigger.build(), previewCallback, handler)
            val afDeadline = System.nanoTime() + 2_000_000_000L
            while (afState.get() != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED &&
                afState.get() != CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED && System.nanoTime() < afDeadline) {
                Thread.sleep(20)
            }
            log("AF afState=${afState.get()} ${ms()}")

            val stillResult = AtomicReference("none")
            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).common().apply {
                addTarget(still.surface)
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
            }
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    stillResult.set(describe(r))
                }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    stillResult.set("failed reason=${f.reason}")
                }
            }, handler)
            stillLatch.await(10, TimeUnit.SECONDS)
            Thread.sleep(100)  // the result usually lands with the image
            log("RESULT ${stillResult.get()} image=${stillSize.get()} ${ms()}")
            var bytes = stillImage.get() ?: run { log("FAILED reason=no_image ${ms()}"); return false }
            if (yuvStill) {
                val (w, h) = stillSize.get().split("x").map { it.toInt() }
                val out = ByteArrayOutputStream(bytes.size / 4)
                YuvImage(bytes, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 97, out)
                bytes = out.toByteArray()
            }
            File(path).writeBytes(bytes)
            written = true
            log("SAVED path=$path bytes=${bytes.size} image=${stillSize.get()} ${ms()}")
        } catch (e: Exception) {
            log("FAILED reason=${e.javaClass.simpleName}:${e.message} ${ms()}")
        } finally {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            Thread.sleep(150)
            readers.forEach { runCatching { it.close() } }
            thread.quitSafely()
            log("DONE written=${if (written) 1 else 0} ${ms()}")
        }
        return written
    }

    private fun jpegBytes(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        // The BLOB buffer is sized for the largest JPEG: cut at the last EOI.
        var end = data.size
        for (i in data.size - 1 downTo 1) if (data[i - 1] == 0xFF.toByte() && data[i] == 0xD9.toByte()) { end = i + 1; break }
        return data.copyOf(end)
    }

    private fun nv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(out, row * w, w)
        }
        val u = image.planes[1]
        val v = image.planes[2]
        var o = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[o++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                out[o++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }
}
