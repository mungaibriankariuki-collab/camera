package com.brian.camera.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brian.camera.BuildConfig
import com.brian.camera.MainActivity

@Composable
fun SettingsScreen(act: MainActivity, onBack: () -> Unit, onCheck: () -> Unit) {
    val p = act.prefs
    val caps = act.engine.caps
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Camera settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Section("Intelligent features")
            Toggle("Scene optimizer", "Detects food, sky, plants, pets, text and night and tunes the photo", p.sceneOptimizer) { p.sceneOptimizer = it }
            Toggle("Shot suggestions", "Composition and lighting tips, face framing", p.shotSuggestions) { p.shotSuggestions = it }
            Toggle("Scan QR codes", "In Photo mode", p.scanQr) { p.scanQr = it }

            Section("Pictures")
            Choice("Photo processing", listOf("Detailed", "Fast"), p.processing) { p.processing = it }
            Note(if (p.processing == "Detailed") "Detailed: stacks 4 frames, then sharpens. Takes about 2 to 4 s, much cleaner and crisper." else "Fast: one frame straight from the camera.")
            Toggle("High efficiency pictures (HEIF)", "Smaller files; location tags aren't kept", p.heif) { p.heif = it }
            Toggle("Watermark", "Adds text and the date to photos", p.watermark) { p.watermark = it }
            if (p.watermark) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1E1E1E)).padding(12.dp)) {
                    BasicTextField(p.watermarkText, { p.watermarkText = it }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Amber))
                }
            }
            Toggle("Save selfies as previewed", "Mirror front-camera photos", p.mirrorSelfie) { p.mirrorSelfie = it }

            Section("Videos")
            Choice("Video size", caps?.videoQualities?.ifEmpty { null } ?: listOf("UHD", "FHD", "HD", "SD"), p.videoQuality) { p.videoQuality = it }
            if (caps?.stabilization == true) Toggle("Video stabilization", "Steadier video (slight crop)", p.stabilization) { p.stabilization = it }
            else Note("Video stabilization: not offered to apps on this phone (the main camera's OIS still works)")
            Toggle("Record audio", null, p.recordAudio) { p.recordAudio = it }

            Section("General")
            Toggle("Grid lines", null, p.grid > 0) { p.grid = if (it) 3 else 0 }
            if (p.grid > 0) Choice("Grid", listOf("3x3", "4x4"), "${p.grid}x${p.grid}") { p.grid = it.first().digitToInt() }
            Toggle("Level", "Shows when the phone is straight", p.level) { p.level = it }
            Toggle("Location tags", "Add where photos were taken", p.location) {
                p.location = it
                if (it) act.askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else act.engine.updateLocation()
            }
            Toggle("Remove location when sharing", null, p.stripLocationOnShare) { p.stripLocationOnShare = it }
            Choice("Volume keys", listOf("Shutter", "Zoom", "Volume"), p.volumeKey) { p.volumeKey = it }
            Toggle("Show palm", "Hold up your palm to the front camera to start a 2 s timer", p.palmGesture) { p.palmGesture = it }
            Toggle("Voice control", "Say \"cheese\", \"smile\", \"capture\" or \"record video\"", p.voiceControl) { p.voiceControl = it }
            Toggle("Swipe up/down to switch cameras", null, p.swipeToSwitch) { p.swipeToSwitch = it }
            Toggle("Shutter sound", null, p.shutterSound) { p.shutterSound = it }
            Toggle("Keep last mode", "Open in the mode you used last", p.keepLastMode) { p.keepLastMode = it }

            Section("About")
            Row(Modifier.fillMaxWidth().clickable { onCheck() }.padding(vertical = 14.dp)) {
                Column {
                    Text("Camera Check", color = Color.White, fontSize = 16.sp)
                    Text("What this phone lets the app use", color = Color.Gray, fontSize = 13.sp)
                }
            }
            Text("Camera ${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Note(t: String) {
    Text(t, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            sub?.let { Text(it, color = Color.Gray, fontSize = 13.sp) }
        }
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun Choice(title: String, options: List<String>, value: String, set: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.Start) {
            options.forEach { o -> Chip(o, o == value) { set(o) } }
        }
    }
}
