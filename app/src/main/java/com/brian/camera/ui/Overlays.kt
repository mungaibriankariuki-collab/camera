package com.brian.camera.ui

import android.content.Context
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2

val Amber = Color(0xFFFFC107)

@Composable
fun GridOverlay(n: Int) {
    if (n < 2) return
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.45f)
        for (i in 1 until n) {
            val x = size.width * i / n
            val y = size.height * i / n
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.5f)
            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.5f)
        }
    }
}

/** Phone tilt (degrees, 0 = level), from the gravity sensor. */
@Composable
fun rememberRoll(): Float {
    val ctx = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]
                val r = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                roll = roll * 0.7f + r * 0.3f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    return roll
}

@Composable
fun LevelOverlay(roll: Float) {
    if (abs(roll) > 30f) return
    val level = abs(roll) < 1.2f
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val col = if (level) Amber else Color.White.copy(alpha = 0.8f)
        // Fixed side ticks, and a line that tilts with the phone.
        drawLine(Color.White.copy(alpha = 0.6f), Offset(cx - 150f, cy), Offset(cx - 110f, cy), 3f)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(cx + 110f, cy), Offset(cx + 150f, cy), 3f)
        rotate(-roll, Offset(cx, cy)) {
            drawLine(col, Offset(cx - 100f, cy), Offset(cx + 100f, cy), 4f)
        }
    }
}

@Composable
fun FocusRing(at: Offset?, locked: Boolean) {
    at ?: return
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(if (locked) Amber else Color.White, 70f, at, style = Stroke(4f))
        drawCircle(if (locked) Amber else Color.White, 6f, at)
    }
    if (locked) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text("AE/AF lock", color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.size(width = 120.dp, height = 24.dp))
    }
}

/** Draws boxes given in frame pixels over the viewfinder. */
@Composable
fun BoxesOverlay(boxes: List<Rect>, frameW: Int, color: Color = Amber) {
    if (boxes.isEmpty() || frameW == 0) return
    Canvas(Modifier.fillMaxSize()) {
        val s = size.width / frameW
        boxes.forEach { r ->
            drawRect(color, Offset(r.left * s, r.top * s), Size(r.width() * s, r.height() * s), style = Stroke(3f))
        }
    }
}

@Composable
fun Histogram(bins: IntArray?, modifier: Modifier) {
    bins ?: return
    Canvas(modifier) {
        drawRect(Color.Black.copy(alpha = 0.4f))
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = size.height * v / max
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * bw, size.height - h), Size(bw, h))
        }
    }
}

/** Food mode: the sharp circle the blur goes around. */
@Composable
fun FoodRing(x: Float, y: Float, r: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Color.White.copy(alpha = 0.9f), r * size.width, Offset(x * size.width, y * size.height), style = Stroke(3f))
    }
}
