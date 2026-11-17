package com.rawr.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.SetExposureMode
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.burst.TimedBurstSettings
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.storage.CaptureFileNames
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Timer

/** "300 × 2 s", "∞ × 5 s". */
internal fun timedBurstShort(v: TimedBurstSettings.Values): String =
    "${if (v.photos > 0) v.photos else "∞"} × ${v.intervalS} s"

private fun durationText(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "about ${(seconds + 30) / 60} min"
    else -> "about %.1f h".format(seconds / 3600f)
}

/**
 * Timed burst panel (the Timed burst tile): On / Off, how many photos, how often, and whether exposure stays locked
 * or follows the light. Same controls as the video crop panel.
 */
@Composable
internal fun X14uTimedBurstPanel(
    values: TimedBurstSettings.Values,
    onChange: (TimedBurstSettings.Values) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photos = TimedBurstSettings.PHOTOS
    val intervals = TimedBurstSettings.INTERVALS
    X14uSheet("TIMED BURST", onClose, modifier.testTag("x14u_timed_burst_panel"),
        icon = Icons.Outlined.Timer) {
        X14uSegments(listOf("Off", "On"), if (values.enabled) 1 else 0, { onChange(values.copy(enabled = it == 1)) })
        X14uSheetLabel("Photos")
        X14uSegments(photos.map { if (it > 0) "$it" else "∞" }, photos.indexOf(values.photos),
            { onChange(values.copy(photos = photos[it], enabled = true)) })
        X14uSheetLabel("Every")
        X14uSegments(intervals.map { "$it s" }, intervals.indexOf(values.intervalS),
            { onChange(values.copy(intervalS = intervals[it], enabled = true)) })
        X14uSheetLabel("Exposure")
        X14uSegments(listOf("Locked", "Follows the light"), if (values.lockExposure) 0 else 1,
            { onChange(values.copy(lockExposure = it == 0, enabled = true)) })
        X14uSheetLabel("Merge each shot (HDR+ / Rexture)")
        X14uSegments(listOf("Auto", "Off"), if (values.mergeAuto) 0 else 1,
            { onChange(values.copy(mergeAuto = it == 0, enabled = true)) })
        X14uSheetDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (values.photos > 0) "${values.photos} photos · ${durationText(values.durationS ?: 0)}"
                else "Until you stop it",
                color = Color.White.copy(alpha = .7f), fontFamily = X14uFont, fontSize = dpText(12f),
                modifier = Modifier.weight(1f)
            )
            Text(
                if (values.merges) "Shots merged" else if (values.mergeAuto) "Single frames (under 5 s)" else "Single frames",
                color = Color.White.copy(alpha = .5f), fontFamily = X14uFont, fontSize = dpText(12f)
            )
        }
    }
}

/**
 * A timed burst run: locks focus and white balance (and exposure when asked) from what the camera is doing at the
 * press, takes a photo every interval and gives the locks back at the end.
 */
internal class TimedRun {
    private data class Saved(val exposure: ExposureMode, val focus: FocusMode, val wb: WhiteBalanceMode)

    var running by mutableStateOf(false)
        private set
    var shots by mutableIntStateOf(0)
        private set
    /** 0 = until stopped. */
    var total by mutableIntStateOf(0)
        private set
    var intervalMs by mutableLongStateOf(1_000L)
        private set
    /** uptimeMillis of the next photo. */
    var nextAt by mutableLongStateOf(0L)
        private set
    var locks by mutableStateOf(emptyList<String>())
        private set
    private var saved: Saved? = null
    private var folder = ""
    private var startedAt = 0L
    private var intervalS = 1
    private var context: android.content.Context? = null

