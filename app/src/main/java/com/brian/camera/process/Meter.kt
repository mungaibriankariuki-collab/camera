package com.brian.camera.process

import android.graphics.Bitmap
import kotlin.math.abs

/** Pro mode helpers: luma histogram, focus peaking and zebra stripes from a preview frame. */
object Meter {
    fun histogram(frame: Bitmap, bins: Int = 64): IntArray {
        val w = 160
        val h = (frame.height * w / frame.width).coerceAtLeast(1)
        val l = Img.luma(frame, w, h)
        val out = IntArray(bins)
        for (v in l) out[v * bins / 256]++
        return out
    }

    /**
     * Overlay bitmap (same aspect as the frame): red where edges are sharp (in-focus),
     * diagonal stripes where the picture is blown out.
     */
    fun peakingAndZebra(frame: Bitmap, peaking: Boolean, zebra: Boolean): Bitmap? {
        if (!peaking && !zebra) return null
        val w = 240
        val h = (frame.height * w / frame.width).coerceAtLeast(2)
        val l = Img.luma(frame, w, h)
        val px = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (zebra && l[i] >= 245 && ((x + y) / 3) % 2 == 0) { px[i] = 0xB0FFFFFF.toInt(); continue }
            if (peaking) {
                val g = abs(l[i + 1] - l[i - 1]) + abs(l[i + w] - l[i - w])
                if (g > 70) px[i] = 0xFFFF2D2D.toInt()
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
