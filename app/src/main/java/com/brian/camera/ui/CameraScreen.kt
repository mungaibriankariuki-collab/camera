package com.brian.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brian.camera.MainActivity
import com.brian.camera.camera.Mode
import com.brian.camera.camera.Saver
import com.brian.camera.ml.Palm
import com.brian.camera.ml.Vision
import com.brian.camera.ml.Voice
import com.brian.camera.process.Filters
import com.brian.camera.process.Meter
import com.brian.camera.process.Portrait
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraScreen(act: MainActivity, onGallery: () -> Unit, onSettings: () -> Unit) {
    val e = act.engine
    val s = act.shooter
    val prefs = act.prefs
    val caps = e.caps
    val mode = e.mode
    val scope = rememberCoroutineScope()

    var countdown by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var locked by remember { mutableStateOf(false) }
    var evVisible by remember { mutableIntStateOf(0) }     // bumps to restart the hide timer
    var showMore by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) } // "filters", "ratio", "timer"
    var vfSize by remember { mutableStateOf(IntSize.Zero) }

    // Live results
    var qr by remember { mutableStateOf<Vision.Code?>(null) }
    var portraitFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var portraitHint by remember { mutableStateOf(false) }
    var hist by remember { mutableStateOf<IntArray?>(null) }
    var peaking by remember { mutableStateOf<ImageBitmap?>(null) }
    var showPeaking by remember { mutableStateOf(true) }
    var faceBoxes by remember { mutableStateOf<List<Rect>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }
    var lensBlocks by remember { mutableStateOf<List<Vision.TextBlock>>(emptyList()) }
    var lensCodes by remember { mutableStateOf<List<Vision.Code>>(emptyList()) }
    var lensLabels by remember { mutableStateOf<List<Vision.Label>>(emptyList()) }
    var lensResult by remember { mutableStateOf<String?>(null) }

    // ---- bind while visible, unbind when another screen is open ----
    DisposableEffect(Unit) {
        e.rebind()
        onDispose { e.unbindAll() }
    }

    // ---- document scanner ----
    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data) ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            var n = 0
            r.pages?.forEach { page ->
                val tmp = File(act.cacheDir, "scan_${System.nanoTime()}.jpg")
                act.contentResolver.openInputStream(page.imageUri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                Saver.publishFile(act, tmp, "SCAN_${Saver.stamp()}.jpg", "image/jpeg")?.let { e.lastUri = it; n++ }
                tmp.delete()
            }
            val pdf = r.pdf?.uri?.let { Saver.savePdf(act, it) }
            withContext(Dispatchers.Main) {
                e.toast("Saved $n page${if (n == 1) "" else "s"}" + if (pdf != null) " + PDF in Documents/Scans" else "")
                e.lastUri?.let { s.loadThumb(it) }
            }
        }
    }
    fun launchScanner() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { docLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e.toast("Document scanner needs Google Play services: ${it.message}") }
    }

    // ---- shutter ----
    fun shutter() {
        if (e.busy != null) return
        when (mode) {
            Mode.VIDEO, Mode.SLOW_MO, Mode.DUAL -> {
                if (e.recording) { e.stopRecording(); s.recSound(false) }
                else if (countdown == 0) {
                    s.recSound(true)
                    e.startRecording { uri -> if (uri != null) { e.lastUri = uri; s.loadThumb(uri) } else e.toast("Video not saved") }
                }
            }
            Mode.SINGLE_TAKE -> s.singleTake()
            Mode.PANORAMA -> s.panoToggle()
            Mode.HYPERLAPSE -> s.lapseToggle()
            Mode.DOC -> launchScanner()
            Mode.LENS -> {
                lensResult = when (prefs.lensTool) {
                    "Text" -> lensBlocks.joinToString("\n\n") { it.text }.ifBlank { null }
                    "QR" -> lensCodes.firstOrNull()?.text
                    else -> lensLabels.joinToString("\n") { "${it.text}  ${(it.confidence * 100).toInt()}%" }.ifBlank { null }
                } ?: run { e.toast("Nothing found yet — hold steady"); null }
            }
            else -> if (prefs.timer > 0 && countdown == 0) countdown = prefs.timer else if (countdown == 0) s.photo()
        }
    }
    act.onVolume = { up ->
        if (prefs.volumeKey == "Zoom") e.setZoomLevel((e.zoom * if (up) 1.15f else 0.87f).coerceIn(e.minZoomLevel(), e.maxZoom))
        else shutter()
    }

    // ---- timer countdown ----
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    // Each tick changes the key, so this runs once per second until zero.
    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        if (prefs.shutterSound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        delay(1000)
        if (countdown == 1) {
            if (mode.video && mode != Mode.SINGLE_TAKE) {
                s.recSound(true)
                e.startRecording { uri -> if (uri != null) { e.lastUri = uri; s.loadThumb(uri) } }
            } else s.photo()
        }
        countdown--
    }

    // ---- voice control (paused while recording, since it needs the mic) ----
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(prefs.voiceControl, e.recording) {
        val v = if (prefs.voiceControl && !e.recording) Voice(act) { cmd ->
            when (cmd) {
                "photo" -> if (!mode.video) shutter()
                "record" -> if (mode.video && !e.recording) shutter()
                "stop" -> if (e.recording) shutter()
            }
        } else null
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) v?.start()
            if (ev == Lifecycle.Event.ON_PAUSE) v?.stop()
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v?.start()
        onDispose { lifecycle.removeObserver(obs); v?.stop() }
    }

    // ---- live analysis per mode ----
    val palm = remember { mutableStateOf<Palm?>(null) }
    LaunchedEffect(mode, e.front, prefs.sceneOptimizer, prefs.scanQr, prefs.palmGesture, prefs.autoFraming, prefs.shotSuggestions, prefs.lensTool, prefs.portraitEffect, prefs.portraitStrength) {
        listOf("scene", "qr", "palm", "portrait", "frame", "pro", "lens", "faces").forEach { e.unlisten(it) }
        qr = null; portraitFrame = null; hist = null; peaking = null; faceBoxes = emptyList(); hint = null
        lensBlocks = emptyList(); lensCodes = emptyList(); lensLabels = emptyList()

        if (mode == Mode.PHOTO && prefs.sceneOptimizer) e.listen("scene", 1500) { f ->
            val sc = Vision.scene(Vision.labels(f), Vision.brightness(f))
            withContext(Dispatchers.Main) { if (s.scene != sc) { s.scene = sc; s.sceneOn = true } }
        } else s.scene = null

        if (mode == Mode.PHOTO && prefs.scanQr && !e.front) {
            var misses = 0
            e.listen("qr", 800) { f ->
                val c = Vision.codes(f).firstOrNull()
                withContext(Dispatchers.Main) {
                    if (c != null) { qr = c; misses = 0 } else if (++misses >= 3) qr = null
                }
            }
        }

        if (e.front && prefs.palmGesture && (mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.NIGHT)) {
            e.listen("palm", 350) { f ->
                val p = palm.value ?: Palm(act).also { palm.value = it }
                if (p.isOpenPalm(f)) withContext(Dispatchers.Main) { if (countdown == 0 && e.busy == null) countdown = 2 }
            }
        }

        if (mode == Mode.PORTRAIT) e.listen("portrait", 0) { f ->
            val out = Portrait.live(f, prefs.portraitEffect, prefs.portraitStrength)
            withContext(Dispatchers.Main) { portraitFrame = out?.asImageBitmap(); portraitHint = out == null }
        }

        if (mode == Mode.VIDEO && prefs.autoFraming) e.listen("frame", 400) { f ->
            val faces = Vision.faces(f)
            withContext(Dispatchers.Main) {
                if (faces.isEmpty()) { if (e.zoom > 1.02f) e.setZoomLevel(max(1f, e.zoom - 0.1f)); return@withContext }
                val l = faces.minOf { it.left } / f.width.toFloat()
                val r = faces.maxOf { it.right } / f.width.toFloat()
                val t = faces.minOf { it.top } / f.height.toFloat()
                val b = faces.maxOf { it.bottom } / f.height.toFloat()
                val span = max(r - l, (b - t) * f.height / f.width)
                var target = (0.45f / span).coerceIn(1f, 2.5f)
                // Zoom only crops the centre, so never zoom past where the faces would be cut off.
                val reach = max(max(abs(l - 0.5f), abs(r - 0.5f)), max(abs(t - 0.5f), abs(b - 0.5f)))
                if (reach > 0.01f) target = min(target, 0.5f / reach * 0.9f).coerceAtLeast(1f)
                e.setZoomLevel(e.zoom + (target - e.zoom) * 0.3f)
            }
        }

        if (mode == Mode.PRO) e.listen("pro", 250) { f ->
            val h = Meter.histogram(f)
            val pk = Meter.peakingAndZebra(f, showPeaking && e.proFocus != null, true)
            withContext(Dispatchers.Main) { hist = h; peaking = pk?.asImageBitmap() }
        }

        if (mode == Mode.PHOTO && prefs.shotSuggestions) e.listen("faces", 1000) { f ->
            val faces = Vision.faces(f)
            val dark = Vision.brightness(f) < 40
            val edge = faces.any { it.left < 4 || it.top < 4 || it.right > f.width - 4 || it.bottom > f.height - 4 }
            withContext(Dispatchers.Main) {
                faceBoxes = faces
                hint = when {
                    edge -> "Face cut off — step back a little"
                    dark -> "Too dark — try Night mode"
                    faces.size == 1 && abs(faces[0].exactCenterX() / f.width - 0.5f) < 0.06f && faces[0].width() < f.width / 4 ->
                        "Tip: place the subject on a grid line"
                    else -> null
                }
            }
        }

        if (mode == Mode.LENS) e.listen("lens", 700) { f ->
            when (prefs.lensTool) {
                "Text" -> { val r = Vision.read(f); withContext(Dispatchers.Main) { lensBlocks = r } }
                "QR" -> { val r = Vision.codes(f); withContext(Dispatchers.Main) { lensCodes = r } }
                else -> { val r = Vision.labels(f); withContext(Dispatchers.Main) { lensLabels = r } }
            }
        }
    }
    LaunchedEffect(mode) { if (mode == Mode.DOC) launchScanner() }
    LaunchedEffect(Unit) {
        if (e.lastThumb == null) withContext(Dispatchers.IO) { loadMedia(act).firstOrNull() }?.let { e.lastUri = it.uri; s.loadThumb(it.uri) }
    }

    // ---- auto-hide focus ring and EV slider ----
    LaunchedEffect(focus, evVisible, locked) {
        if (focus != null && !locked) { delay(3500); focus = null }
    }

    val modes = Mode.entries.filter {
        when (it) {
            Mode.SLOW_MO -> caps?.slowMo == true
            Mode.DUAL -> caps?.concurrent != null
            else -> true
        }
    }
    val mainModes = modes.filter { it.main }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- viewfinder ----------
        val tall = e.previewAspect() < 0.7f
        Box(
            Modifier
                .padding(top = if (tall) 0.dp else 64.dp)
                .fillMaxWidth()
                .aspectRatio(if (mode == Mode.PHOTO && prefs.ratio == "1:1") 1f else e.previewAspect())
                .clip(RoundedCornerShape(0.dp))
                .onSizeChanged { vfSize = it }
                .pointerInput(mode, modes) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var last = start
                        var multi = false
                        var startDist = 0f
                        var startZoom = e.zoom
                        var longFired = false
                        while (true) {
                            val ev = if (!multi && !longFired) withTimeoutOrNull(650) { awaitPointerEvent() } else awaitPointerEvent()
                            if (ev == null) {
                                if ((last - start).getDistance() < 24f && mode != Mode.FOOD) {
                                    longFired = true
                                    locked = true
                                    focus = last
                                    e.focusAt(last.x, last.y, lock = true)
                                }
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (!multi) { multi = true; startDist = d; startZoom = e.zoom }
                                else if (startDist > 0f) e.setZoomLevel((startZoom * d / startDist).coerceIn(e.minZoomLevel(), e.maxZoom))
                                ev.changes.forEach { it.consume() }
                            } else if (pressed.isEmpty()) break
                            else last = pressed[0].position
                        }
                        if (!multi && !longFired && !e.recording) {
                            val d = last - start
                            if (abs(d.x) > 140 && abs(d.x) > abs(d.y) * 1.5f) {
                                val i = mainModes.indexOf(mode)
                                val list = if (i >= 0) mainModes else modes
                                val idx = list.indexOf(mode)
                                val next = if (d.x < 0) idx + 1 else idx - 1
                                if (next in list.indices) e.switchMode(list[next])
                            } else if (abs(d.y) > 140 && prefs.swipeToSwitch) {
                                e.flip()
                            } else if (d.getDistance() < 24f) {
                                locked = false
                                focus = start
                                evVisible++
                                if (mode == Mode.FOOD && vfSize.width > 0) {
                                    s.foodX = start.x / vfSize.width; s.foodY = start.y / vfSize.height
                                }
                                e.focusAt(start.x, start.y)
                            }
                        }
                    }
                }
        ) {
            val filterCm = when {
                mode == Mode.FOOD -> Filters.food()
                mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.NIGHT -> Filters.matrix(prefs.filter, prefs.myFilters())
                else -> null
            }
            AndroidView(
                factory = { e.previewView },
                update = { v ->
                    if (filterCm != null) v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(filterCm) })
                    else v.setLayerType(View.LAYER_TYPE_NONE, null)
                },
                modifier = Modifier.fillMaxSize()
            )
            e.highSpeed?.let { hs -> androidx.compose.runtime.key(hs) { AndroidView(factory = { hs.texture }, modifier = Modifier.fillMaxSize()) } }
            portraitFrame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            peaking?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }

            GridOverlay(prefs.grid)
            if (prefs.level && !mode.video) LevelOverlay(rememberRoll())
            if (mode == Mode.FOOD) FoodRing(s.foodX, s.foodY, s.foodRadius)
            FocusRing(focus, locked)
            if (mode == Mode.PHOTO && prefs.shotSuggestions) BoxesOverlay(faceBoxes, e.frameW, Color.White.copy(alpha = 0.6f))
            if (mode == Mode.LENS) {
                BoxesOverlay(lensBlocks.mapNotNull { it.box } + lensCodes.mapNotNull { it.box }, e.frameW)
            }
            if (mode == Mode.DUAL) {
                AndroidView(
                    factory = { e.pipView },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 12.dp).width(110.dp).aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp)).border(2.dp, Color.White, RoundedCornerShape(12.dp))
                )
            }
            if (e.screenFlash) Box(Modifier.fillMaxSize().background(Color.White))

            // EV slider after a tap
            if (focus != null && e.evRange.last > 0 && mode != Mode.PRO) {
                EvSlider(e.evIndex, e.evRange, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { e.setEv(it); evVisible++ }
            }
            if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            if (mode == Mode.PORTRAIT && portraitHint) Hint("Stay about 1.5 m away from the person", Modifier.align(Alignment.Center))
            hint?.let { Hint(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)) }
            if (mode == Mode.PANORAMA) PanoramaGuide(s, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            if (mode == Mode.HYPERLAPSE && s.lapseRunning) Hint("● ${s.lapseFrames / 30.0}s of video", Modifier.align(Alignment.TopCenter).padding(top = 80.dp))
            if (mode == Mode.PRO) Histogram(hist, Modifier.align(Alignment.TopStart).padding(top = 80.dp, start = 12.dp).width(110.dp).height(56.dp))
            if (mode == Mode.DOC) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Document scan", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Auto crop, straightening, cleanup, PDF", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Chip("Scan a document", true) { launchScanner() }
            }
            e.bindError?.let { Hint("Camera: $it", Modifier.align(Alignment.Center)) }
        }

        // ---------- top bar ----------
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(if (tall) Color.Black.copy(alpha = 0.25f) else Color.Black).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
            if (mode.video || mode == Mode.HYPERLAPSE) {
                IconButton(onClick = { e.cycleFlash() }) { Icon(if (e.torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, "Torch", tint = if (e.torch) Amber else Color.White) }
            } else if (mode != Mode.PANORAMA && mode != Mode.LENS && mode != Mode.DOC) {
                IconButton(onClick = { e.cycleFlash() }) {
                    Icon(when (prefs.flash) { 1 -> Icons.Filled.FlashAuto; 2 -> Icons.Filled.FlashOn; else -> Icons.Filled.FlashOff }, "Flash", tint = if (prefs.flash == 2) Amber else Color.White)
                }
            }
            if (!mode.video && mode !in listOf(Mode.PANORAMA, Mode.LENS, Mode.DOC, Mode.PRO)) {
                IconButton(onClick = { panel = if (panel == "timer") null else "timer" }) {
                    Icon(if (prefs.timer == 0) Icons.Filled.TimerOff else Icons.Filled.Timer, "Timer", tint = if (prefs.timer > 0) Amber else Color.White)
                }
            }
            if (mode == Mode.PHOTO) {
                IconButton(onClick = { panel = if (panel == "ratio") null else "ratio" }) { Icon(Icons.Filled.AspectRatio, "Ratio", tint = Color.White) }
                if (!e.front) Chip(if (prefs.highRes) "50M" else "12M", prefs.highRes) { prefs.highRes = !prefs.highRes; e.rebind() }
            }
            if (mode == Mode.VIDEO) {
                Chip(prefs.videoQuality, false) {
                    val q = caps?.videoQualities?.ifEmpty { null } ?: listOf("FHD", "HD")
                    prefs.videoQuality = q[(q.indexOf(prefs.videoQuality) + 1) % q.size]
                    e.rebind()
                }
                Chip("Auto frame", prefs.autoFraming) { prefs.autoFraming = !prefs.autoFraming }
            }
            if (mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.NIGHT) {
                IconButton(onClick = { panel = if (panel == "filters") null else "filters" }) {
                    Icon(Icons.Filled.FilterVintage, "Filters", tint = if (prefs.filter != "None") Amber else Color.White)
                }
            }
            if (mode == Mode.PHOTO && s.scene != null) {
                Chip("✦ ${s.scene}", s.sceneOn) { s.sceneOn = !s.sceneOn }
            }
        }

        // ---------- bottom controls ----------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(if (tall) Color.Transparent else Color.Black).navigationBarsPadding().padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            qr?.let { code -> QrCard(act, code) { qr = null } }
            e.message?.let { Hint(it, Modifier.padding(4.dp)) }
            e.busy?.let { Hint(it, Modifier.padding(4.dp)) }

            when (panel) {
                "timer" -> OptionRow(listOf("Off", "2s", "3s", "5s", "10s"), when (prefs.timer) { 0 -> "Off"; else -> "${prefs.timer}s" }) {
                    prefs.timer = if (it == "Off") 0 else it.dropLast(1).toInt(); panel = null
                }
                "ratio" -> OptionRow(listOf("3:4", "9:16", "1:1"), prefs.ratio) { prefs.ratio = it; panel = null; e.rebind() }
                "filters" -> FilterStrip(act) { panel = null }
            }
            when (mode) {
                Mode.PORTRAIT -> PortraitPanel(prefs)
                Mode.PRO -> ProPanel(e, showPeaking) { showPeaking = it }
                Mode.NIGHT -> OptionRow(listOf("3", "6", "9"), "${prefs.nightFrames}", label = "Frames") { prefs.nightFrames = it.toInt() }
                Mode.HYPERLAPSE -> if (!s.lapseRunning) OptionRow(listOf("4", "8", "16", "32"), "${prefs.hyperlapseSpeed}", suffix = "x") { prefs.hyperlapseSpeed = it.toInt() }
                Mode.LENS -> LensPanel(prefs)
                Mode.FOOD -> FoodPanel(s)
                else -> {}
            }

            // Zoom chips
            if (!e.front && mode !in listOf(Mode.DOC, Mode.DUAL)) ZoomRow(e)

            // Recording bar
            if (e.recording) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                    Spacer(Modifier.width(6.dp))
                    Text("%02d:%02d".format(e.recSeconds / 60, e.recSeconds % 60), color = Color.White, fontSize = 16.sp)
                    if (mode == Mode.VIDEO) {
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { e.togglePause() }) { Icon(if (e.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Pause", tint = Color.White) }
                    }
                }
            }

            // Mode bar
            if (!e.recording && !s.panoRunning && !s.lapseRunning && !s.singleTakeRunning) {
                val sc = rememberScrollState()
                Row(Modifier.fillMaxWidth().horizontalScroll(sc).padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Spacer(Modifier.width(24.dp))
                    mainModes.forEach { m ->
                        ModeLabel(m.label.uppercase(), m == mode) { e.switchMode(m) }
                    }
                    ModeLabel(if (mode.main) "MORE" else mode.label.uppercase(), !mode.main) { showMore = true }
                    Spacer(Modifier.width(24.dp))
                }
            }

            // Shutter row
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.DarkGray).clickable { onGallery() }) {
                    e.lastThumb?.let { Image(it.asImageBitmap(), "Gallery", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                ShutterButton(mode, e.recording || s.panoRunning || s.lapseRunning || s.singleTakeRunning, e.busy != null) { shutter() }
                IconButton(onClick = { e.flip() }, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Icon(Icons.Filled.Cameraswitch, "Switch camera", tint = Color.White)
                }
            }
        }

        if (showMore) MoreModes(modes.filter { !it.main }, mode, { showMore = false }) { e.switchMode(it); showMore = false }
        lensResult?.let { LensResultSheet(act, it, prefs.lensTool) { lensResult = null } }
    }
}

