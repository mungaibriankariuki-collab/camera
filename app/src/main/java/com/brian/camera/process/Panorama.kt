package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.abs

/** Left-to-right sweep panorama: tracks the camera's motion and lays down centre strips. */
class Panorama(private val maxWidth: Int = 7000) {
    private var canvas: Bitmap? = null
    private var c: Canvas? = null
    private var fw = 0
    private var fh = 0
    private var margin = 0
    private var ox = 0
    private var oy = 0
    private var top = 0
    private var bottom = Int.MAX_VALUE
    private var prevLuma: IntArray? = null
    private var last: Bitmap? = null

    /** 0..1 how full the panorama is. */
    var progress = 0f
        private set
    var tooFast = false
        private set
    var full = false
        private set

    fun add(frame: Bitmap) {
        if (full) return
        val lw = frame.width / 4
        val lh = frame.height / 4
        val l = Img.luma(frame, lw, lh)
        if (canvas == null) {
            fw = frame.width; fh = frame.height
            margin = fh / 6
            canvas = Bitmap.createBitmap(maxWidth, fh + 2 * margin, Bitmap.Config.ARGB_8888)
            c = Canvas(canvas!!)
            ox = 0; oy = margin
            top = oy; bottom = oy + fh
            c!!.drawBitmap(frame, Rect(0, 0, fw / 2, fh), Rect(0, oy, fw / 2, oy + fh), null)
            prevLuma = l; last = frame
            return
        }
        if (frame.width != fw || frame.height != fh) return
        val (dx, dy) = Align.shift(prevLuma!!, l, lw, lh, lw / 5, step = 2, rangeY = 6)
        val adv = -dx * 4
        tooFast = abs(adv) >= fw / 5 - 8
        if (adv < 6 || tooFast) return
        val cx = fw / 2
        val nx = ox + adv
        val ny = oy - dy * 4
        if (nx + fw > maxWidth) { full = true; return }
        c!!.drawBitmap(frame, Rect(cx - adv, 0, cx, fh), Rect(ox + cx, ny, nx + cx, ny + fh), null)
        ox = nx; oy = ny
        top = maxOf(top, oy); bottom = minOf(bottom, oy + fh)
        prevLuma = l; last = frame
        progress = (ox + fw).toFloat() / maxWidth
    }

    /** Finished panorama, or null if the sweep was too short. */
    fun finish(): Bitmap? {
        val cv = canvas ?: return null
        val lf = last ?: return null
        val cx = fw / 2
        c!!.drawBitmap(lf, Rect(cx, 0, fw, fh), Rect(ox + cx, oy, ox + fw, oy + fh), null)
        val width = ox + fw
        if (width < fw * 1.4f || bottom - top < fh / 2) return null
        val out = Bitmap.createBitmap(cv, 0, top, width, bottom - top)
        canvas = null
        return out
    }
}
