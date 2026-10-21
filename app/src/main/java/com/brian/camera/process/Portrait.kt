package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.ByteOrder

/** Portrait: cut the person out with ML Kit selfie segmentation, then treat the background. */
object Portrait {
    val effects = listOf("Blur", "Big circle", "Studio", "Stage", "Stage mono", "High-key mono", "Mono", "Color point")

    private val stream by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())
    }
    private val single by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    /** Live preview frame (small). Returns null when nobody is found. */
    suspend fun live(frame: Bitmap, effect: String, strength: Int): Bitmap? {
        val mask = stream.process(InputImage.fromBitmap(frame, 0)).await()
        val alpha = maskBitmap(mask) ?: return null
        return compose(frame, alpha, effect, strength)
    }

    /** Full photo. Segments a ~512 px copy, then scales the mask up. */
    suspend fun apply(photo: Bitmap, effect: String, strength: Int): Bitmap {
        val w = 512
        val h = (photo.height * w / photo.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val mask = single.process(InputImage.fromBitmap(small, 0)).await()
        val alpha = maskBitmap(mask) ?: return photo
        return compose(photo, alpha, effect, strength)
    }

    /** Mask confidences → an ARGB bitmap whose alpha is "person" (with a soft edge). */
    private fun maskBitmap(mask: SegmentationMask): Bitmap? {
        val buf = mask.buffer.order(ByteOrder.nativeOrder())
        buf.rewind()
        val n = mask.width * mask.height
        val px = IntArray(n)
        var person = 0
        for (i in 0 until n) {
            val c = buf.float
            val a = smooth(c)
            if (a > 128) person++
            px[i] = (a shl 24) or 0xFFFFFF
        }
        if (person < n / 100) return null
        val bmp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, mask.width, 0, 0, mask.width, mask.height)
        return bmp
    }

    private fun smooth(c: Float): Int {
        val t = ((c - 0.35f) / 0.4f).coerceIn(0f, 1f)
        return (t * t * (3 - 2 * t) * 255).toInt()
    }

    private fun compose(src: Bitmap, alphaSmall: Bitmap, effect: String, strength: Int): Bitmap {
        val w = src.width
        val h = src.height
        val alpha = Bitmap.createScaledBitmap(alphaSmall, w, h, true)
        val shrink = 4 + strength * 3 * w / 1000
        val bg: Bitmap = when (effect) {
            "Studio" -> Img.applyMatrix(Img.cheapBlur(src, shrink / 2 + 2), Filters.offset(-70f, -70f, -70f).also { it.postConcat(Filters.contrast(1.1f)) })
            "Mono" -> Img.applyMatrix(Img.cheapBlur(src, shrink), Filters.sat(0f))
            "Stage", "Stage mono" -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
            "High-key mono" -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
            "Color point" -> Img.applyMatrix(src, Filters.sat(0f))
            "Big circle" -> bokeh(src, shrink)
            else -> Img.cheapBlur(src, shrink)
        }.copy(Bitmap.Config.ARGB_8888, true)

        // Person = original pixels masked by alpha.
        val fg = when (effect) {
            "Stage mono" -> Img.applyMatrix(src, Filters.sat(0f).also { it.postConcat(Filters.contrast(1.15f)) })
            "High-key mono" -> Img.applyMatrix(src, Filters.sat(0f).also { it.postConcat(Filters.offset(18f, 18f, 18f)); it.postConcat(Filters.contrast(1.1f)) })
            else -> src.copy(Bitmap.Config.ARGB_8888, true)
        }
        Canvas(fg).drawBitmap(alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        val c = Canvas(bg)
        if (effect == "Studio" || effect == "Stage") {
            // Gentle key light on the subject.
            c.drawBitmap(fg, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(Filters.offset(10f, 10f, 10f)) })
        } else {
            c.drawBitmap(fg, 0f, 0f, null)
        }
        return bg
    }

    /** Blur plus bright round highlights, for a lens-like look. */
    private fun bokeh(src: Bitmap, shrink: Int): Bitmap {
        val blurred = Img.cheapBlur(src, shrink).copy(Bitmap.Config.ARGB_8888, true)
        val w = 64
        val h = (src.height * w / src.width).coerceAtLeast(1)
        val l = Img.luma(src, w, h)
        val c = Canvas(blurred)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = src.width / 40f
        val step = src.width.toFloat() / w
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val v = l[y * w + x]
            if (v > 215) {
                val col = src.getPixel((x * step).toInt().coerceIn(0, src.width - 1), (y * step).toInt().coerceIn(0, src.height - 1))
                p.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(x * step, y * step, r, p)
            }
        }
        return blurred
    }
}
