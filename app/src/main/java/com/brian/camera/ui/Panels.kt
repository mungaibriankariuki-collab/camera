package com.brian.camera.ui

import android.content.Intent
import android.graphics.ImageDecoder
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brian.camera.MainActivity
import com.brian.camera.Prefs
import com.brian.camera.camera.CameraEngine
import com.brian.camera.camera.Caps
import com.brian.camera.camera.Shooter
import com.brian.camera.ml.Vision
import com.brian.camera.process.Filters
import com.brian.camera.process.Portrait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilterStrip(act: MainActivity, onClose: () -> Unit) {
    val prefs = act.prefs
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.Default) {
            val bmp = runCatching { decodeUri(act, uri) }.getOrNull() ?: return@launch
            val m = Filters.fromPhoto(bmp)
            withContext(Dispatchers.Main) {
                val list = prefs.myFilters().toMutableList()
                val name = "My ${list.size + 1}"
                list += name to m
                prefs.saveMyFilters(list)
                prefs.filter = name
            }
        }
    }
    val mine = prefs.myFilters()
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 8.dp)) {
            (Filters.names + mine.map { it.first }).forEach { n -> Chip(n, prefs.filter == n) { prefs.filter = n } }
            Chip("+ My filter", false) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        if (mine.any { it.first == prefs.filter }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Chip("Delete ${prefs.filter}", false) {
                    prefs.saveMyFilters(mine.filter { it.first != prefs.filter })
                    prefs.filter = "None"
                }
                Chip("Done", true) { onClose() }
            }
        }
    }
}

fun decodeUri(act: MainActivity, uri: Uri): android.graphics.Bitmap =
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(act.contentResolver, uri)) { d, info, _ ->
            val maxSide = 1600
            val sz = info.size
            val scale = maxOf(sz.width, sz.height).toFloat() / maxSide
            if (scale > 1f) d.setTargetSize((sz.width / scale).toInt(), (sz.height / scale).toInt())
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(act.contentResolver, uri)
    }