// ---------------------------------------------------------------- small pieces

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) Amber else Color.White.copy(alpha = 0.15f))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun ModeLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Amber else Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 14.sp,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
fun OptionRow(options: List<String>, selected: String, label: String? = null, suffix: String = "", onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        label?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp)) }
        options.forEach { o -> Chip(o + suffix, o == selected) { onPick(o) } }
    }
}

@Composable
fun ShutterButton(mode: Mode, active: Boolean, busy: Boolean, onClick: () -> Unit) {
    val isVideo = mode.video || mode == Mode.HYPERLAPSE
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
            .background(
                when {
                    busy -> Color.Gray
                    isVideo || (mode == Mode.PANORAMA && active) -> Color(0xFFE53935)
                    else -> Color.White
                }
            )
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (active) Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
    }
}

@Composable
fun EvSlider(index: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    Row(modifier.width(260.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("☀", color = Amber, fontSize = 16.sp)
        androidx.compose.material3.Slider(
            value = index.toFloat(),
            onValueChange = { onChange(Math.round(it)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ZoomRow(e: com.brian.camera.camera.CameraEngine) {
    val min = e.minZoomLevel()
    val stops = buildList {
        if (min < 0.95f) add(min)
        add(1f); add(2f)
        if (e.maxZoom >= 4f) add(4f)
        if (e.maxZoom >= 8f) add(8f)
        if (e.maxZoom >= 15f) add(15f)
    }
    Row(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(4.dp)) {
        stops.forEach { z ->
            val near = abs(e.zoom - z) < 0.05f || (z == stops.lastOrNull { it <= e.zoom + 0.01f })
            Box(
                Modifier.padding(horizontal = 3.dp).size(38.dp).clip(CircleShape)
                    .background(if (near) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { e.setZoomLevel(z) },
                contentAlignment = Alignment.Center
            ) {
                val label = if (near && abs(e.zoom - z) >= 0.05f) "%.1f".format(e.zoom) else if (z < 1f) "%.1f".format(z) else "${z.toInt()}"
                Text(label + if (near) "x" else "", color = if (near) Amber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PanoramaGuide(s: com.brian.camera.camera.Shooter, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                !s.panoRunning -> "Press the button, then move slowly to the right →"
                s.panoTooFast -> "Too fast — slow down"
                else -> "Keep moving right →"
            },
            color = if (s.panoTooFast) Color(0xFFFF8A80) else Color.White, fontSize = 14.sp
        )
        if (s.panoRunning) {
            Box(Modifier.padding(top = 6.dp).width(240.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(s.panoProgress.coerceIn(0f, 1f)).height(6.dp).background(Amber))
            }
        }
    }
}

@Composable
fun QrCard(ctx: Context, code: Vision.Code, onClose: () -> Unit) {
    Row(
        Modifier.padding(8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE202020)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(code.kind, color = Amber, fontSize = 12.sp)
            Text(code.text.take(80), color = Color.White, fontSize = 14.sp, maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        Chip(if (code.url != null) "Open" else "Copy", true) {
            if (code.url != null) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else copyText(ctx, code.wifiPass ?: code.text)
            onClose()
        }
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Camera", text))
    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun MoreModes(modes: List<Mode>, current: Mode, onClose: () -> Unit, onPick: (Mode) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("More modes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            modes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { m ->
                        Box(
                            Modifier.padding(6.dp).width(104.dp).height(64.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (m == current) Amber else Color.White.copy(alpha = 0.12f))
                                .clickable { onPick(m) },
                            contentAlignment = Alignment.Center
                        ) { Text(m.label, color = if (m == current) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
