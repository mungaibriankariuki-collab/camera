package com.brian.camera.process

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Smart HDR: exposure fusion of an under-, normal- and over-exposed frame.
 * Each pixel takes most from whichever frame shows it best (closest to mid-grey); the weight maps
 * are computed small and blurred so there are no halos, then the frames are blended at full size.
 */
object Hdr {
    private const val F = 8 // weight-map downscale

    /** Clipped-highlight and crushed-shadow fractions of a preview frame: decides Auto HDR. */
    fun needsHdr(frame: Bitmap): Boolean {
        val w = 96
        val h = max(1, frame.height * w / frame.width)
        val l = Img.luma(frame, w, h)
        val bright = l.count { it >= 248 }.toFloat() / l.size
        val dark = l.count { it <= 10 }.toFloat() / l.size
        return bright > 0.03f || (dark > 0.12f && bright > 0.005f)
    }

    fun fuse(under: Bitmap, normal: Bitmap, over: Bitmap): Bitmap {
        val w = normal.width
        val h = normal.height
        val frames = listOf(under, normal, over)
        // Align the brackets to the normal frame, comparing brightness-matched luma.
        val shifts = frames.map { f -> if (f === normal) 0 to 0 else align(normal, f) }

        val sw = w / F
        val sh = h / F
        val weights = frames.mapIndexed { i, f ->
            val l = Img.luma(f, sw, sh)
            val (dx, dy) = shifts[i]
            FloatArray(sw * sh) { k ->
                // Sample the shifted position so the weights line up with the shifted pixels.
                val x = (k % sw + dx / F).coerceIn(0, sw - 1)
                val y = (k / sw + dy / F).coerceIn(0, sh - 1)
                val v = l[y * sw + x] / 255f - 0.5f
                exp(-(v * v) / (2 * 0.2f * 0.2f)) + 1e-3f
            }.also { blur(it, sw, sh, 6) }
        }
        // Normalize so the three weights add up to 1 everywhere.
        for (k in 0 until sw * sh) {
            val s = weights[0][k] + weights[1][k] + weights[2][k]
            for (wt in weights) wt[k] /= s
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = Array(3) { IntArray(w) }
        val line = IntArray(w)
        val xi = IntArray(w) { min(sw - 1, it / F) }
        val xi2 = IntArray(w) { min(sw - 1, it / F + 1) }
        val xf = FloatArray(w) { (it % F) / F.toFloat() }
        for (y in 0 until h) {
            for (i in 0..2) {
                val sy = (y + shifts[i].second).coerceIn(0, h - 1)
                frames[i].getPixels(rows[i], 0, w, 0, sy, w, 1)
            }
            val yi = min(sh - 1, y / F)
            val yi2 = min(sh - 1, y / F + 1)
            val yf = (y % F) / F.toFloat()
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f
                for (i in 0..2) {
                    val wt = weights[i]
                    val a = wt[yi * sw + xi[x]] * (1 - xf[x]) + wt[yi * sw + xi2[x]] * xf[x]
                    val c = wt[yi2 * sw + xi[x]] * (1 - xf[x]) + wt[yi2 * sw + xi2[x]] * xf[x]
                    val k = a * (1 - yf) + c * yf
                    val sx = (x + shifts[i].first).coerceIn(0, w - 1)
                    val p = rows[i][sx]
                    r += (p shr 16 and 255) * k; g += (p shr 8 and 255) * k; b += (p and 255) * k
                }
                line[x] = (0xFF shl 24) or (Img.clamp(r.toInt()) shl 16) or (Img.clamp(g.toInt()) shl 8) or Img.clamp(b.toInt())
            }
            out.setPixels(line, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Shift of [f] relative to [ref], 1 px accurate: coarse on small luma, refined on a full-size centre crop. */
    private fun align(ref: Bitmap, f: Bitmap): Pair<Int, Int> {
        val lw = ref.width / 4
        val lh = ref.height / 4
        val a = Img.luma(ref, lw, lh)
        val b = matchGain(a, Img.luma(f, lw, lh))
        val (cx, cy) = Align.pyramid(a, b, lw, lh, lw / 12)
        val size = min(768, min(ref.width, ref.height) / 2)
        val x0 = (ref.width - size) / 2
        val y0 = (ref.height - size) / 2
        val ca = Img.luma(Bitmap.createBitmap(ref, x0, y0, size, size), size, size)
        val cb = matchGain(ca, Img.luma(Bitmap.createBitmap(f, x0, y0, size, size), size, size))
        val maxS = size / 4
        return Align.shift(ca, cb, size, size, 3, (cx * 4).coerceIn(-maxS, maxS), (cy * 4).coerceIn(-maxS, maxS), step = 2)
    }

    private fun matchGain(ref: IntArray, x: IntArray): IntArray {
        val mr = ref.average().coerceAtLeast(1.0)
        val mx = x.average().coerceAtLeast(1.0)
        val k = mr / mx
        return IntArray(x.size) { (x[it] * k).toInt().coerceIn(0, 255) }
    }

    /** In-place box blur, [passes] times (≈ Gaussian). */
    private fun blur(a: FloatArray, w: Int, h: Int, passes: Int) {
        val tmp = FloatArray(a.size)
        repeat(passes) {
            for (y in 0 until h) for (x in 0 until w) {
                val l = a[y * w + max(0, x - 1)]; val r = a[y * w + min(w - 1, x + 1)]
                tmp[y * w + x] = (l + a[y * w + x] + r) / 3f
            }
            for (y in 0 until h) for (x in 0 until w) {
                val u = tmp[max(0, y - 1) * w + x]; val d = tmp[min(h - 1, y + 1) * w + x]
                a[y * w + x] = (u + tmp[y * w + x] + d) / 3f
            }
        }
    }
}
