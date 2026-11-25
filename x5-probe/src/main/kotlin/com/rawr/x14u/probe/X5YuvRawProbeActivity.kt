package com.rawr.x14u.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolated X14U 5x probe. Not the production Rawr pipeline.
 * One configure attempt, two outputs in the observed 3x order:
 *   YUV_420_888 2560x1920 repeating, RAW_SENSOR 8192x6144 one-shot.
 * OP mode 36877 (0x900D), zero vendor keys / zero stream-use-case overrides.
 */
class X5YuvRawProbeActivity : Activity() {
    companion object {
        private const val TAG = "X14U5XProbe"
        private const val OP_MODE = 36877
        private const val YUV_WIDTH = 2560
        private const val YUV_HEIGHT = 1920
        private const val RAW_WIDTH = 8192
        private const val RAW_HEIGHT = 6144
        private const val CAMERA_PERMISSION_REQUEST = 51
    }

    private val cameraThread = HandlerThread("x5-probe-camera")
    private val ioThread = HandlerThread("x5-probe-image")
    private val saveThread = HandlerThread("x5-probe-raw-save")
    private lateinit var cameraHandler: Handler
    private lateinit var ioHandler: Handler
    private lateinit var saveHandler: Handler
    private lateinit var startButton: Button
    private lateinit var captureButton: Button
    private lateinit var statusView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var yuvReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private val launched = AtomicBoolean(false)
    private val shotSent = AtomicBoolean(false)
    private val rawReceived = AtomicBoolean(false)
    private val captureResult = AtomicReference<TotalCaptureResult?>(null)
    private val cameraId: String by lazy { if (intent.getStringExtra("probe_camera_id") == "4") "4" else "5" }
    private val filePrefix: String by lazy { "X14U_ID${cameraId}_YUVRAW_${System.currentTimeMillis()}" }
    @Volatile private var yuvFrameCount = 0
    private var characteristics: CameraCharacteristics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraThread.start()
        ioThread.start()
        saveThread.start()
        cameraHandler = Handler(cameraThread.looper)
        ioHandler = Handler(ioThread.looper)
        saveHandler = Handler(saveThread.looper)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (18 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        val header = TextView(this).apply {
            textSize = 22f
            text = "ID${cameraId} CONTROL V3 | 0x900D\nYUV 2560x1920 + RAW16 8192x6144"
        }
        layout.addView(header)
        startButton = Button(this).apply { text = "1. START ONCE" }
        captureButton = Button(this).apply { text = "2. CAPTURE ONE RAW"; isEnabled = false }
        statusView = TextView(this).apply { textSize = 16f; text = "Ready. No camera opened yet.\n" }
        layout.addView(startButton)
        layout.addView(captureButton)
        layout.addView(statusView)
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)

