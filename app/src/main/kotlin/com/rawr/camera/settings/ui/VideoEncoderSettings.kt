package com.rawr.camera.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.rawr.camera.settings.architecture.SetVideoEncoder
import com.rawr.camera.settings.architecture.SettingsDispatch
import com.rawr.camera.settings.model.KeyframeInterval
import com.rawr.camera.settings.model.SettingsUiState
import com.rawr.camera.settings.model.VideoAudioCodec
import com.rawr.camera.settings.model.VideoAudioRoute
import com.rawr.camera.settings.model.VideoAudioSource
import com.rawr.camera.settings.model.VideoBitrateMode
import com.rawr.camera.settings.model.VideoEncoderConfig
import com.rawr.camera.video.VideoEncoderCapabilities
import com.rawr.camera.video.VideoResolutionMode

@Composable
internal fun VideoEncoderSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val encoder = state.values.videoEncoder
    val set: (VideoEncoderConfig) -> Unit = { dispatch.invoke(SetVideoEncoder(it)) }
    val supportedModes = remember { VideoEncoderCapabilities.supportedBitrateModes }
    val maxBitrateMbps = remember { VideoEncoderCapabilities.maxBitrateMbps }
    val context = androidx.compose.ui.platform.LocalContext.current
    val unprocessedSupported = remember { com.rawr.camera.video.VideoAudioInput.supportsUnprocessed(context) }
    val bFramesProduced = remember { com.rawr.camera.video.EncoderObservations.bFramesProduced(context) }
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("VideoEncoder")) {
        SettingsGroup(
            title = "Recording",
            description = "The recording timer always shows a health dot (green: keeping up, amber: near the frame " +
                "budget, red: frames dropped) and a summary after a take that had problems."
        ) {
            SettingsSwitchRow(
                title = "Show diagnostics",
                checked = encoder.showDiagnostics,
                supportingText = "Tap the recording timer for FPS, drops, GPU time and encoder delay; " +
                    "a summary after every take"
            ) { set(encoder.copy(showDiagnostics = it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Mute audio",
                checked = encoder.audioMuted,
                supportingText = "Records a silent audio track (also the AUDIO button on the capture screen)"
            ) { set(encoder.copy(audioMuted = it)) }
        }
        SettingsGroup(
            title = "Horizon Lock",
            description = "Lock keeps the saved video level while you move (Level tile → Lock, Video only); the " +
                "viewfinder behaves like a stock camera. The picture is zoomed in by a fixed " +
                "amount so the turned frame stays full: the wider the range, the more it crops. Past the range the " +
                "horizon follows the phone. ±15° is the default."
        ) {
            val shownRange = state.values.horizonLockRange.resolved(1f)
            com.rawr.camera.settings.model.HorizonLockRange.entries
                .filter { it != com.rawr.camera.settings.model.HorizonLockRange.Auto }
                .forEachIndexed { index, range ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = "${range.label} · %.2fx crop".format(range.crop),
                    selected = shownRange == range,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetHorizonLockRange(range)) }
            }
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = "Sound meter",
                checked = state.values.videoAudioMeter,
                supportingText = "Two small level bars over the histogram's corner in Video",
                onCheckedChange = { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoAudioMeter(it)) }
            )
        }
        SettingsGroup(
            title = "Shutter display",
            description = "How the video shutter reads: as a time (1/60) or as a shutter angle (180° = half the " +
                "frame time). The camera is set by angle either way, so a choice keeps its look when the frame rate " +
                "changes."
        ) {
            listOf(false to "Time · 1/60", true to "Angle · 180°").forEachIndexed { index, (angle, title) ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = title,
                    selected = state.values.videoShutterAngle == angle,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoShutterAngle(angle)) }
            }
        }
        SettingsGroup(
            title = "Stabilise",
            description = "Takes the shake out of the video using the gyroscope (Stabilise tile, Video, back lenses), " +
                "inside a fixed crop: Standard about 1.15x, Strong about 1.3x for walking. The viewfinder shows it " +
                "while recording. With Horizon Lock on Full it uses the crop that's already there."
        ) {
            com.rawr.camera.settings.model.VideoStabilize.entries.forEachIndexed { index, mode ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = if (mode == com.rawr.camera.settings.model.VideoStabilize.Off) mode.label
                        else "${mode.label} · ${mode.crop}x",
                    selected = state.values.videoStabilize == mode,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoStabilize(mode)) }
            }
        }
        SettingsGroup(
            title = "Horizon Follow",
            description = "Follow (Level tile → Follow): the video follows your tilt slowly, like a gimbal's roll, so " +
                "shake goes and deliberate turns come through as smooth rolls. The viewfinder shows the recorded " +
                "frame. Crops about 1.4x."
        ) {
            com.rawr.camera.settings.model.HorizonSmoothing.entries.forEachIndexed { index, smoothing ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = "${smoothing.label} · ${smoothing.seconds} s",
                    selected = state.values.horizonSmoothing == smoothing,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetHorizonSmoothing(smoothing)) }
            }
        }
        SettingsGroup(
            title = "Video",
            description = "HEVC settings apply to the next recording. Some encoders ignore B-frame limits; the recording monitor shows the actual GOP."
        ) {
            SettingsRow(
                "Bit depth"
            )
            if (state.values.videoLogEnabled) SettingsRow("10-bit", value = "Required by LOG")
            else CompactChoiceRow(listOf(10, 8), encoder.bitDepth, { "$it-bit" }) { set(encoder.copy(bitDepth = it)) }
            SettingDivider()
            SettingsRow("Bitrate mode")
            CompactChoiceRow(
                VideoBitrateMode.entries.filter { it.wireValue in supportedModes || it == encoder.bitrateMode },
                encoder.bitrateMode,
                { it.label }
            ) { set(encoder.copy(bitrateMode = it)) }
            VideoResolutionMode.entries.forEach { mode ->
                SettingDivider()
                StandaloneNumericSliderRow(
                    identity = "video_bitrate_${mode.name}",
                    label = "${mode.label} bitrate",
                    minimum = VideoEncoderConfig.MIN_BITRATE_MBPS.toFloat(),
                    maximum = maxBitrateMbps.toFloat(),
                    step = 1f,
                    decimals = 0,
                    value = encoder.bitrateMbps(mode).toFloat(),
                    defaultValue = VideoEncoderConfig().bitrateMbps(mode).toFloat(),
                    unit = " Mbps",
                    enabled = encoder.bitrateMode != VideoBitrateMode.Cq
                ) { set(encoder.withBitrateMbps(mode, it.toInt())) }
            }
            SettingDivider()
            val interval = encoder.keyframeInterval
            val frames = interval.frames(30)
            SettingsRow(
                "I-frame interval",
                value = if (frames <= 1) "Every frame"
                else "${if (interval.wireValue == 1) "0.5" else "${interval.wireValue / 2}"} s · ${interval.frames(24)} / $frames frames"
            )
            CompactChoiceRow(KeyframeInterval.entries, interval, { it.label }) {
                set(encoder.copy(keyframeInterval = it))
            }
            SettingDivider()
            if (frames <= 1) SettingsRow("Max B-frames", value = "Off with All-I")
            else {
                SettingsRow(
                    "Max B-frames",
                    value = if (encoder.maxBFrames < 0) "Auto" else "${encoder.maxBFrames}",
                    supportingText = if (bFramesProduced == false && encoder.maxBFrames != 0)
                        "This phone's encoder made no B-frames in your last recording; it records I and P frames only." else null
                )
                CompactChoiceRow(
                    VideoEncoderConfig.MAX_B_FRAME_CHOICES,
                    encoder.maxBFrames,
                    { if (it < 0) "Auto" else "$it" }
                ) { set(encoder.copy(maxBFrames = it)) }
            }
            SettingDivider()
            SettingsRow("GOP at 30 fps", value = VideoEncoderConfig.gopPattern(frames, encoder.maxBFrames))
        }
        SettingsGroup(
            title = "Audio",
            description = "48 kHz. PCM records uncompressed audio in a .mov; AAC records a smaller .mp4. " +
                "Stereo falls back to mono if the microphone can't do stereo. " +
                "Gain is digital: Android has no analog mic gain. Watch the MIC meter; the lamp turns red on clipping."
        ) {
            SettingsRow("Codec", value = encoder.audioCodec.label)
            CompactChoiceRow(VideoAudioCodec.entries, encoder.audioCodec, { it.label }) {
                set(encoder.copy(audioCodec = it))
            }
            SettingDivider()
            SettingsRow("Source", value = encoder.audioSource.label)
            CompactChoiceRow(
                VideoAudioSource.entries.filter {
                    it != VideoAudioSource.Unprocessed || unprocessedSupported || it == encoder.audioSource
                },
                encoder.audioSource,
                { it.label }
            ) { set(encoder.copy(audioSource = it)) }
            SettingDivider()
            SettingsRow("Input", value = encoder.audioRoute.label)
            CompactChoiceRow(VideoAudioRoute.entries, encoder.audioRoute, { it.label }) {
                set(encoder.copy(audioRoute = it))
            }
            SettingDivider()
            StandaloneNumericSliderRow(
                identity = "video_audio_gain",
                label = "Gain",
                minimum = VideoEncoderConfig.MIN_AUDIO_GAIN_DB.toFloat(),
                maximum = VideoEncoderConfig.MAX_AUDIO_GAIN_DB.toFloat(),
                step = 1f,
                decimals = 0,
                value = encoder.audioGainDb.toFloat(),
                defaultValue = 0f,
                valueFormatter = { if (it.toInt() == 0) "0 dB" else "%+d dB".format(it.toInt()) }
            ) { set(encoder.copy(audioGainDb = it.toInt())) }
            SettingDivider()
            SettingsRow("Low cut", value = if (encoder.audioLowCutHz == 0) "Off" else "${encoder.audioLowCutHz} Hz")
            CompactChoiceRow(VideoEncoderConfig.AUDIO_LOW_CUT_HZ, encoder.audioLowCutHz, { if (it == 0) "Off" else "$it Hz" }) {
                set(encoder.copy(audioLowCutHz = it))
            }
            SettingDivider()
            SettingsRow("Channels", value = if (encoder.audioChannels == 1) "Mono" else "Stereo")
            CompactChoiceRow(listOf(2, 1), encoder.audioChannels, { if (it == 1) "Mono" else "Stereo" }) {
                set(encoder.copy(audioChannels = it))
            }
            if (!encoder.audioCodec.isPcm) {
                SettingDivider()
                SettingsRow("Bitrate", value = "${encoder.audioBitrateKbps} kbps")
                CompactChoiceRow(VideoEncoderConfig.AUDIO_BITRATES_KBPS, encoder.audioBitrateKbps, { "$it" }) {
                    set(encoder.copy(audioBitrateKbps = it))
                }
            }
        }
    }
}
