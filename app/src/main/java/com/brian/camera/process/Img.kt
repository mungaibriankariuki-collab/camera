package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Small bitmap helpers shared by the photo pipeline. */
object Img {

    /** Decodes a JPEG no bigger than [maxPixels], upright (EXIF rotation applied). */
    fun decodeUpright(file: File, maxPixels: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        })
        val orient = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        return if (m.isIdentity) bmp else transform(bmp, m)
    }

    fun transform(src: Bitmap, m: Matrix): Bitmap {
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun rotate(src: Bitmap, deg: Int): Bitmap = if (deg % 360 == 0) src else transform(src, Matrix().apply { postRotate(deg.toFloat()) })

    fun mirror(src: Bitmap): Bitmap = transform(src, Matrix().apply { postScale(-1f, 1f) })

    /** Centre-crops to width:height = [aw]:[ah] (portrait orientation). */
    fun cropRatio(src: Bitmap, aw: Int, ah: Int): Bitmap {
        val target = aw.toFloat() / ah
        val cur = src.width.toFloat() / src.height
        if (kotlin.math.abs(cur - target) < 0.01f) return src
        val (w, h) = if (cur > target) Pair((src.height * target).toInt(), src.height) else Pair(src.width, (src.width / target).toInt())
        val out = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun applyMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Fast, soft blur: shrink then grow twice. [factor] is the shrink amount (bigger = blurrier). */
    fun cheapBlur(src: Bitmap, factor: Int): Bitmap {
        val f = max(2, factor)
        val sw = max(1, src.width / f)
        val sh = max(1, src.height / f)
        val tiny = Bitmap.createScaledBitmap(src, sw, sh, true)
        val tiny2 = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, max(1, sw / 2), max(1, sh / 2), true), sw, sh, true)
        return Bitmap.createScaledBitmap(tiny2, src.width, src.height, true)
    }

    /** Luma at a reduced size, for alignment and sharpness. */
    fun luma(src: Bitmap, w: Int, h: Int): IntArray {
        val small = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8
        }
        return px
    }

    /** Variance of the Laplacian: higher = sharper. */
    fun sharpness(src: Bitmap): Double {
        val w = min(480, src.width)
        val h = max(1, src.height * w / src.width)
        val l = luma(src, w, h)
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = (4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]).toDouble()
            sum += v; sq += v * v; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sq / n - mean * mean
    }

    /**
     * Detail pass: unsharp mask for fine edges plus a wide, gentle local-contrast ("clarity") boost.
     * A noise gate skips tiny differences so grain isn't sharpened. Works in place, row by row.
     */
    fun enhance(src: Bitmap, sharpen: Float, clarity: Float): Bitmap {
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val fine = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(out, max(1, w / 2), max(1, h / 2), true), w, h, true)
        val wide = cheapBlur(out, 24)
        val rs = IntArray(w); val rf = IntArray(w); val rw = IntArray(w)
        val gate = 3
        for (y in 0 until h) {
            out.getPixels(rs, 0, w, 0, y, w, 1)
            fine.getPixels(rf, 0, w, 0, y, w, 1)
            wide.getPixels(rw, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val s = rs[x]; val f = rf[x]; val g = rw[x]
                var r = 0; var gr = 0; var b = 0
                for (ch in 0..2) {
                    val sh = 16 - ch * 8
                    val sv = s shr sh and 255
                    val dv = sv - (f shr sh and 255)
                    val wv = sv - (g shr sh and 255)
                    val d = if (dv > gate || dv < -gate) dv * sharpen else 0f
                    // Less clarity near pure black/white so highlights and shadows don't clip.
                    val k = 1f - kotlin.math.abs(sv - 128) / 170f
                    val v = clamp((sv + d + wv * clarity * k).toInt())
                    when (ch) { 0 -> r = v; 1 -> gr = v; else -> b = v }
                }
                rs[x] = (s and -0x1000000) or (r shl 16) or (gr shl 8) or b
            }
            out.setPixels(rs, 0, w, 0, y, w, 1)
        }
        fine.recycle(); wide.recycle()
        return out
    }

    /** Crops the centre by [factor] and scales it back up (digital zoom past the camera's limit). */
    fun digitalZoom(src: Bitmap, factor: Float): Bitmap {
        if (factor <= 1.01f) return src
        val cw = (src.width / factor).toInt()
        val ch = (src.height / factor).toInt()
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        return Bitmap.createScaledBitmap(crop, src.width, src.height, true).copy(Bitmap.Config.ARGB_8888, true)
    }

    fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