@Composable
fun PortraitPanel(prefs: Prefs) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
            Portrait.effects.forEach { fx -> Chip(fx, prefs.portraitEffect == fx) { prefs.portraitEffect = fx } }
        }
        if (prefs.portraitEffect != "Color point") {
            Row(Modifier.width(280.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Strength", color = Color.White, fontSize = 12.sp)
                Slider(prefs.portraitStrength.toFloat(), { prefs.portraitStrength = Math.round(it) }, valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                Text("${prefs.portraitStrength}", color = Amber, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun FoodPanel(s: Shooter) {
    Row(Modifier.width(280.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Blur area", color = Color.White, fontSize = 12.sp)
        Slider(s.foodRadius, { s.foodRadius = it }, valueRange = 0.12f..0.48f, modifier = Modifier.weight(1f))
    }
}

@Composable
fun LensPanel(prefs: Prefs) {
    OptionRow(listOf("Text", "QR", "Identify"), prefs.lensTool) { prefs.lensTool = it }
}

private val isoStops = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
private val shutterStops = listOf(
    1_000_000_000L / 4000, 1_000_000_000L / 2000, 1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
    1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30, 1_000_000_000L / 15, 1_000_000_000L / 8,
    1_000_000_000L / 4, 1_000_000_000L / 2, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L
)
private val wbNames = listOf(
    CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto",
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
    CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade",
    CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
)

@Composable
fun ProPanel(e: CameraEngine, peaking: Boolean, setPeaking: (Boolean) -> Unit) {
    val caps = e.caps
    var tab by remember { mutableStateOf("ISO") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val ignored = (e.proIso != null && e.liveIso > 0 && kotlin.math.abs(e.liveIso - e.proIso!!) > e.proIso!! / 4) ||
            (e.proShutter != null && e.liveShutter > 0 && kotlin.math.abs(e.liveShutter - e.proShutter!!) > e.proShutter!! / 4)
        if (ignored) Text("The camera is ignoring this manual setting", color = Color(0xFFFF8A80), fontSize = 12.sp)
        Text(
            "ISO ${e.liveIso}   ${Caps.fmtShutter(e.proShutter ?: e.liveShutter.coerceAtLeast(1))}   EV ${"%.1f".format(e.evIndex * e.evStep)}",
            color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp
        )
        val manual = caps?.isoShutter == true
        when (tab) {
            "ISO" -> if (manual) {
                val stops = isoStops.filter { caps?.isoRange?.contains(it) ?: true }
                ValueRow(listOf("A") + stops.map { "$it" }, e.proIso?.toString() ?: "A") {
                    e.proIso = it.toIntOrNull(); e.applyPro()
                }
            } else Text("This phone doesn't allow manual ISO", color = Color.Gray, fontSize = 12.sp)
            "Speed" -> if (manual) {
                val stops = shutterStops.filter { caps?.exposureRange?.contains(it) ?: true }
                ValueRow(listOf("A") + stops.map { Caps.fmtShutter(it) }, e.proShutter?.let { Caps.fmtShutter(it) } ?: "A") { v ->
                    e.proShutter = stops.firstOrNull { Caps.fmtShutter(it) == v }; e.applyPro()
                }
            } else Text("This phone doesn't allow manual shutter speed", color = Color.Gray, fontSize = 12.sp)
            "EV" -> if (e.evRange.last > 0) EvSlider(e.evIndex, e.evRange, Modifier) { e.setEv(it) }
            "Focus" -> if ((caps?.minFocusDist ?: 0f) > 0f) {
                Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("AF", e.proFocus == null) { e.proFocus = null; e.applyPro() }
                    Text("∞", color = Color.White)
                    Slider(e.proFocus ?: 0f, { e.proFocus = it; e.applyPro() }, valueRange = 0f..(caps?.minFocusDist ?: 10f), modifier = Modifier.weight(1f))
                    Text("✿", color = Color.White)
                }
            } else Text("Fixed focus on this camera", color = Color.Gray, fontSize = 12.sp)
            "WB" -> {
                val avail = wbNames.filter { (m, _) -> caps?.wbModes?.contains(m) ?: true }
                ValueRow(avail.map { it.second }, avail.firstOrNull { it.first == e.proWb }?.second ?: "Auto") { name ->
                    e.proWb = avail.first { it.second == name }.first; e.applyPro()
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp)) {
            listOf("ISO", "Speed", "EV", "Focus", "WB").forEach { t -> ModeLabel(t, tab == t) { tab = t } }
            if (caps?.raw == true) Chip("RAW", e.proRaw) { e.proRaw = !e.proRaw }
            Chip("Peaking", peaking) { setPeaking(!peaking) }
            Chip("Reset", false) { e.proIso = null; e.proShutter = null; e.proFocus = null; e.proWb = CaptureRequest.CONTROL_AWB_MODE_AUTO; e.setEv(0); e.applyPro() }
        }
    }
}

@Composable
private fun ValueRow(values: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
        values.forEach { v -> Chip(v, v == selected) { onPick(v) } }
    }
}

private val languages = listOf("en" to "English", "sw" to "Swahili", "fr" to "French", "es" to "Spanish", "ar" to "Arabic", "zh" to "Chinese", "de" to "German", "pt" to "Portuguese", "hi" to "Hindi")

@Composable
fun LensResultSheet(act: MainActivity, text: String, tool: String, onClose: () -> Unit) {
    val prefs = act.prefs
    val scope = rememberCoroutineScope()
    var translated by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (tool == "Identify") "What's in the picture" else if (tool == "QR") "Code" else "Text found", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("Close", false) { onClose() }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(text, color = Color.White, fontSize = 16.sp) }
                translated?.let {
                    Spacer(Modifier.height(16.dp))
                    Text("Translation", color = Amber, fontSize = 13.sp)
                    SelectionContainer { Text(it, color = Color.White, fontSize = 16.sp) }
                }
                if (working) Text("Translating… (first time downloads the language, ~30 MB)", color = Color.Gray, fontSize = 13.sp)
            }
            if (tool != "Identify") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    languages.forEach { (code, name) -> Chip(name, prefs.translateTo == code) { prefs.translateTo = code } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Chip("Copy", false) { copyText(act, translated ?: text) }
                Chip("Share", false) {
                    act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, translated ?: text), "Share"))
                }
                if (tool == "Identify") {
                    Chip("Search", true) {
                        val q = text.lineSequence().firstOrNull()?.substringBefore("  ") ?: text
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                    }
                } else {
                    Chip("Translate", true) {
                        working = true
                        scope.launch {
                            translated = runCatching { Vision.translate(text, prefs.translateTo) }.getOrElse { "Couldn't translate: ${it.message}" }
                            working = false
                        }
                    }
                }
            }
        }
    }
}