        startButton.setOnClickListener {
            if (launched.compareAndSet(false, true)) {
                startButton.isEnabled = false
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openCameraOnce()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
                }
            }
        }
        captureButton.setOnClickListener { captureOneRaw() }
        emit("NEW_PROBE pkg=$packageName cam=$cameraId op=$OP_MODE yuv=${YUV_WIDTH}x${YUV_HEIGHT} raw=${RAW_WIDTH}x${RAW_HEIGHT}")
    }

    private fun emit(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            if (::statusView.isInitialized) statusView.append("$message\n")
        }
    }

    private fun failure(stage: String, detail: String) {
        emit("FAIL stage=$stage reason=$detail")
        runOnUiThread { if (::captureButton.isInitialized) captureButton.isEnabled = false }
        saveText("${filePrefix}_FAIL_${stage}.txt", "stage=$stage\nreason=$detail\n")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCameraOnce()
            else failure("permission", "CAMERA permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraOnce() {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // cameraIdList is not an authorization test for an explicitly named camera:
            // log visibility, then let CameraManager/openCamera report the actual result.
            // Xiaomi may expose different lists to stock and third-party packages.
            val visibleIds = try {
                manager.cameraIdList.toList()
            } catch (error: Exception) {
                emit("CAMERA_LIST_ERROR type=${error.javaClass.simpleName} message=${error.message}")
                emptyList()
            }
            emit("CAMERA_LIST target=$cameraId listed=${cameraId in visibleIds} visible=${visibleIds.joinToString(",")}")
            characteristics = try {
                manager.getCameraCharacteristics(cameraId)
            } catch (error: Exception) {
                emit("CHARACTERISTICS_UNAVAILABLE id=$cameraId type=${error.javaClass.simpleName} message=${error.message}")
                null
            }
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvAvailable = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.any { it.width == YUV_WIDTH && it.height == YUV_HEIGHT } ?: false
            val rawAvailable = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.any { it.width == RAW_WIDTH && it.height == RAW_HEIGHT } ?: false
            emit("PUBLIC_MAP present=${map != null} yuv2560=$yuvAvailable raw50=$rawAvailable")
            emit("DIRECT_OPEN_ATTEMPT id=$cameraId listed=${cameraId in visibleIds}")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    emit("CAMERA_OPENED id=${device.id}")
                    createExactlyTwoOutputs(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    failure("open", "disconnected")
                    device.close()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    failure("open", "CameraDevice error=$error")
                    device.close()
                }
            }, cameraHandler)
        } catch (error: Exception) {
            failure("open_exception", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun createExactlyTwoOutputs(device: CameraDevice) {
        try {
            yuvReader = ImageReader.newInstance(YUV_WIDTH, YUV_HEIGHT, ImageFormat.YUV_420_888, 3)
            rawReader = ImageReader.newInstance(RAW_WIDTH, RAW_HEIGHT, ImageFormat.RAW_SENSOR, 2)
            yuvReader!!.setOnImageAvailableListener({ reader ->
                try {
                    reader.acquireLatestImage()?.use { yuvFrameCount++ }
                } catch (error: Exception) {
                    Log.w(TAG, "YUV_DRAIN_ERROR ${error.message}")
                }
            }, ioHandler)
            rawReader!!.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireNextImage() } catch (error: Exception) {
                    failure("raw_acquire", error.message ?: "unknown")
                    null
                }
                if (image != null) {
                    if (rawReceived.compareAndSet(false, true)) {
                        saveHandler.post { image.use { saveRawImage(it) } }
                    } else image.close()
                }
            }, ioHandler)
            val yuv = OutputConfiguration(yuvReader!!.surface)
            val raw = OutputConfiguration(rawReader!!.surface)
            // Both stream use cases remain their Camera2 default (=0). No vendor keys.
            val outputs = listOf(yuv, raw)
            val executor = Executor { cameraHandler.post(it) }
            val config = SessionConfiguration(OP_MODE, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (isFinishing || isDestroyed) {
                        configured.close()
                        return
                    }
                    session = configured
                    emit("SESSION_CONFIGURED cam=$cameraId op=0x900D outputs=2 yuv=$YUV_WIDTH,$YUV_HEIGHT raw=$RAW_WIDTH,$RAW_HEIGHT")
                    startYuvRepeating(device, configured)
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    failure("configure", "CameraService rejected YUV+RAW session")
                    configured.close()
                }
            })
            emit("CONFIGURE_REQUEST cam=$cameraId op=0x900D outputs=[YUV_420_888:${YUV_WIDTH}x${YUV_HEIGHT},RAW_SENSOR:${RAW_WIDTH}x${RAW_HEIGHT}] useCase=0,0 keys=none")
            device.createCaptureSession(config)
        } catch (error: Exception) {
            failure("configure_exception", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun startYuvRepeating(device: CameraDevice, configured: CameraCaptureSession) {
        try {
            val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(requireNotNull(yuvReader).surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            configured.setRepeatingRequest(preview, object : CameraCaptureSession.CaptureCallback() {}, cameraHandler)
            emit("PREVIEW_RUNNING target=YUV_ONLY; RAW not targeted; button ready")
            runOnUiThread { captureButton.isEnabled = true }
        } catch (error: Exception) {
            failure("preview", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun captureOneRaw() {
        if (!shotSent.compareAndSet(false, true)) return
        captureButton.isEnabled = false
        val device = camera
        val configured = session
        if (device == null || configured == null) {
            failure("capture", "No active camera session")
            return
        }
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(rawReader).surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                setTag("X14U_RAW_STILL")
            }.build()
            configured.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    emit("STILL_STARTED timestamp=$timestamp frameNumber=$frameNumber")
                }
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    captureResult.set(result)
                    emit("STILL_COMPLETED frameNumber=${result.frameNumber} sensorTimestamp=${result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)}")
                }
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failed: CaptureFailure) {
                    failure("capture_callback", "reason=${failed.reason} frame=${failed.frameNumber}")
                }
            }, cameraHandler)
            emit("STILL_SUBMITTED target=RAW_ONLY count=1")
        } catch (error: Exception) {
            failure("capture_exception", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun saveRawImage(image: Image) {
        val width = image.width
        val height = image.height
        val format = image.format
        val timestamp = image.timestamp
        val planeCount = image.planes.size
        emit("RAW_IMAGE_RECEIVED format=$format width=$width height=$height planes=$planeCount timestamp=$timestamp")
        if (format != ImageFormat.RAW_SENSOR || width != RAW_WIDTH || height != RAW_HEIGHT || planeCount != 1) {
            failure("raw_geometry", "unexpected dimensions/format/planes")
            return
        }
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val activeBytes = width * 2
        val buffer = plane.buffer.duplicate()
        if (pixelStride != 2 || rowStride < activeBytes ||
            (height - 1L) * rowStride + activeBytes > buffer.limit().toLong()) {
            failure("raw_stride", "rowStride=$rowStride pixelStride=$pixelStride limit=${buffer.limit()}")
            return
        }
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/X14U_X5_YUV_PROBE/"
        val name = "${filePrefix}_${width}x${height}.raw16le"
        val row = ByteArray(activeBytes)
        var firstRowsNonZero = 0
        var lastRowsNonZero = 0
        var checkedRows = 0
        try {
            val uri = createDownload(name, "application/octet-stream", relative)
            var good = false
            try {
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("cannot open MediaStore stream")
                out.use { stream ->
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        buffer.get(row)
                        stream.write(row)
                        if (y < 32 || y >= height - 32 || y % 128 == 0) {
                            var nonZero = 0
                            for (index in 0 until row.size step 16) if (row[index].toInt() != 0) nonZero++
                            if (y < 32 && nonZero > 0) firstRowsNonZero++
                            if (y >= height - 32 && nonZero > 0) lastRowsNonZero++
                            checkedRows++
                        }
                    }
                }
                good = true
            } finally {
                if (good) finishDownload(uri) else contentResolver.delete(uri, null, null)
            }
            val result = captureResult.get()
            val sensorTimestamp = result?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
            val black = characteristics?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val blackValues = if (black == null) emptyList<Int>() else listOf(
                black.getOffsetForIndex(0, 0), black.getOffsetForIndex(0, 1),
                black.getOffsetForIndex(1, 0), black.getOffsetForIndex(1, 1)
            )
            val json = JSONObject().apply {
                put("cameraId", cameraId)
                put("opMode", OP_MODE)
                put("streamTopology", "YUV 2560x1920 repeating + RAW_SENSOR 8192x6144 still")
                put("width", width)
                put("height", height)
                put("imageFormat", format)
                put("rowStride", rowStride)
                put("pixelStride", pixelStride)
                put("imageTimestamp", timestamp)
                put("resultSensorTimestamp", sensorTimestamp ?: JSONObject.NULL)
                put("timestampsMatch", sensorTimestamp != null && sensorTimestamp == timestamp)
                put("exposureTimeNs", result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: JSONObject.NULL)
                put("iso", result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
                put("afState", result?.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE) ?: JSONObject.NULL)
                put("focusDistance", result?.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) ?: JSONObject.NULL)
                put("cfaArrangement", characteristics?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
                put("whiteLevel", characteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: JSONObject.NULL)
                put("blackLevelBayer2x2", org.json.JSONArray(blackValues))
                put("first32RowsWithNonzeroSamples", firstRowsNonZero)
                put("last32RowsWithNonzeroSamples", lastRowsNonZero)
                put("checkedRows", checkedRows)
                put("rawFile", name)
                put("expectedFileBytes", height.toLong() * activeBytes)
                put("yuvFramesSeen", yuvFrameCount)
            }
            saveText("${filePrefix}_metadata.json", json.toString(2))
            emit("RAW_SAVE_OK file=$name bytes=${height.toLong() * activeBytes} firstRows=$firstRowsNonZero lastRows=$lastRowsNonZero")
        } catch (error: Exception) {
            failure("raw_save", error.javaClass.simpleName + ":" + error.message)
        }
    }

    private fun createDownload(name: String, mime: String, relative: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")
    }

    private fun finishDownload(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, values, null, null)
    }

    private fun saveText(name: String, text: String) {
        try {
            val uri = createDownload(name, "text/plain", "${Environment.DIRECTORY_DOWNLOADS}/X14U_X5_YUV_PROBE/")
            var okay = false
            try {
                val stream = contentResolver.openOutputStream(uri) ?: throw IOException("no output stream")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                okay = true
            } finally {
                if (okay) finishDownload(uri) else contentResolver.delete(uri, null, null)
            }
        } catch (error: Exception) {
            Log.e(TAG, "SAVE_TEXT_FAIL file=$name ${error.message}")
        }
    }

    override fun onDestroy() {
        captureButton.isEnabled = false
        try { session?.close() } catch (_: Exception) { }
        try { camera?.close() } catch (_: Exception) { }
        try { yuvReader?.close() } catch (_: Exception) { }
        try { rawReader?.close() } catch (_: Exception) { }
        cameraThread.quitSafely()
        ioThread.quitSafely()
        saveThread.quitSafely()
        super.onDestroy()
    }
}
