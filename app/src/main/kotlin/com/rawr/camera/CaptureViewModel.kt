package com.rawr.camera

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SyncToneControls
import com.rawr.camera.integration.DeviceLensDefaults
import com.rawr.camera.settings.model.effectiveLensProfiles
import com.rawr.camera.settings.model.selectOption
import com.rawr.camera.integration.NativeCaptureScreenController
import com.rawr.camera.integration.RawPreviewCoordinator
import com.rawr.camera.integration.StillCaptureCoordinator
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.ToneParameter
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.toFilmSimQuickState
import com.rawr.camera.model.toRenderProfileQuickState
import com.rawr.camera.model.toViewfinderQuickState
import com.rawr.camera.model.withToneParameter
import com.rawr.camera.settings.architecture.SetFilmSimDiscreteValue
import com.rawr.camera.settings.architecture.SetFilmSimFlag
import com.rawr.camera.settings.architecture.SetFilmSimNumericValue
import com.rawr.camera.settings.architecture.capturePreferences
import com.rawr.camera.settings.architecture.editActiveProfileTone
import com.rawr.camera.settings.architecture.withAgxDefault
import com.rawr.camera.settings.architecture.withAgxValue
import com.rawr.camera.settings.architecture.withCapturePreferenceChanges
import com.rawr.camera.settings.architecture.withFilmDiscrete
import com.rawr.camera.settings.architecture.withFilmFlag
import com.rawr.camera.settings.architecture.withFilmNumeric
import com.rawr.camera.settings.architecture.withFilmPreset
import com.rawr.camera.settings.model.FilmSimSpecs
import com.rawr.camera.settings.model.SettingsCatalog
import com.rawr.camera.settings.model.withRenderProfile
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.editThroughLens
import com.rawr.camera.settings.model.withLensOverrides
import com.rawr.camera.settings.model.options
import com.rawr.camera.settings.model.persistedId
import com.rawr.camera.settings.model.storedValue
import com.rawr.camera.settings.preferences.SettingsPreferencesStore
import com.rawr.camera.storage.GalleryMediaStore
import com.rawr.camera.video.VideoResolutionMode
import com.rawr.camera.video.toVideoImageSettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rawr.camera.settings.model.withModeLookSwitch

