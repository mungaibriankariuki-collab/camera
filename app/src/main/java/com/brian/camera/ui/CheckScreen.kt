package com.brian.camera.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brian.camera.camera.CameraEngine

/** Camera Check: exactly what this phone exposes to apps, with a summary of which modes are on. */
@Composable
fun CheckScreen(e: CameraEngine, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = e.caps
    val header = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.HARDWARE}"
    val summary = if (c == null) "Camera not opened yet" else buildString {
        appendLine("Pro: ISO/shutter ${if (c.manualSensor) "✓" else if (c.isoShutter) "try (not officially supported)" else "✗"} · manual focus ${ok(c.minFocusDist > 0f)} · RAW ${ok(c.raw)}")
        appendLine("Ultra-wide: ${ok(c.hasWide)}   Slow motion: ${if (c.slowMoFps > 0) "${c.slowMoFps} fps" else if (c.hsFps >= 60) "${c.hsFps} fps (${c.hsSize})" else "✗"}")
        appendLine("Video stabilization: ${ok(c.stabilization)}   Director's view: ${ok(c.concurrent != null)}")
    }
    val full = "$header\n\n$summary\n${c?.report ?: ""}"
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Camera Check", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip("Copy", true) { copyText(ctx, full) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            Text(header, color = Color.Gray, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).background(Color(0xFF1E1E1E)).padding(12.dp)) {
                Text(summary, color = Color.White, fontSize = 15.sp)
            }
            SelectionContainer { Text(c?.report ?: "", color = Color(0xFFB0BEC5), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

private fun ok(b: Boolean) = if (b) "✓" else "✗"

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Camera needs permission", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Camera and microphone to shoot photos and videos, and photos/videos to show your gallery.", color = Color.Gray, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp))
        Chip("Allow", true) { onAsk() }
    }
}
