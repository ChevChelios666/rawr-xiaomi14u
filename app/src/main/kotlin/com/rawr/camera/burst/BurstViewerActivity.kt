package com.rawr.camera.burst

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.ui.CaptureColors
import com.rawr.camera.ui.CaptureTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * X14U burst library and viewer. Opened from the camera's thumbnail in Burst mode (the last burst) or from the
 * renderer library. Frames live in Rawr's own storage; Export copies the chosen ones to DCIM/Rawr Bursts/.
 */
class BurstViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_FOLDER)
        setContent { CaptureTheme { BurstScreen(start, onExit = ::finish) } }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        fun intent(context: Context, folder: String? = null): Intent =
            Intent(context, BurstViewerActivity::class.java).apply { folder?.let { putExtra(EXTRA_FOLDER, it) } }
    }
}

private val Bg = Color(0xFF0B0B0B)
private val Card = Color(0xFF161514)
private val Line = Color(0xFF2A2A2A)
private val Muted = Color(0xFF8D8A85)
private val PreTint = Color(0xFF8A6A63)
private val Mono = FontFamily.Monospace

/** Decoded frames, shared by the library and viewer (thumbnails ~360 px, views ~1200 px). */
private object FrameCache {
    val cache = object : LruCache<String, Bitmap>(160 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    suspend fun get(file: java.io.File, side: Int): Bitmap? {
        val key = "${file.path}@$side@${file.length()}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { BurstStore.decode(file, side) }?.also { cache.put(key, it) }
    }
}

@Composable
private fun FrameImage(
    file: java.io.File?, side: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop,
    detailSide: Int? = null, imageModifier: Modifier = Modifier,
    /** Shown until this view's own decode is in (the playback frame, so scrubbing never flashes empty). */
    placeholder: Bitmap? = null
) {
    var bitmap by remember(file?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.path, file?.length(), side) { bitmap = file?.let { FrameCache.get(it, side) } }
    // Zoomed in: a sharper decode replaces the view-size one once it's in (the view-size one shows meanwhile).
    var detail by remember(file?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.path, file?.length(), detailSide) {
        detail = if (detailSide != null && file != null) FrameCache.get(file, detailSide) else null
    }
    Box(modifier.background(Color(0xFF1C1A17)), contentAlignment = Alignment.Center) {
        (detail ?: bitmap ?: placeholder)?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().then(imageModifier), contentScale = contentScale)
        }
            ?: Text("…", color = Muted)
    }
}