    /**
     * The run's photos go to Rawr's own storage like a burst (files/burst_frames/TIMED_<date>_<time>/…_001), so the
     * burst viewer plays them as a time lapse, makes the video and stacks them; [context] writes its manifest.
     */
    fun start(state: CaptureUiState, dispatch: CaptureDispatch, settings: TimedBurstSettings.Values, context: android.content.Context) {
        saved = Saved(state.exposureControl.mode, state.focus.mode, state.whiteBalanceMode)
        this.context = context.applicationContext
        startedAt = System.currentTimeMillis()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(startedAt))
        folder = "TIMED_$stamp"
        CaptureFileNames.startBurst("_TIMED${stamp.substringAfter('_')}", folder, firstLive = 1)
        // Single frames when merging is off or the interval is too short for a merge (Auto).
        TimedBurstSettings.runSingleFrame = !settings.merges
        intervalS = settings.intervalS
        // Written now too, so a run cut short (the app closed) still opens as a timed run.
        com.rawr.camera.burst.BurstStore.writeTimedManifest(context.applicationContext, folder, startedAt, intervalS, 0)
        total = settings.photos
        intervalMs = settings.intervalS * 1_000L
        shots = 0
        val list = mutableListOf<String>()
        if (settings.lockExposure && ExposureMode.Manual in state.capabilities.supportedExposureModes) {
            if (state.exposureControl.mode != ExposureMode.Manual) dispatch(SetExposureMode(ExposureMode.Manual))
            list += "AE"
        }
        if (state.focus.mode == FocusMode.Af) dispatch(SetFocusMode(FocusMode.AfLock))
        list += "AF"
        if (state.whiteBalanceMode != WhiteBalanceMode.ManualTempTint && state.capabilities.manualWhiteBalanceSupported) {
            dispatch(LockWhiteBalance(state.whiteBalanceTemperatureK, state.whiteBalanceTint))
        }
        list += "WB"
        locks = list
        // The first photo after the locks settle.
        nextAt = android.os.SystemClock.uptimeMillis() + 400L
        running = true
    }

    /** Names and takes the next photo through [shoot]; ends the run after the last. */
    fun shoot(shoot: () -> Unit) {
        shots += 1
        CaptureFileNames.burstFrameQueued()
        shoot()
        nextAt += intervalMs
        // Running late (a slow save): the next one waits a full interval from now, not a burst to catch up.
        val now = android.os.SystemClock.uptimeMillis()
        if (nextAt < now) nextAt = now + intervalMs
    }

    val finished: Boolean get() = total > 0 && shots >= total

    fun end(state: CaptureUiState, dispatch: CaptureDispatch) {
        saved?.let { s ->
            if (s.exposure != state.exposureControl.mode && s.exposure in state.capabilities.supportedExposureModes) {
                dispatch(SetExposureMode(s.exposure))
            }
            if (s.focus != state.focus.mode) dispatch(SetFocusMode(s.focus))
            if (s.wb != WhiteBalanceMode.ManualTempTint && s.wb != state.whiteBalanceMode) dispatch(SetWhiteBalanceMode(s.wb))
        }
        saved = null
        running = false
        locks = emptyList()
        TimedBurstSettings.runSingleFrame = false
        // Photos already on their way still go to the run's folder.
        CaptureFileNames.endBurstAfterQueued()
        context?.let { com.rawr.camera.burst.BurstStore.writeTimedManifest(it, folder, startedAt, intervalS, shots) }
        context = null
    }
}

/** Above the shutter while a run goes: the count, the next photo and the time left. */
@Composable
internal fun X14uTimedCounter(run: TimedRun, nowMs: Long, orientation: Orientation) {
    Column(Modifier.uprightLayoutInLandscape(orientation), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${run.shots}", color = Color.White, fontFamily = CaptureMono, fontSize = dpText(20f), fontWeight = FontWeight.Medium)
            Text(if (run.total > 0) " / ${run.total}" else " / ∞", color = Color.White.copy(alpha = .55f),
                fontFamily = CaptureMono, fontSize = dpText(14f))
        }
        val next = ((run.nextAt - nowMs).coerceAtLeast(0L) / 100L) / 10f
        val left = if (run.total > 0) ((run.total - run.shots).coerceAtLeast(0) * run.intervalMs / 1000L).toInt() else null
        Text(
            "next in %.1f s".format(next) + (left?.let { " · " + "%d:%02d left".format(it / 60, it % 60) } ?: ""),
            color = Color.White.copy(alpha = .55f), fontFamily = X14uFont, fontSize = dpText(12f)
        )
    }
}
