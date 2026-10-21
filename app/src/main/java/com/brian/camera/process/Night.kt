package com.brian.camera.process

import android.graphics.Bitmap
import kotlin.math.pow

/**
 * Night mode: average several aligned frames (cuts noise roughly by √n), then lift the shadows.
 * Feed frames with [add], then call [finish].
 */
class Night(private val first: Bitmap) {
    private val w = first.width
    private val h = first.height
    private val r = ShortArray(w * h)
    private val g = ShortArray(w * h)
    private val b = ShortArray(w * h)
    private val count = ShortArray(w * h)
    private val lw = w / 4
    private val lh = h / 4
    private val refLuma = Img.luma(first, lw, lh)
    private val row = IntArray(w)
    var frames = 0
        private set

    init { accumulate(first, 0, 0) }

    fun add(frame: Bitmap) {
        if (frame.width != w || frame.height != h) return
        val l = Img.luma(frame, lw, lh)
        val (dx, dy) = Align.pyramid(refLuma, l, lw, lh, lw / 12)
        accumulate(frame, dx * 4, dy * 4)
    }

    private fun accumulate(f: Bitmap, dx: Int, dy: Int) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy !in 0 until h) continue
            f.getPixels(row, 0, w, 0, sy, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val sx = x + dx
                if (sx !in 0 until w) continue
                val c = row[sx]
                val i = base + x
                r[i] = (r[i] + (c shr 16 and 255)).toShort()
                g[i] = (g[i] + (c shr 8 and 255)).toShort()
                b[i] = (b[i] + (c and 255)).toShort()
                count[i] = (count[i] + 1).toShort()
            }
        }
        frames++
    }

    /** Averages and brightens. [lift] 0..1: how much to open the shadows. */
    fun finish(lift: Float = 0.6f, curve: Boolean = true): Bitmap {
        val gamma = 1f - 0.35f * lift
        val lut = IntArray(256) { v ->
            val t = (v / 255f).pow(gamma)
            // Soft S-curve keeps blacks from going grey.
            val s = if (curve) t * t * (3 - 2 * t) * 0.25f + t * 0.75f else t
            (s * 255).toInt().coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val line = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val i = base + x
                val n = count[i].toInt().coerceAtLeast(1)
                line[x] = (0xFF shl 24) or (lut[r[i] / n] shl 16) or (lut[g[i] / n] shl 8) or lut[b[i] / n]
            }
            out.setPixels(line, 0, w, 0, y, w, 1)
        }
        return out
    }
}