@Composable
private fun BurstScreen(startFolder: String?, onExit: () -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(startFolder) }
    var refresh by remember { mutableStateOf(0) }
    var bursts by remember { mutableStateOf<List<BurstInfo>>(emptyList()) }
    LaunchedEffect(refresh) {
        bursts = withContext(Dispatchers.IO) { BurstStore.cleanup(context); BurstStore.list(context) }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val open = folder
        if (open == null) {
            BackHandler(onBack = onExit)
            BurstLibrary(bursts, onOpen = { folder = it }, onDelete = { f ->
                BurstStore.deleteBurst(context, f); refresh++
            }, onExit = onExit)
        } else {
            BackHandler { folder = null; refresh++ }
            BurstViewer(open, onBack = { folder = null; refresh++ })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BurstLibrary(bursts: List<BurstInfo>, onOpen: (String) -> Unit, onDelete: (String) -> Unit, onExit: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←  Back", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onExit))
            Spacer(Modifier.weight(1f))
            Text("Bursts and timed runs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }
        if (bursts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing yet. Hold the shutter in Burst mode, or turn on Timed burst.", color = Muted)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(bursts, key = { it.folder }) { info ->
                Row(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onOpen(info.folder) }, onLongClick = { confirmDelete = info.folder })
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(96.dp)) {
                        Box(Modifier.offset(x = 8.dp, y = (-6).dp).size(84.dp).background(Color(0xFF3A3530), RoundedCornerShape(10.dp)))
                        FrameImage(info.coverFrame?.view, 360,
                            Modifier.offset(y = 6.dp).size(84.dp).border(2.dp, Bg, RoundedCornerShape(10.dp))
                                .background(Bg, RoundedCornerShape(10.dp)))
                        Text("${info.frames.size}", color = Color(0xFF1A1A1A), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomEnd).background(CaptureColors.Accent, RoundedCornerShape(9.dp))
                                .padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SimpleDateFormat("d MMM · HH:mm:ss", Locale.getDefault()).format(Date(info.startedAt)),
                            color = Color.White, fontSize = 15.sp)
                        if (info.isTimed) {
                            Text("Timed · ${info.frames.size} × ${info.intervalS} s", color = Muted, fontSize = 12.sp)
                        } else {
                            Text("%.1f s · %d fps".format(Locale.US, info.lengthSeconds, info.fps), color = Muted, fontSize = 12.sp)
                            if (info.preFrames > 0) Text("${info.preFrames} before the press", color = Muted, fontSize = 12.sp)
                        }
                        val days = info.rawDaysLeft()
                        Text(
                            when {
                                info.format == "Raw" -> "RAW only"
                                days == null -> if (info.frames.any { it.dng != null }) "RAW kept" else "JPEG"
                                else -> "RAW $days ${if (days == 1) "day" else "days"} left"
                            },
                            color = Muted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this burst?") },
            text = { Text("All its frames are deleted. Exported photos stay.") },
            confirmButton = { TextButton(onClick = { onDelete(f); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BurstViewer(folder: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<BurstInfo?>(null) }
    var reload by remember { mutableStateOf(0) }
    // Frames may still be saving: re-read the folder for a while.
    LaunchedEffect(folder, reload) {
        repeat(40) {
            info = withContext(Dispatchers.IO) { BurstStore.load(context, folder) }
            val pending = info?.frames?.any { it.view == null } ?: true
            if (!pending && it > 0) return@LaunchedEffect
            delay(1500L)
        }
    }
    val burst = info ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = Muted) }
        return
    }
    if (burst.frames.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var position by remember(folder) { mutableStateOf(burst.frames.indexOfFirst { it.number == burst.cover }.coerceAtLeast(0)) }
    val index = position.coerceIn(0, burst.frames.lastIndex)
    var playing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember(folder) { mutableStateOf(setOf<Int>()) }
    var confirmKeep by remember { mutableStateOf(false) }
    val frame = burst.frames[index]
    val strip = rememberLazyListState()
    LaunchedEffect(index) { strip.animateScrollToItem((index - 3).coerceAtLeast(0)) }
    // Playback frames: every frame decoded small up front (progress on the frame), so Play shows each one at the
    // burst's own speed. It used to step on a timer while each 12 MP frame was still decoding, and skipped most.
    val views = burst.frames.map { it.view?.path }
    val playback = remember(folder, views) { mutableStateListOf<Bitmap?>().apply { repeat(burst.frames.size) { add(null) } } }
    var prepared by remember(folder, views) { mutableStateOf(0) }
    val allPrepared = prepared >= burst.frames.size
    LaunchedEffect(folder, views) {
        val files = burst.frames.map { it.view }
        // About 192 MB of 16-bit frames in all: the long side shrinks for long bursts (480..1600 px).
        val side = kotlin.math.sqrt(192.0 * 1024 * 1024 / files.size / 1.5).toInt().coerceIn(480, 1600)
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            files.forEachIndexed { i, file ->
                launch(Dispatchers.IO) {
                    val bitmap = gate.withPermit { file?.let { BurstStore.decodePlayback(it, side) } }
                    withContext(Dispatchers.Main) {
                        playback[i] = bitmap
                        prepared++
                    }
                }
            }
        }
    }
    // Paced by the display clock from the press of Play: frame k shows at k / fps, none skipped (all decoded).
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val fps = burst.fps.coerceIn(1, 60).toDouble()
        val from = position
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (playing) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val next = from + ((now - start) / 1e9 * fps).toInt()
            if (next >= burst.frames.lastIndex) {
                position = burst.frames.lastIndex
                playing = false
            } else if (next != position) position = next
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // Export video / Stack: the open sheet, and a job running (its progress over the frame, Stop cancels it).
    var sheet by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var jobCancel by remember { mutableStateOf(false) }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val clockSeconds = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun frameTime(i: Int): String = if (burst.isTimed) clockSeconds.format(Date(burst.startedAt + i * burst.intervalS * 1000L))
        else "%+.2f s".format(Locale.US, (burst.frames[i].number - burst.pressNumber) / burst.fps.coerceAtLeast(1).toFloat())
    fun runJob(title: String, work: (progress: (Int, Int) -> Boolean) -> String) {
        sheet = null
        jobCancel = false
        job = title to 0f
        scope.launch {
            val message = withContext(Dispatchers.Default) {
                work { done, total ->
                    scope.launch(Dispatchers.Main) { if (job != null) job = title to done / total.coerceAtLeast(1).toFloat() }
                    !jobCancel
                }
            }
            job = null
            toast(message)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        // Header: back, the run (kind, start, frames and speed) and a second line with its length and RAW.
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 26.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (burst.isTimed) "TIMED ${clock.format(Date(burst.startedAt))} · ${burst.frames.size} × ${burst.intervalS} s"
                    else "BURST ${clock.format(Date(burst.startedAt))} · ${burst.frames.size} × ${burst.fps} fps",
                    color = Color.White, fontFamily = Mono, fontSize = 13.sp
                )
                val days = burst.rawDaysLeft()
                Text(
                    if (burst.isTimed) {
                        val runS = (burst.frames.size - 1).coerceAtLeast(0) * burst.intervalS
                        val run = if (runS >= 60) "${(runS + 30) / 60} min run" else "$runS s run"
                        "%s · plays as %.1f s at %d fps".format(Locale.US, run, burst.frames.size / burst.fps.coerceAtLeast(1).toFloat(), burst.fps)
                    } else listOfNotNull(
                        "%.1f s".format(Locale.US, burst.lengthSeconds),
                        if (burst.preFrames > 0) "${burst.preFrames} before the shutter" else null,
                        when {
                            burst.format == "Raw" -> "RAW only"
                            days != null -> "RAW $days ${if (days == 1) "day" else "days"}"
                            burst.frames.any { it.dng != null } -> "RAW kept"
                            else -> null
                        }
                    ).joinToString(" · "),
                    color = Muted, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(34.dp))
        }
        // The frame (tap: select it while selecting). Pinch to zoom, drag to pan, double tap: 3x there / back to fit.
        // The zoom stays while scrubbing, so frames compare at the same spot.
        var zoom by remember(folder) { mutableStateOf(1f) }
        var pan by remember(folder) { mutableStateOf(Offset.Zero) }
        var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        fun clampPan(p: Offset, z: Float): Offset {
            val maxX = viewSize.width * (z - 1f) / 2f
            val maxY = viewSize.height * (z - 1f) / 2f
            return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)
                .clipToBounds()
                .onSizeChanged { viewSize = it }
                .pointerInput(selecting, frame.number) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            if (zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
                            else {
                                val z = 3f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                zoom = z
                                pan = clampPan((center - at) * (z - 1f), z)
                            }
                        },
                        onTap = {
                            if (selecting) selected = if (frame.number in selected) selected - frame.number else selected + frame.number
                        }
                    )
                }
                .pointerInput(folder) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.count { it.pressed }
                            // One finger pans only when zoomed in; two pinch (around the fingers) and pan.
                            if (pointers >= 2 || (pointers == 1 && zoom > 1.01f)) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val k = newZoom / zoom
                                // Keep the image point under the fingers where it is.
                                val p = (pan - (centroid - center)) * k + (centroid - center) + event.calculatePan()
                                zoom = newZoom
                                pan = if (newZoom <= 1.01f) Offset.Zero else clampPan(p, newZoom)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val zoomLayer = Modifier.graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
            }
            val playbackFrame = playback.getOrNull(index)
            if (playing && playbackFrame != null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C1A17))) {
                    Image(playbackFrame.asImageBitmap(), null, Modifier.fillMaxSize().then(zoomLayer),
                        contentScale = ContentScale.Fit)
                }
            } else {
                FrameImage(
                    frame.view, 1400, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    detailSide = if (zoom > 1.5f) 4096 else null,
                    imageModifier = zoomLayer,
                    placeholder = playbackFrame
                )
            }
            // Progress: playback preparing, or an export / stack running (Stop cancels it).
            val running = job
            if (running != null || !allPrepared) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth()
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    val fraction = running?.second ?: (prepared / burst.frames.size.toFloat())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(running?.let { "${it.first} · ${(it.second * 100).toInt()} %" }
                            ?: "Preparing playback · $prepared / ${burst.frames.size}",
                            color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (running != null) Text("Stop", color = CaptureColors.Accent, fontSize = 13.sp,
                            modifier = Modifier.clickable { jobCancel = true }.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Line, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
                            .background(CaptureColors.Accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
            if (zoom > 1.01f) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Badge(String.format(Locale.US, "%.1f×", zoom), Color.White)
                }
            }
            // Frame number and its time (from the shutter press, or the clock for a timed run); what it is.
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Badge(if (selecting) "${selected.size} selected" else "${index + 1} / ${burst.frames.size} · ${frameTime(index)}", Color.White)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!burst.isTimed && frame.number == burst.pressNumber) Badge("◉ SHUTTER")
                if (frame.number == burst.cover) Badge("COVER", Color.White)
                if (!burst.isTimed && frame.number < burst.pressNumber) Badge("BEFORE SHUTTER", Muted)
            }
            if (selecting) {
                val on = frame.number in selected
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(26.dp)
                    .background(if (on) CaptureColors.Accent else Color.Black.copy(alpha = .4f), CircleShape)
                    .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Text("✓", color = Color(0xFF111111), fontSize = 14.sp)
                }
            }
        }
        Timeline(burst, index, selected, ready = { playback.getOrNull(it) != null },
            onIndex = { playing = false; position = it })
        Spacer(Modifier.height(8.dp))
        LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(burst.frames.size) { i ->
                val f = burst.frames[i]
                Box(
                    Modifier.size(52.dp)
                        .border(2.dp, if (i == index) CaptureColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(2.dp)
                        .combinedClickable(onClick = { playing = false; position = i }, onLongClick = {
                            selecting = true
                            selected = if (f.number in selected) selected - f.number else selected + f.number
                        })
                ) {
                    FrameImage(f.view, 200, Modifier.fillMaxSize())
                    if (f.number in selected) Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(12.dp)
                        .background(CaptureColors.Accent, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // The bar: Play · Select · Develop · Video · Stack; while selecting, what to do with the ticked frames.
        val busy = job != null
        if (!selecting) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
                BarItem(if (playing) "❚❚" else "▶", if (playing) "Pause" else "Play", enabled = (allPrepared || playing) && !busy) {
                    if (!playing && index >= burst.frames.lastIndex) position = 0
                    playing = !playing
                }
                BarItem("✓", "Select", enabled = !busy) { playing = false; selecting = true; selected = setOf(frame.number) }
                BarItem("◎", "Develop", enabled = frame.dng != null && !busy) {
                    val dng = frame.dng ?: return@BarItem
                    if (!developWith(context, dng)) toast("Couldn't open the RAW")
                }
                BarItem("▣", "Video", accent = sheet == "video", enabled = !busy) { playing = false; sheet = "video" }
                BarItem("≡", "Stack", accent = sheet == "stack", enabled = !busy) { playing = false; sheet = "stack" }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
                BarItem("✓", "Keep ${selected.size}", accent = true,
                    enabled = selected.isNotEmpty() && selected.size < burst.frames.size && !busy) { confirmKeep = true }
                BarItem("↧", "Export ${selected.size}", enabled = selected.isNotEmpty() && !busy) {
                    val chosen = selected
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BurstStore.export(context, folder, chosen) }
                        toast(if (n > 0) "$n saved to ${BurstStore.exportDir(burst).removeSuffix("/")}" else "Couldn't export")
                    }
                }
                BarItem("◎", "+ RAW", enabled = selected.isNotEmpty() && !busy) {
                    val chosen = selected
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BurstStore.export(context, folder, chosen, withDng = true) }
                        toast(if (n > 0) "$n files saved to ${BurstStore.exportDir(burst).removeSuffix("/")}" else "Couldn't export")
                    }
                }
                BarItem("★", "Cover", enabled = selected.size == 1) {
                    BurstStore.setCover(context, folder, selected.first())
                    toast("Cover set")
                    reload++
                }
                BarItem("All", if (selected.size == burst.frames.size) "None" else "All") {
                    selected = if (selected.size == burst.frames.size) emptySet() else burst.frames.map { it.number }.toSet()
                }
                BarItem("✕", "Cancel") { selecting = false; selected = emptySet() }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    // Export video / Stack sheets (the camera's panel style), over the bottom of the viewer.
    val chosenFrames = burst.frames.filter { it.number in selected }
    if (sheet == "video") {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
            VideoSheet(burst, chosenFrames.size, onClose = { sheet = null }) { options, onlySelected ->
                val frames = if (onlySelected) chosenFrames else burst.frames
                runJob("Making the video") { progress -> FrameVideoExport.export(context, burst, frames, options, progress).message }
            }
        }
    }
    if (sheet == "stack") {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
            StackSheet(burst, chosenFrames.size, onClose = { sheet = null }) { mode, align, onlySelected ->
                val frames = if (onlySelected) chosenFrames else burst.frames
                runJob("Stacking (${mode.label})") { progress -> FrameStack.stack(context, burst, frames, mode, align, progress).message }
            }
        }
    }
    if (confirmKeep) {
        val drop = burst.frames.map { it.number }.toSet() - selected
        AlertDialog(
            onDismissRequest = { confirmKeep = false },
            title = { Text("Keep ${selected.size}, delete ${drop.size}?") },
            text = { Text("The other frames are deleted from this burst. Exported photos stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmKeep = false
                    scope.launch {
                        withContext(Dispatchers.IO) { BurstStore.deleteFrames(context, folder, drop) }
                        selecting = false
                        selected = emptySet()
                        position = 0
                        reload++
                    }
                }) { Text("Delete ${drop.size}") }
            },
            dismissButton = { TextButton(onClick = { confirmKeep = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Badge(text: String, color: Color = CaptureColors.Accent) {
    Text(text, color = color, fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = Mono,
        modifier = Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** A bar item: a symbol over its label (accent when it's the open one). */
@Composable
private fun BarItem(symbol: String, label: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = .3f)
        accent -> CaptureColors.Accent
        else -> Color.White.copy(alpha = .85f)
    }
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(symbol, color = tint, fontSize = 18.sp, maxLines = 1)
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

/** Export video: format, speed, size, deflicker and which frames; the camera's panel controls. */
@Composable
private fun VideoSheet(
    burst: BurstInfo,
    selectedCount: Int,
    onClose: () -> Unit,
    onExport: (FrameVideoExport.Options, onlySelected: Boolean) -> Unit
) {
    val formats = FrameVideoExport.Format.entries
    var format by remember { mutableStateOf(FrameVideoExport.Format.Hevc) }
    // A burst plays in real time (its own fps) or slowed; a timed run as a time lapse.
    val speeds = if (burst.isTimed) listOf(24, 30, 60) else listOf(burst.fps.coerceIn(1, 60), 10, 24).distinct()
    var fps by remember { mutableStateOf(speeds.first()) }
    var uhd by remember { mutableStateOf(false) }
    var deflicker by remember { mutableStateOf(burst.isTimed) }
    var onlySelected by remember { mutableStateOf(false) }
    val frames = if (onlySelected) selectedCount else burst.frames.size
    com.rawr.camera.ui.X14uSheet("EXPORT VIDEO", onClose) {
        com.rawr.camera.ui.X14uSheetLabel("Format")
        com.rawr.camera.ui.X14uSegments(formats.map { it.label }, formats.indexOf(format), { format = formats[it] })
        com.rawr.camera.ui.X14uSheetLabel("Speed")
        com.rawr.camera.ui.X14uSegments(
            speeds.map { if (!burst.isTimed && it == burst.fps) "Real time $it fps" else if (!burst.isTimed && it < burst.fps) "Slow $it fps" else "$it fps" },
            speeds.indexOf(fps), { fps = speeds[it] })
        if (format != FrameVideoExport.Format.Gif) {
            com.rawr.camera.ui.X14uSheetLabel("Size")
            com.rawr.camera.ui.X14uSegments(listOf("1080p", "4K"), if (uhd) 1 else 0, { uhd = it == 1 })
        }
        com.rawr.camera.ui.X14uSheetLabel("Deflicker")
        com.rawr.camera.ui.X14uSegments(listOf("Off", "On"), if (deflicker) 1 else 0, { deflicker = it == 1 })
        com.rawr.camera.ui.X14uSheetLabel("Frames")
        com.rawr.camera.ui.X14uSegments(listOf("All ${burst.frames.size}", "Selected $selectedCount"), if (onlySelected) 1 else 0,
            { onlySelected = it == 1 }, enabled = { it == 0 || selectedCount >= 2 })
        com.rawr.camera.ui.X14uSheetDivider()
        val gifFrames = minOf(frames, FrameVideoExport.GIF_MAX_FRAMES)
        Text(
            if (format == FrameVideoExport.Format.Gif) "%.1f s loop · %d px · %s".format(Locale.US, gifFrames / fps.toFloat(),
                FrameVideoExport.GIF_LONG_SIDE, if (frames > gifFrames) "first $gifFrames frames" else "$gifFrames frames")
            else "%.1f s · %s MP4 · to %s".format(Locale.US, frames / fps.toFloat(), format.label, BurstStore.exportDir(burst).removeSuffix("/")),
            color = Color.White.copy(alpha = .7f), fontSize = 12.sp
        )
        com.rawr.camera.ui.X14uSegments(listOf("Cancel", "Export"), null, { i ->
            if (i == 0) onClose()
            else onExport(FrameVideoExport.Options(format, fps, if (uhd) 2160 else 1080, deflicker), onlySelected)
        }, enabled = { it == 0 || frames >= 2 }, emphasis = 1)
    }
}

/** Stack into one photo: Average / Lighten / Clean, which frames, and alignment for handheld runs. */
@Composable
private fun StackSheet(
    burst: BurstInfo,
    selectedCount: Int,
    onClose: () -> Unit,
    onStack: (FrameStack.Mode, align: Boolean, onlySelected: Boolean) -> Unit
) {
    val modes = FrameStack.Mode.entries
    var mode by remember { mutableStateOf(FrameStack.Mode.Average) }
    var align by remember { mutableStateOf(!burst.isTimed) }
    var onlySelected by remember { mutableStateOf(false) }
    val frames = if (onlySelected) selectedCount else burst.frames.size
    com.rawr.camera.ui.X14uSheet("STACK INTO ONE PHOTO", onClose) {
        com.rawr.camera.ui.X14uTiles(modes.size, 3, { modes[it] == mode }, { modes[it].label }, { modes[it].note }, { mode = modes[it] })
        com.rawr.camera.ui.X14uSheetLabel("Frames")
        com.rawr.camera.ui.X14uSegments(listOf("All ${burst.frames.size}", "Selected $selectedCount"), if (onlySelected) 1 else 0,
            { onlySelected = it == 1 }, enabled = { it == 0 || selectedCount >= 2 })
        com.rawr.camera.ui.X14uSheetLabel("Align")
        com.rawr.camera.ui.X14uSegments(listOf("Off (tripod)", "On (handheld)"), if (align) 1 else 0, { align = it == 1 })
        com.rawr.camera.ui.X14uSheetDivider()
        Text(
            when (mode) {
                FrameStack.Mode.Average -> if (burst.isTimed) "Like one %s exposure".format(
                    ((frames - 1).coerceAtLeast(0) * burst.intervalS).let { if (it >= 60) "${(it + 30) / 60} min" else "$it s" })
                    else "Less noise, motion blurred"
                FrameStack.Mode.Lighten -> "The brightest of each point: trails of moving lights"
                FrameStack.Mode.Clean -> "What stays still: passers-by and cars go (two passes)"
            } + " · to " + BurstStore.exportDir(burst).removeSuffix("/"),
            color = Color.White.copy(alpha = .7f), fontSize = 12.sp
        )
        com.rawr.camera.ui.X14uSegments(listOf("Cancel", "Stack"), null, { i ->
            if (i == 0) onClose() else onStack(mode, align, onlySelected)
        }, enabled = { it == 0 || frames >= 2 }, emphasis = 1)
    }
}

/**
 * One tick per frame: before the shutter press tinted, frames on screen / selected in the accent; tap or drag to move.
 * The shutter press is its own marker (a dashed white line under a shutter icon, "0 s" below it), so the accent line
 * only ever means the frame on screen.
 */
@Composable
private fun Timeline(burst: BurstInfo, index: Int, selected: Set<Int>, ready: (Int) -> Boolean, onIndex: (Int) -> Unit) {
    val n = burst.frames.size
    fun at(x: Float, width: Float) = ((x / width) * n).toInt().coerceIn(0, n - 1)
    val press = burst.frames.indexOfFirst { it.number == burst.pressNumber }
    val pre = burst.frames.count { it.number < burst.pressNumber }
    val marked = press >= 0 && pre > 0
    Column(Modifier.fillMaxWidth().background(Color(0xFF141312), RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(56.dp)
                .pointerInput(n) { detectTapGestures { onIndex(at(it.x, size.width.toFloat())) } }
                .pointerInput(n) { detectDragGestures { change, _ -> onIndex(at(change.position.x, size.width.toFloat())) } }
        ) {
            val step = size.width / n
            val tickW = (step * .55f).coerceIn(1f, 3f * density)
            // Top 16 dp: the shutter icon; ticks below it, selection marks at the bottom.
            val top = 16f * density
            val tickBottom = top + (size.height - top) * .72f
            burst.frames.forEachIndexed { i, f ->
                val x = step * (i + .5f)
                // Frames still preparing for playback are dim.
                val color = (if (f.number < burst.pressNumber) PreTint else Color(0xFFBDBDBD)).copy(alpha = if (ready(i)) 1f else .3f)
                drawLine(color, Offset(x, top + 6f), Offset(x, tickBottom), tickW)
                if (f.number in selected) drawLine(CaptureColors.Accent, Offset(x, top + (size.height - top) * .8f), Offset(x, size.height), tickW * 1.4f)
            }
            if (marked) {
                val x = step * press
                drawLine(Color.White, Offset(x, 14f * density), Offset(x, tickBottom + 2f * density), 1.5f * density,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * density, 2f * density)))
                val c = Offset(x, 7f * density)
                drawCircle(Color.White, 6.5f * density, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
                drawCircle(Color.White, 3.5f * density, c)
            }
            val cx = step * (index + .5f)
            drawLine(CaptureColors.Accent, Offset(cx, top), Offset(cx, tickBottom + 2f * density), 3f * density)
        }
        val fps = burst.fps.coerceAtLeast(1).toFloat()
        if (burst.isTimed) {
            // A timed run: the clock at its first and last photo.
            val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Box(Modifier.fillMaxWidth()) {
                Text(clock.format(Date(burst.startedAt)), color = Muted, fontFamily = Mono, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterStart))
                Text(clock.format(Date(burst.startedAt + (n - 1).coerceAtLeast(0) * burst.intervalS * 1000L)), color = Muted,
                    fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd))
            }
        } else Column {
        Box(Modifier.fillMaxWidth()) {
            Text(if (pre > 0) "−%.1f s".format(Locale.US, pre / fps) else "0 s", color = Muted, fontFamily = Mono, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterStart))
            Text("+%.1f s".format(Locale.US, (n - pre) / fps), color = Muted, fontFamily = Mono, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterEnd))
            // "0 s" under the shutter line, unless it would run into the end labels.
            val fraction = if (marked) press / n.toFloat() else -1f
            if (fraction in .14f..0.86f) {
                Text("0 s", color = Color.White, fontFamily = Mono, fontSize = 10.sp,
                    modifier = Modifier.layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0))
                        layout(constraints.maxWidth, p.height) {
                            p.place((constraints.maxWidth * fraction - p.width / 2f).toInt()
                                .coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)), 0)
                        }
                    })
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LegendKey(PreTint, "before shutter")
            LegendKey(Color(0xFFBDBDBD), "while held")
            LegendKey(CaptureColors.Accent, "on screen", narrow = true)
        }
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String, narrow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (narrow) 3.dp else 8.dp, 8.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

/**
 * Develop: a chooser with Rawr's renderer first, then every app that takes a DNG (Lightroom's "Add to Lightroom",
 * Snapseed…). The frame lives in Rawr's private storage, so it's lent read-only through a content link
 * (FileProvider, res/xml/shared_file_paths.xml). False when the file can't be shared.
 */
private fun developWith(context: Context, dng: java.io.File): Boolean {
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", dng)
    }.getOrNull() ?: return false
    val mime = "image/x-adobe-dng"
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(dng.name, uri)
    val rawr = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .setClass(context, com.rawr.camera.renderer.RendererActivity::class.java)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, "Develop with")
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<android.os.Parcelable>(rawr))
    return runCatching { context.startActivity(chooser) }.isSuccess
}