/**
 * Application lifetime owner.
 *
 * Compose state is production-backed for camera/lens/exposure/focus controls.
 * Processing controls not yet integrated remain application/UI concerns.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val previewCoordinator = RawPreviewCoordinator(application)
    private val settingsDefaults = SettingsCatalog.initialState().values
    private val settingsEditor = SettingsPreferencesStore.editor(application, settingsDefaults)

    /** X14U: what the camera and captures use: global settings with the active lens's overrides applied. */
    private val latestSettings: SettingsValues get() = settingsEditor.state.value.withLensOverrides()
    private val effectiveValues = settingsEditor.values.map { it.withLensOverrides() }

    /** A capture-screen edit: settings the active lens overrides change on the lens ([perLens] keys always). */
    private fun updateForLens(perLens: Set<String> = emptySet(), edit: (SettingsValues) -> SettingsValues): SettingsValues =
        settingsEditor.update { it.editThroughLens(perLens, DeviceLensDefaults.lenses, edit) }
    val cameraLocation = com.rawr.camera.location.CameraLocation.get(application)
    val foreground = com.rawr.camera.integration.CaptureForeground(cameraLocation::setForeground)
    fun videoImageSettings(): com.rawr.camera.video.VideoImageSettings = latestSettings.toVideoImageSettings()
    fun videoEncoderConfig(): com.rawr.camera.settings.model.VideoEncoderConfig = latestSettings.videoEncoder.sanitized()
    /** Microphone setup for the video-mode meter; follows Settings edits. */
    val videoAudioInput: StateFlow<com.rawr.camera.video.VideoAudioInput> = settingsEditor.state
        .map { com.rawr.camera.video.VideoAudioInput.of(it.videoEncoder.sanitized()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.rawr.camera.video.VideoAudioInput.of(latestSettings.videoEncoder.sanitized()))
    /** X14U: Settings → Video → Show diagnostics. */
    val videoDiagnostics: StateFlow<Boolean> = settingsEditor.state
        .map { it.videoEncoder.showDiagnostics }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, latestSettings.videoEncoder.showDiagnostics)

    /** X14U: Pro (new screen) or Photo (classic screen) for photos. */
    val proUi: StateFlow<Boolean> = settingsEditor.state
        .map { it.x14uProUi }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, latestSettings.x14uProUi)

    /** X14U Pano mode (UI only; stills run as Photo). Not kept across restarts. */
    private val panoFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pano: StateFlow<Boolean> = panoFlow
    fun setPano(on: Boolean) {
        panoFlow.value = on
        if (!on) com.rawr.camera.storage.CaptureFileNames.setNextStillSuffix("")
    }

    /**
     * Pano undo: removes the files of the frame tagged [tag] ("_PANO2231_03") that this app saved. A merge may still
     * be writing them, so it looks again a few times over the next two minutes.
     */
    fun deletePanoFrame(tag: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (wait in longArrayOf(0L, 4_000L, 15_000L, 45_000L, 60_000L)) {
                kotlinx.coroutines.delay(wait)
                val removed = runCatching {
                    app.contentResolver.delete(
                        android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL),
                        "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                            "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                        arrayOf("%$tag%", app.packageName)
                    )
                }.getOrDefault(0)
                if (removed > 0) refreshLatestGalleryImage()
            }
        }
    }

    fun setProUi(pro: Boolean) {
        settingsEditor.update { prev ->
            prev.copy(x14uProUi = pro, stillsSwitched = prev.stillsSwitched || pro != prev.x14uProUi).withModeLookSwitch(prev)
        }
    }

    /** X14U tools panel: what its tiles show (lens names whose image is mirrored, for the front camera's tile). */
    data class ToolsState(
        val photoAspect: com.rawr.camera.settings.model.PhotoAspect = com.rawr.camera.settings.model.PhotoAspect.Native,
        val level: Boolean = false,
        val location: Boolean = false,
        val mirroredLenses: Set<String> = emptySet(),
        val focusLoupe: com.rawr.camera.settings.model.FocusLoupeSettings = com.rawr.camera.settings.model.FocusLoupeSettings(),
        /** Digital zoom chain: lens name to its zoom factor, widest first. */
        val zoomChain: List<Pair<String, Float>> = emptyList(),
        /** Video horizon lock (the Level tile's "Level + lock"). */
        val horizonLock: Boolean = false
    )
    val tools: StateFlow<ToolsState> = settingsEditor.state
        .map { v ->
            ToolsState(
                v.photoAspect, v.viewfinderLevel, v.locationTagging,
                v.effectiveLensProfiles(DeviceLensDefaults.lenses)
                    .filter { it.image.mirror != com.rawr.camera.settings.model.LensMirror.None }.map { it.name }.toSet(),
                v.focusLoupe,
                com.rawr.camera.settings.model.ZoomChain.of(v.effectiveLensProfiles(DeviceLensDefaults.lenses)),
                v.videoHorizonLock
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ToolsState())

    /** X14U digital zoom: crop on top of the current lens (1 = none); photos only. */
    @Volatile private var digitalZoomCrop = 1f
    fun setDigitalZoom(crop: Float) {
        val c = crop.coerceIn(1f, 8f)
        if (c == digitalZoomCrop) return
        digitalZoomCrop = c
        previewCoordinator.setDigitalZoom(c, latestSettings.zoomUpscale)
    }

    fun setPhotoAspect(aspect: com.rawr.camera.settings.model.PhotoAspect) {
        settingsEditor.update { it.copy(photoAspect = aspect) }
    }
    /** One-time "Swipe up for Photo+" hint: shows until Photo/Photo+ is switched once, at most twice. */
    val stillsHint: StateFlow<Boolean> = settingsEditor.state
        .map { !it.stillsSwitched && it.stillsHintCount < 2 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    fun stillsHintShown() {
        settingsEditor.update { it.copy(stillsHintCount = it.stillsHintCount + 1) }
    }

    fun setHorizonLock(on: Boolean) {
        settingsEditor.update { it.copy(videoHorizonLock = on, viewfinderLevel = it.viewfinderLevel || on) }
    }

    fun toggleLevel() {
        // Level off is lock off too (the tile's Off).
        settingsEditor.update {
            it.copy(viewfinderLevel = !it.viewfinderLevel, videoHorizonLock = it.videoHorizonLock && !it.viewfinderLevel)
        }
    }

    /** X14U video horizon lock: gravity in, roll and crop out to the native viewfinder and recorder. */
    private val horizonSensor = com.rawr.camera.integration.HorizonLockSensor(application) { roll, crop ->
        previewCoordinator.setHorizonLock(roll, crop)
    }
    fun setLocationTagging(on: Boolean) {
        settingsEditor.update { it.copy(locationTagging = on) }
    }

    /**
     * Mirror front: flips the lens image left-right as seen (viewfinder, photo, video; DNG by orientation). The lens
     * mirror is in the sensor frame, so a sensor mounted at 90° / 270° mirrors on its vertical axis.
     */
    fun toggleLensMirror(lensId: String) {
        settingsEditor.update { v ->
            v.copy(lensProfiles = v.effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                if (lens.name != lensId) lens else {
                    val next = if (lens.image.mirror != com.rawr.camera.settings.model.LensMirror.None) {
                        com.rawr.camera.settings.model.LensMirror.None
                    } else {
                        val id = lens.camera.physical.ifEmpty { lens.camera.id }
                        val sensor = runCatching {
                            getApplication<Application>().getSystemService(android.hardware.camera2.CameraManager::class.java)
                                .getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        }.getOrNull() ?: 270
                        if (sensor % 180 == 90) com.rawr.camera.settings.model.LensMirror.Vertical
                        else com.rawr.camera.settings.model.LensMirror.Horizontal
                    }
                    lens.copy(image = lens.image.copy(mirror = next))
                }
            })
        }
    }

    /** X14U: the AUDIO button (tap: mute / unmute). */
    fun toggleAudioMuted() {
        settingsEditor.update { it.copy(videoEncoder = it.videoEncoder.copy(audioMuted = !it.videoEncoder.audioMuted)) }
    }

    /** Startup diagnostics use the same optimistic owner, including when preferences are still loading. */
    fun applyStartupVideoOverrides(resolution: VideoResolutionMode?, fps: Int?) {
        if (resolution == null && fps == null) return
        val next = settingsEditor.update { current -> current.copy(
            videoResolutionId = resolution?.persistedId() ?: current.videoResolutionId,
            videoFps = fps?.takeIf { it == 24 || it == 30 || it == 60 } ?: current.videoFps
        ) }
        controller.dispatch(next.withLensOverrides().capturePreferences())
        settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoResolution)
    }

    private val mutableRenderProfiles = MutableStateFlow(settingsDefaults.toRenderProfileQuickState())
    val renderProfiles: StateFlow<RenderProfileQuickState> = mutableRenderProfiles.asStateFlow()
    private val mutableFilmSimQuick = MutableStateFlow(settingsDefaults.toFilmSimQuickState())
    val filmSimQuick: StateFlow<FilmSimQuickState> = mutableFilmSimQuick.asStateFlow()
    /** X14U: a lens's fixed f-number from its camera, cached by camera id. */
    private val apertureCache = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private fun nativeApertures(values: SettingsValues): Map<String, Float> =
        values.effectiveLensProfiles(DeviceLensDefaults.lenses).mapNotNull { lens ->
            val id = lens.camera.physical.ifEmpty { lens.camera.id }
            val f = apertureCache[id] ?: runCatching {
                val manager = getApplication<Application>().getSystemService(android.hardware.camera2.CameraManager::class.java)
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull()
            }.getOrNull()?.also { apertureCache[id] = it }
            f?.let { lens.name to it }
        }.toMap()

    val viewfinderQuick: StateFlow<ViewfinderQuickState> = settingsEditor.state
        .map { it.withLensOverrides().toViewfinderQuickState(DeviceLensDefaults.lenses).copy(nativeApertures = nativeApertures(it)) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly,
            latestSettings.toViewfinderQuickState(DeviceLensDefaults.lenses))
    private val galleryMediaStore = GalleryMediaStore(application)
    private val mutableLatestGalleryImage = MutableStateFlow<Uri?>(null)
    val latestGalleryImage: StateFlow<Uri?> = mutableLatestGalleryImage.asStateFlow()
    private val galleryRefreshGeneration = AtomicLong(0L)
    private val stillCaptureCoordinator =
        StillCaptureCoordinator(
            application = application,
            preview = previewCoordinator,
            // Zoomed past a lens with multiframe on: the "Merge when zoomed" choice (default Super-resolution).
            settings = {
                latestSettings.let { s ->
                    val zoomMerge = s.zoomMergeAlgorithm
                    if (digitalZoomCrop > 1.01f && s.experimentalMultiframeEnabled && zoomMerge != null) s.copy(
                        multiframeTuning = s.multiframeTuning.copy(mergeAlgorithm = zoomMerge)
                    ) else s
                }
            },
            onJpegPublished = ::onJpegPublished,
            onDngPublished = ::refreshLatestGalleryImage,
            gpsSnapshot = cameraLocation::snapshot
        )
    val controller: CaptureScreenController =
        NativeCaptureScreenController(
            previewCoordinator,
            onCaptureQueued = stillCaptureCoordinator::enqueueCapture,
            requestStillCapture = stillCaptureCoordinator::capture,
            foregroundToken = foreground::token,
            pollStillCompletion = stillCaptureCoordinator::pollCompletion,
            onToneScrubbed = { parameter, value -> persistQuickTone(parameter, value) },
            onRenderExposureScrubbed = ::persistRenderExposure,
            onAgxControl = ::persistAgxControl,
            videoLocked = { videoSelectionLocked() },
            onRawCpuIngress = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        application,
                        "Camera frames use the CPU copy path on this device (slower, more battery)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            onLensSelected = { lensId -> settingsEditor.update { it.copy(lastLensId = lensId) } },
            onCapturePreferencesChanged = { before, after ->
                // HDR+ is remembered per lens. Mode and LOG switches change what tone settings mean, so they edit
                // the global settings directly.
                if (before.captureMode != after.captureMode || before.videoLogEnabled != after.videoLogEnabled) {
                    // X14U: each mode keeps its own Look / Exposure set (lens overrides still win).
                    settingsEditor.update { prev ->
                        prev.withCapturePreferenceChanges(before, after.copy(
                            experimentalMultiframeEnabled = before.experimentalMultiframeEnabled))
                            .withModeLookSwitch(prev)
                    }
                } else {
                    updateForLens(setOf(com.rawr.camera.settings.model.LensOverrides.HDR_PLUS)) {
                        it.withCapturePreferenceChanges(before, after)
                    }
                }
                settingsSync.updatePreviewCrop(after.captureMode, after.videoResolution)
            }
        )

    private val settingsSync by lazy {
        com.rawr.camera.integration.CaptureSettingsSync(
            viewModelScope, effectiveValues, { latestSettings }, previewCoordinator, controller
        )
    }

    var recordingOptions = com.rawr.camera.video.RecordingOptions()
    private val recordingBackend = com.rawr.camera.video.CaptureRecordingBackend(
        application, controller, previewCoordinator, { latestSettings }
    )
    val recording = com.rawr.camera.video.RecordingCoordinator(
        controller = controller,
        startRecorder = { gps -> recordingBackend.start(recordingOptions, gps) },
        clockNs = android.os.SystemClock::elapsedRealtimeNanos,
        foregroundToken = foreground::token,
        validateAccess = cameraLocation::validateAccess,
        admitGps = { if (latestSettings.locationTagging) cameraLocation.snapshot() else null },
        onSaved = { recorder ->
            if (recordingOptions.inspectOutput) {
                recordingOptions = recordingOptions.copy(inspectOutput = false)
                recordingBackend.inspectSaved(recorder)
            }
        }
    )
    private var captureVisible = false
    private var previewStopJob: kotlinx.coroutines.Job? = null

    fun startPreview() {
        captureVisible = true
        viewModelScope.launch {
            previewStopJob?.join()
            if (captureVisible) previewCoordinator.start()
        }
    }

    fun setUiPresentationActive(active: Boolean) = previewCoordinator.setUiPresentationActive(active)

    fun stopPreviewAfterRecording() {
        captureVisible = false
        val stopping = recording.stop()
        previewStopJob = viewModelScope.launch {
            stopping.join()
            if (!captureVisible) previewCoordinator.stop()
        }
    }

    init {
        refreshLatestGalleryImage()
        viewModelScope.launch { recordingBackend.recoverInterrupted() }
        viewModelScope.launch {
            effectiveValues.map { it.capturePreferences() }.distinctUntilChanged().collect { preferences ->
                controller.dispatch(preferences)
                settingsSync.updatePreviewCrop(controller.state.value.captureMode, controller.state.value.videoResolution)
            }
        }
        viewModelScope.launch {
            effectiveValues.map { it.toRenderProfileQuickState() }.distinctUntilChanged().collect {
                mutableRenderProfiles.value = it
            }
        }
        viewModelScope.launch {
            settingsEditor.values.distinctUntilChanged { before, after ->
                before.selectedFilmPresetId == after.selectedFilmPresetId &&
                    before.filmSimLook == after.filmSimLook && before.filmPresets == after.filmPresets
            }.collect { mutableFilmSimQuick.value = it.toFilmSimQuickState() }
        }
        viewModelScope.launch {
            settingsEditor.values.map { it.zoomUpscale }.distinctUntilChanged().collect {
                previewCoordinator.setDigitalZoom(digitalZoomCrop, it)
            }
        }
        // Once: each lens's photo DCP defaults to an imported Main profile (Tele on the 3.2x / 5x) when it has none,
        // and video goes back to the camera's own colour (a DCP costs video frames).
        viewModelScope.launch {
            settingsEditor.values.first().let { v ->
                if (!v.dcpDefaultsOnce) settingsEditor.update { s ->
                    val lenses = s.effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                        val tele = (com.rawr.camera.settings.model.ZoomChain.factorOf(lens.name) ?: 1f) >= 3f
                        val match = lens.image.availableDcps.firstOrNull {
                            it.name.contains(if (tele) "tele" else "main", ignoreCase = true)
                        }
                        lens.image { (if (dcp == null && match != null) selectDcp(match.file) else this).selectVideoDcp(null) }
                    }
                    s.copy(lensProfiles = lenses, dcpDefaultsOnce = true)
                }
            }
        }
        // Option A, once: in-sensor-zoom lenses (the 2x) go off so zooming crops the main camera without a switch.
        // Turned back on in Settings → Lenses, the 2x is a button again and a zoom step (1x -> 2x -> 3.2x).
        viewModelScope.launch {
            settingsEditor.values.first().let { v ->
                if (!v.inSensorZoomLensesOffOnce) {
                    settingsEditor.update { s ->
                        val lenses = s.effectiveLensProfiles(DeviceLensDefaults.lenses)
                        val crops = com.rawr.camera.settings.model.ZoomChain.inSensorCrops(lenses)
                        s.copy(
                            lensProfiles = if (crops.isEmpty()) s.lensProfiles
                            else lenses.map { if (it.name in crops) it.copy(enabled = false) else it },
                            inSensorZoomLensesOffOnce = true
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            // Pano frames are always full size (the merge wants every pixel).
            kotlinx.coroutines.flow.combine(settingsEditor.values.map { it.photoAspect }, panoFlow) { aspect, pano ->
                if (pano) com.rawr.camera.settings.model.PhotoAspect.Native.nativeRatio else aspect.nativeRatio
            }.distinctUntilChanged().collect { previewCoordinator.setOutputAspect(it) }
        }
        // Video horizon lock: on in Video with the setting, range by lens; Full freezes its framing while recording.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsEditor.values.map { it.videoHorizonLock to it.horizonLockRange }.distinctUntilChanged(),
                controller.state.map { (it.captureMode == com.rawr.camera.model.CaptureMode.Video) to it.selectedLensId }
                    .distinctUntilChanged(),
                recording.state.map { it.recording }.distinctUntilChanged()
            ) { (on, range), (video, lens), rec ->
                val resolved = range.resolved(com.rawr.camera.settings.model.ZoomChain.factorOf(lens) ?: 1f)
                val degrees = if (on && video) resolved.degrees else 0
                Triple(degrees, resolved.crop, rec to com.rawr.camera.ui.isFrontLens(lens, lens))
            }.distinctUntilChanged().collect { (degrees, crop, recFront) ->
                horizonSensor.configure(degrees, crop, recording = recFront.first, front = recFront.second)
            }
        }
        settingsSync.start()
    }

    fun refreshLatestGalleryImage() {
        val generation = galleryRefreshGeneration.incrementAndGet()
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) { galleryMediaStore.findLatestRawrImage() }
            if (galleryRefreshGeneration.get() == generation) mutableLatestGalleryImage.value = latest
        }
    }

    private fun onJpegPublished(uri: Uri) {
        galleryMediaStore.rememberPublishedJpeg(uri)
        galleryRefreshGeneration.incrementAndGet()
        mutableLatestGalleryImage.value = uri
    }

    private fun videoSelectionLocked(): Boolean = recording.state.value.let { it.busy || it.recording }

    fun selectRenderProfileFromCapture(selection: com.rawr.camera.model.RenderProfileSelection) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        val next = settingsEditor.update { it.withRenderProfile(selection) }.withLensOverrides()
        mutableRenderProfiles.value = next.toRenderProfileQuickState()
        previewCoordinator.setColorRenderProfile(next.effectiveRenderProfileId(), next.regularLutProfileId())
        controller.dispatch(SyncToneControls.fromImageTone(next.activeImageTone()))
    }

    fun onFilmEvent(event: com.rawr.camera.architecture.CaptureFilmEvent) {
        val next = settingsEditor.update { current ->
            when (event) {
                is com.rawr.camera.architecture.CaptureFilmEvent.SelectPreset -> current.withFilmPreset(event.id)
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, event.value), quantize = true, enableGrain = true
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ResetNumeric -> current.withFilmNumeric(
                    SetFilmSimNumericValue(event.parameter, FilmSimSpecs.forParameter(event.parameter).defaultValue)
                )
                is com.rawr.camera.architecture.CaptureFilmEvent.ScrubDiscrete -> {
                    if (event.index !in event.field.options().indices) current else current.withFilmDiscrete(
                        SetFilmSimDiscreteValue(event.field, event.field.storedValue(event.index))
                    )
                }
                is com.rawr.camera.architecture.CaptureFilmEvent.SetFlag -> current.withFilmFlag(
                    SetFilmSimFlag(event.flag, event.enabled)
                )
            }
        }
        mutableFilmSimQuick.value = next.toFilmSimQuickState()
        previewCoordinator.setFilmSimLook(next.filmSimLook.toFloatArray(), next.filmSimLook.toIntArray())
    }

    /** Rail edits go through settings; CaptureSettingsSync projects them onto the preview and stills. */
    fun onQuickEvent(event: CaptureQuickEvent) {
        if (latestSettings.isVideo && videoSelectionLocked()) return
        when (event) {
            is CaptureQuickEvent.SelectDcp, is CaptureQuickEvent.SelectLensOption -> {
                settingsEditor.update { c -> c.withLensQuickEvent(event) }
                return
            }
            else -> Unit
        }
        updateForLens { c ->
            val video = c.isVideo
            when (event) {
                is CaptureQuickEvent.SelectDcp, is CaptureQuickEvent.SelectLensOption -> c
                is CaptureQuickEvent.SetRawrAeOption -> c.copy(rawrAe = event.edit(c.rawrAe))
                CaptureQuickEvent.ToggleRawrAe -> c.copy(rawrAe = c.rawrAe.copy(enabled = !c.rawrAe.enabled))
                is CaptureQuickEvent.SetMetering -> c.copy(rawrAe = c.rawrAe.copy(metering = event.metering))
                is CaptureQuickEvent.SetHighlightMode -> {
                    val enabled = event.mode != HighlightQuickMode.Off
                    val method = when (event.mode) {
                        HighlightQuickMode.Off -> if (video) c.videoHighlightMethod else c.photoHighlightMethod
                        HighlightQuickMode.Propagation -> 0
                        HighlightQuickMode.Opposed -> 1
                    }
                    if (video) c.copy(videoHighlightEnabled = enabled, videoHighlightMethod = method)
                    else c.copy(photoHighlightEnabled = enabled, photoHighlightMethod = method)
                }
                is CaptureQuickEvent.SetHighlightThreshold -> event.value.coerceIn(0.5f, 2f).let {
                    if (video) c.copy(videoHighlightThreshold = it) else c.copy(photoHighlightThreshold = it)
                }
                is CaptureQuickEvent.SetHighlightCompression -> event.value.coerceIn(0f, 300f).let {
                    if (video) c.copy(videoHighlightCompression = it) else c.copy(photoHighlightCompression = it)
                }
            }
        }
    }

    /** Lens-list edits from the capture screen (DCP pill, option toggles); the video DCP in video mode. */
    private fun SettingsValues.withLensQuickEvent(event: CaptureQuickEvent): SettingsValues = when (event) {
        is CaptureQuickEvent.SelectDcp -> if (controller.state.value.selectedLensId != event.lensId) this else
            copy(lensProfiles = effectiveLensProfiles(DeviceLensDefaults.lenses).map { lens ->
                if (lens.name != event.lensId) lens
                else if (isVideo) lens.image { selectVideoDcp(event.file) } else lens.image { selectDcp(event.file) }
            })
        is CaptureQuickEvent.SelectLensOption -> lensProfiles?.let { lenses ->
            copy(lensProfiles = lenses.map { lens ->
                if (lens.name == event.lensId) lens.selectOption(event.row, event.choice) else lens
            })
        } ?: this
        else -> this
    }

    private fun persistQuickTone(parameter: ToneParameter, value: Int) {
        updateForLens { current ->
            current.editActiveProfileTone { it.withToneParameter(parameter, value.coerceIn(-100, 100).toFloat()) }
        }
    }

    private fun persistAgxControl(parameter: com.rawr.camera.settings.model.AgxParameter, value: Float?) {
        settingsEditor.update { current ->
            if (value == null) current.withAgxDefault(parameter) else current.withAgxValue(parameter, value)
        }
    }

    private fun persistRenderExposure(tenths: Int) {
        val ev = com.rawr.camera.model.TonemapControlContract.exposureEvFromTenths(tenths)
        updateForLens { current -> current.editActiveProfileTone { it.copy(renderExposure = ev) } }
    }

    /** Diagnostic camera-ID override; native routing remains authoritative. */

    fun selectCameraIdFromIntent(cameraId: String) {
        previewCoordinator.setPreferredCameraId(cameraId)
    }

    override fun onCleared() {
        horizonSensor.close()
        recording.closeWhenIdle {
            stillCaptureCoordinator.closeWhenIdle {
                controller.close()
                previewCoordinator.close()
            }
        }
    }
}
