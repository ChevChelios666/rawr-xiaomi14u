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
import android.hardware.camera2.params.ColorSpaceTransform
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
    const val APERTURE_TAG = "com.xiaomi.lens.aperture"
    private val APERTURE = CaptureRequest.Key(APERTURE_TAG, Float::class.javaObjectType)
    private val APERTURE_MODE = CaptureRequest.Key("com.xiaomi.lens.apertureMode", Int::class.javaObjectType)
    private val APP_MODULE = CaptureRequest.Key("xiaomi.app.module", Int::class.javaObjectType)
    private val MFNR = CaptureRequest.Key("org.codeaurora.qcamera3.sessionParameters.enableMFNR", Int::class.javaObjectType)
    private val MODE = CaptureResult.Key("org.codeaurora.qcamera3.sensor_meta_data.current_mode", Int::class.javaObjectType)

    private fun log(message: String) = Log.i(TAG, "HALJ_$message")

    /** True when the JPEG was written to [path]. */
    @SuppressLint("MissingPermission")
    fun shoot(
        context: Context, cameraId: String, physicalId: String, yuvStill: Boolean, path: String, stillUsage: Long = 0L,
        /** Plain keys on the still (camera-native RGB, no NR/sharpening/curve games: input for Rawr's develop). */
        plain: Boolean = false,
        /** YUV still written as a synthesized CFA DNG here instead of a JPEG at [path] (HalRawSynth). */
        dngPath: String? = null,
        deviceRotationDegrees: Int = 0,
        /** Instead of a DNG: the mosaic for Rawr's own develop (HalRawSynth.BufferOut), and the shot's values. */
        mosaic: HalRawSynth.BufferOut? = null,
        shotInfo: AtomicReference<HalRawSynth.ShotInfo?>? = null,
        /** Rawr's own viewfinder exposure for the still (AE off), when both are > 0; else the camera's AE. */
        exposureNs: Long = 0L,
        sensitivity: Int = 0,
        /** The 1x's variable aperture (f-number) on every request (Xiaomi's keys, as the lens's Aperture choices). */
        aperture: Float? = null
    ): Boolean {
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
            log("OPENED camera=$cameraId physical=${physicalId.ifEmpty { "none" }} aperture=${aperture ?: "auto"} ${ms()}")

            val preview = ImageReader.newInstance(1440, 1080, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            val analysis = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)
            val stillFormat = if (yuvStill) ImageFormat.YUV_420_888 else ImageFormat.JPEG
            // Usage 0x100000 keeps Xiaomi's camera service from sizing the stream down (GCam's readers carry it).
            val still = if (stillUsage != 0L) {
                runCatching { ImageReader.newInstance(8192, 6144, stillFormat, 2, stillUsage) }
                    .onFailure { log("READER_USAGE_REFUSED usage=0x${stillUsage.toString(16)} ${it.message}") }
                    .getOrNull() ?: ImageReader.newInstance(8192, 6144, stillFormat, 2)
            } else ImageReader.newInstance(8192, 6144, stillFormat, 2)
            readers += listOf(preview, analysis, still)
            for (r in listOf(preview, analysis)) r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            val stillImage = AtomicReference<ByteArray?>()
            val stillSize = AtomicReference("none")
            var stillLatch = CountDownLatch(1)
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
            log("SESSION preview=1440x1080 private+640x480yuv still=${if (yuvStill) "yuv" else "jpeg"} 8192x6144 " +
                "usage=0x${still.usage.toString(16)} ${ms()}")

            val frames = AtomicInteger()
            val previewGains = AtomicReference<FloatArray?>()
            val afState = AtomicInteger(-1)
            val lastLine = AtomicReference("")
            val lastPreview = AtomicReference<TotalCaptureResult?>()
            fun describe(r: CaptureResult) = "exposureNs=${r.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                "iso=${r.get(CaptureResult.SENSOR_SENSITIVITY)} aperture=${r.get(CaptureResult.LENS_APERTURE)} " +
                "focus=${r.get(CaptureResult.LENS_FOCUS_DISTANCE)} afState=${r.get(CaptureResult.CONTROL_AF_STATE)} " +
                "sensorMode=${runCatching { r.get(MODE) }.getOrNull()}"
            val previewCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    frames.incrementAndGet()
                    afState.set(r.get(CaptureResult.CONTROL_AF_STATE) ?: -1)
                    HalRawSynth.wbGains(r)?.let { previewGains.set(it) }
                    lastLine.set(describe(r))
                    lastPreview.set(r)
                }
            }
            fun CaptureRequest.Builder.common() = apply {
                runCatching { set(CLIENT, "com.android.camera") }
                if (aperture != null) {
                    runCatching { set(APERTURE, aperture) }
                    runCatching { set(APERTURE_MODE, 1) }
                    runCatching { set(APP_MODULE, 167) }
                }
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
            val stillCapture = AtomicReference<TotalCaptureResult?>()
            val times = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isos = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val manual = exposureNs > 0L && sensitivity > 0
            val wantNs = times?.let { exposureNs.coerceIn(it.lower, it.upper) } ?: exposureNs
            val wantIso = isos?.let { sensitivity.coerceIn(it.lower, it.upper) } ?: sensitivity
            // controlOff: all of 3A off (lens at the AF-locked distance, the viewfinder's aperture) for a camera that
            // runs its own ISO under AE OFF (logical camera 0: asked 317, got 1845 at f/1.63, 23:04 Nov 15).
            fun stillRequest(controlOff: Boolean) = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).common().apply {
                addTarget(still.surface)
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (manual) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, wantNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, wantIso)
                    if (controlOff) {
                        val p = lastPreview.get()
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        p?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                        p?.get(CaptureResult.LENS_APERTURE)?.let { set(CaptureRequest.LENS_APERTURE, it) }
                        p?.get(CaptureResult.SENSOR_FRAME_DURATION)?.let {
                            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(it, wantNs))
                        }
                    }
                    log("MANUAL exposureNs=$wantNs iso=$wantIso controlOff=$controlOff (rawr $exposureNs / $sensitivity)")
                }
                set(CaptureRequest.JPEG_QUALITY, (if (dngPath != null || mosaic != null) 100 else 98).toByte())
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                if (plain) {
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
                    set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                    // An sRGB-shaped contrast curve for shadow precision in 8 bits; what the camera really applied is
                    // read back from the result (it ignored PRESET_CURVE sRGB: 18:27 Nov 15).
                    set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                    val pts = FloatArray(66) { j ->
                        val x = (j / 2) / 32f
                        if (j % 2 == 0) x else (if (x <= 0.0031308f) x * 12.92f else (1.055 * Math.pow(x.toDouble(), 1 / 2.4) - 0.055).toFloat())
                    }
                    set(CaptureRequest.TONEMAP_CURVE, android.hardware.camera2.params.TonemapCurve(pts, pts, pts))
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(1f, 1f, 1f, 1f))
                    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)))
                }
            }
            fun fire(controlOff: Boolean) = s.capture(stillRequest(controlOff).build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    stillResult.set(describe(r))
                    stillCapture.set(r)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    stillResult.set("failed reason=${f.reason}")
                }
            }, handler)
            fire(false)
            stillLatch.await(10, TimeUnit.SECONDS)
            Thread.sleep(100)  // the result usually lands with the image
            log("RESULT ${stillResult.get()} image=${stillSize.get()} ${ms()}")
            // The camera ran its own ISO / shutter instead of the asked pair: once more with all of 3A off.
            val got = stillCapture.get()
            val gotIso = got?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val gotNs = got?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            fun off(a: Double, b: Double) = a <= 0.0 || b <= 0.0 || a / b > 1.25 || b / a > 1.25
            if (manual && stillImage.get() != null && (off(gotIso.toDouble(), wantIso.toDouble()) ||
                    off(gotNs.toDouble(), wantNs.toDouble()))) {
                log("MANUAL_IGNORED gotIso=$gotIso gotNs=$gotNs ${ms()}")
                val first = stillImage.getAndSet(null)
                val firstResult = stillCapture.getAndSet(null)
                stillLatch = CountDownLatch(1)
                fire(true)
                stillLatch.await(10, TimeUnit.SECONDS)
                Thread.sleep(100)
                log("RESULT_CONTROL_OFF ${stillResult.get()} image=${stillSize.get()} ${ms()}")
                if (stillImage.get() == null) {
                    stillImage.set(first)
                    stillCapture.set(firstResult)
                }
            }
            var bytes = stillImage.get() ?: run { log("FAILED reason=no_image ${ms()}"); return false }
            if (dngPath != null || mosaic != null) {
                val r = stillCapture.get()
                val shot = HalRawSynth.ShotInfo(
                    exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    iso = r?.get(CaptureResult.SENSOR_SENSITIVITY),
                    aperture = r?.get(CaptureResult.LENS_APERTURE),
                    focalLength = r?.get(CaptureResult.LENS_FOCAL_LENGTH),
                    wbGains = previewGains.get(),
                    deviceRotationDegrees = deviceRotationDegrees,
                    curve = r?.takeIf { it.get(CaptureResult.TONEMAP_MODE) == CaptureResult.TONEMAP_MODE_CONTRAST_CURVE }
                        ?.get(CaptureResult.TONEMAP_CURVE)?.let { c ->
                            val n = c.getPointCount(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN)
                            FloatArray(n * 2).also { c.copyColorCurve(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN, it, 0) }
                        }
                )
                log("PLAIN tonemapMode=${r?.get(CaptureResult.TONEMAP_MODE)} curvePoints=${shot.curve?.size?.div(2)} " +
                    "nr=${r?.get(CaptureResult.NOISE_REDUCTION_MODE)} edge=${r?.get(CaptureResult.EDGE_MODE)} " +
                    "awb=${r?.get(CaptureResult.CONTROL_AWB_MODE)} cc=${r?.get(CaptureResult.COLOR_CORRECTION_MODE)} " +
                    "gains=${r?.get(CaptureResult.COLOR_CORRECTION_GAINS)}")
                val synthStart = System.nanoTime()
                val sink: HalRawSynth.MosaicOut = mosaic ?: HalRawSynth.DngOut(File(dngPath!!), chars, shot)
                val (w, h) = if (yuvStill) {
                    val (sw, sh) = stillSize.get().split("x").map { it.toInt() }
                    HalRawSynth.writeDng(sink, bytes, sw, sh, shot)
                    sw to sh
                } else HalRawSynth.writeDngFromJpeg(sink, bytes, shot)
                shotInfo?.set(shot)
                written = true
                log("DNG path=${dngPath ?: "rawr-develop"} image=${w}x$h wbGains=${shot.wbGains?.joinToString(",")} " +
                    "synthMs=${(System.nanoTime() - synthStart) / 1_000_000} ${ms()}")
                return true
            }
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
