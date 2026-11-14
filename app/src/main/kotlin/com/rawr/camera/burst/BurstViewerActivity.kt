package com.rawr.camera.burst

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
    detailSide: Int? = null, imageModifier: Modifier = Modifier
) {
    var bitmap by remember(file?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.path, file?.length(), side) { bitmap = file?.let { FrameCache.get(it, side) } }
    // Zoomed in: a sharper decode replaces the view-size one once it's in (the view-size one shows meanwhile).
    var detail by remember(file?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.path, file?.length(), detailSide) {
        detail = if (detailSide != null && file != null) FrameCache.get(file, detailSide) else null
    }
    Box(modifier.background(Color(0xFF1C1A17)), contentAlignment = Alignment.Center) {
        (detail ?: bitmap)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().then(imageModifier), contentScale = contentScale) }
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
            Text("Bursts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
        }
        if (bursts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No bursts yet. Hold the shutter in Burst mode.", color = Muted)
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
                        Text("%.1f s · %d fps".format(Locale.US, info.lengthSeconds, info.fps), color = Muted, fontSize = 12.sp)
                        if (info.preFrames > 0) Text("${info.preFrames} before the press", color = Muted, fontSize = 12.sp)
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
    LaunchedEffect(playing) {
        while (playing) {
            delay((1000L / burst.fps.coerceIn(1, 60)))
            if (position >= burst.frames.lastIndex) playing = false else position += 1
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←  Back", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(buildString { append("BURST  "); append(index + 1); append(" / "); append(burst.frames.size) },
                    color = Muted, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp)
                Text(SimpleDateFormat("d MMM · HH:mm:ss", Locale.getDefault()).format(Date(burst.startedAt)),
                    color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(56.dp))
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
            FrameImage(
                frame.view, 1400, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                detailSide = if (zoom > 1.5f) 4096 else null,
                imageModifier = Modifier.graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                }
            )
            if (zoom > 1.01f) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Badge(String.format(Locale.US, "%.1f×", zoom), Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (frame.number == burst.pressNumber) Badge("● PRESS")
                if (frame.number == burst.cover) Badge("COVER")
                if (frame.number < burst.pressNumber) Badge("BEFORE PRESS", Muted)
            }
            if (selecting) {
                val on = frame.number in selected
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp)
                    .background(if (on) CaptureColors.Accent else Color.Black.copy(alpha = .4f), CircleShape)
                    .border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Text("✓", color = Color(0xFF111111), fontSize = 14.sp)
                }
            }
        }
        Timeline(burst, index, selected, onIndex = { playing = false; position = it })
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
                    if (f.number in selected) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp)
                        .background(CaptureColors.Accent, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(if (playing) "Pause" else "Play", Modifier.weight(1f)) {
                    if (!playing && index >= burst.frames.lastIndex) position = 0
                    playing = !playing
                }
                ActionButton("Select", Modifier.weight(1f)) { playing = false; selecting = true; selected = setOf(frame.number) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Develop full quality", Modifier.weight(1f), accent = true, enabled = frame.dng != null) {
                    val dng = frame.dng ?: return@ActionButton
                    context.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(dng), "image/x-adobe-dng")
                        .setClass(context, com.rawr.camera.renderer.RendererActivity::class.java))
                }
                ActionButton("Export", Modifier.weight(1f)) {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BurstStore.export(context, folder, setOf(frame.number)) }
                        toast(if (n > 0) "Saved to DCIM/Rawr Bursts" else "Couldn't export")
                    }
                }
            }
            val days = burst.rawDaysLeft()
            Text(
                when {
                    frame.dng == null -> "No RAW for this frame"
                    days != null -> "RAW kept for $days more ${if (days == 1) "day" else "days"} · Develop opens it in the renderer"
                    else -> "Develop opens this frame's RAW in the renderer with your photo settings"
                },
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", color = CaptureColors.Accent, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("All", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clickable { selected = burst.frames.map { it.number }.toSet() }.padding(8.dp))
                Text("Cancel", color = Muted, fontSize = 14.sp,
                    modifier = Modifier.clickable { selecting = false; selected = emptySet() }.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Keep ${selected.size}, delete ${burst.frames.size - selected.size}", Modifier.weight(1f),
                    enabled = selected.isNotEmpty() && selected.size < burst.frames.size) { confirmKeep = true }
                ActionButton("Export ${selected.size}", Modifier.weight(1f), accent = true, enabled = selected.isNotEmpty()) {
                    val chosen = selected
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BurstStore.export(context, folder, chosen) }
                        toast(if (n > 0) "$n saved to DCIM/Rawr Bursts" else "Couldn't export")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Set as cover", Modifier.weight(1f), enabled = selected.size == 1) {
                    BurstStore.setCover(context, folder, selected.first())
                    toast("Cover set")
                    reload++
                }
                ActionButton("Export with RAW", Modifier.weight(1f), enabled = selected.isNotEmpty()) {
                    val chosen = selected
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { BurstStore.export(context, folder, chosen, withDng = true) }
                        toast(if (n > 0) "$n files saved to DCIM/Rawr Bursts" else "Couldn't export")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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

@Composable
private fun ActionButton(label: String, modifier: Modifier, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = .3f)
        accent -> CaptureColors.Accent
        else -> Color.White
    }
    Box(
        modifier.height(44.dp)
            .border(1.dp, if (accent && enabled) CaptureColors.Accent else Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = tint, fontSize = 14.sp, maxLines = 1) }
}

/** One tick per frame: before the press tinted, the press a line, selected frames marked; tap or drag to move. */
@Composable
private fun Timeline(burst: BurstInfo, index: Int, selected: Set<Int>, onIndex: (Int) -> Unit) {
    val n = burst.frames.size
    fun at(x: Float, width: Float) = ((x / width) * n).toInt().coerceIn(0, n - 1)
    Column(Modifier.fillMaxWidth().background(Color(0xFF141312), RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(40.dp)
                .pointerInput(n) { detectTapGestures { onIndex(at(it.x, size.width.toFloat())) } }
                .pointerInput(n) { detectDragGestures { change, _ -> onIndex(at(change.position.x, size.width.toFloat())) } }
        ) {
            val step = size.width / n
            val tickW = (step * .55f).coerceIn(1f, 3f * density)
            burst.frames.forEachIndexed { i, f ->
                val x = step * (i + .5f)
                val color = if (f.number < burst.pressNumber) PreTint else Color(0xFFBDBDBD)
                drawLine(color, Offset(x, 6f), Offset(x, size.height * .72f), tickW)
                if (f.number in selected) drawLine(CaptureColors.Accent, Offset(x, size.height * .8f), Offset(x, size.height), tickW * 1.4f)
            }
            val press = burst.frames.indexOfFirst { it.number == burst.pressNumber }
            if (press >= 0 && burst.preFrames > 0) {
                val x = step * press
                drawLine(CaptureColors.Accent.copy(alpha = .7f), Offset(x, 0f), Offset(x, size.height * .76f), 2f * density)
            }
            val cx = step * (index + .5f)
            drawLine(CaptureColors.Accent, Offset(cx, 0f), Offset(cx, size.height * .76f), 3f * density)
        }
        Row {
            val pre = burst.frames.count { it.number < burst.pressNumber }
            val fps = burst.fps.coerceAtLeast(1).toFloat()
            Text(if (pre > 0) "−%.1f s".format(Locale.US, pre / fps) else "0 s", color = Muted, fontFamily = Mono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (pre > 0) Text("press", color = CaptureColors.Accent, fontFamily = Mono, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("+%.1f s".format(Locale.US, (n - pre) / fps), color = Muted, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}
