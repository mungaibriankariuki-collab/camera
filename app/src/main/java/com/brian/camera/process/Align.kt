package com.brian.camera.process

import kotlin.math.abs

/** Finds how far one frame moved relative to another (pure translation), coarse to fine. */
object Align {

    /**
     * Best (dx, dy) such that b(x + dx, y + dy) ≈ a(x, y), searching ±[range] px.
     * a and b are luma arrays of size w*h. Samples every [step] px for speed.
     */
    fun shift(a: IntArray, b: IntArray, w: Int, h: Int, range: Int, cx: Int = 0, cy: Int = 0, step: Int = 2, rangeY: Int = range): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        val m = range + maxOf(abs(cx), abs(cy)) + 1
        val my = rangeY + maxOf(abs(cx), abs(cy)) + 1
        for (dy in cy - rangeY..cy + rangeY) for (dx in cx - range..cx + range) {
            var sum = 0L
            var n = 0
            var y = my
            while (y < h - my) {
                val yb = y + dy
                if (yb in 0 until h) {
                    var x = m
                    val ra = y * w
                    val rb = yb * w
                    while (x < w - m) {
                        val xb = x + dx
                        if (xb in 0 until w) {
                            sum += abs(a[ra + x] - b[rb + xb])
                            n++
                        }
                        x += step
                    }
                }
                y += step
            }
            if (n > 0) {
                val score = sum * 1024 / n
                if (score < best) { best = score; bx = dx; by = dy }
            }
        }
        return bx to by
    }

    /** Two-level search on full-size luma via a 1/4 pyramid. Returns shift in full pixels. */
    fun pyramid(a: IntArray, b: IntArray, w: Int, h: Int, maxShift: Int): Pair<Int, Int> {
        val sw = w / 4
        val sh = h / 4
        val sa = down(a, w, h, 4)
        val sb = down(b, w, h, 4)
        val (cx, cy) = shift(sa, sb, sw, sh, (maxShift / 4).coerceAtLeast(2), step = 1)
        return shift(a, b, w, h, 3, cx * 4, cy * 4, step = 3)
    }

    fun down(src: IntArray, w: Int, h: Int, f: Int): IntArray {
        val sw = w / f
        val sh = h / f
        val out = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (j in 0 until f) for (i in 0 until f) s += src[(y * f + j) * w + x * f + i]
            out[y * sw + x] = s / (f * f)
        }
        return out
    }
}
