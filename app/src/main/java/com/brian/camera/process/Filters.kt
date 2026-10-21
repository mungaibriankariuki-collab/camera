package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import kotlin.math.sqrt

/** Colour filters as 4x5 ColorMatrix values: used live on the preview and baked into photos. */
object Filters {

    val names = listOf("None", "Vivid", "Warm", "Cool", "Film", "Fade", "Mono", "Noir", "Sepia", "Teal", "Pastel", "Retro")

    fun matrix(name: String, my: List<Pair<String, FloatArray>> = emptyList()): ColorMatrix? {
        my.firstOrNull { it.first == name }?.let { return ColorMatrix(it.second) }
        return when (name) {
            "Vivid" -> sat(1.45f).also { it.postConcat(contrast(1.08f)) }
            "Warm" -> scale(1.10f, 1.02f, 0.88f).also { it.postConcat(sat(1.1f)) }
            "Cool" -> scale(0.90f, 1.0f, 1.12f)
            "Film" -> sat(0.85f).also { it.postConcat(contrast(0.92f)); it.postConcat(offset(10f, 6f, -4f)) }
            "Fade" -> contrast(0.78f).also { it.postConcat(sat(0.8f)); it.postConcat(offset(14f, 14f, 14f)) }
            "Mono" -> sat(0f)
            "Noir" -> sat(0f).also { it.postConcat(contrast(1.35f)) }
            "Sepia" -> ColorMatrix().apply { setSaturation(0f); postConcat(scale(1.07f, 0.95f, 0.78f)) }
            "Teal" -> ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, 6f,
                0f, 1.0f, 0.05f, 0f, 0f,
                0f, 0.1f, 1.05f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f)).also { it.postConcat(contrast(1.1f)) }
            "Pastel" -> sat(0.7f).also { it.postConcat(offset(18f, 16f, 22f)); it.postConcat(contrast(0.9f)) }
            "Retro" -> scale(1.05f, 0.98f, 0.85f).also { it.postConcat(contrast(1.15f)); it.postConcat(sat(0.75f)) }
            else -> null
        }
    }

    // ---- scene optimizer looks ----
    fun scene(scene: String): ColorMatrix? = when (scene) {
        "Food" -> sat(1.3f).also { it.postConcat(scale(1.06f, 1.02f, 0.94f)) }
        "Sky" -> ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            -0.05f, 0f, 1.15f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)).also { it.postConcat(sat(1.15f)) }
        "Greenery", "Flowers" -> sat(1.25f).also { it.postConcat(scale(1f, 1.06f, 1f)) }
        "Sunset" -> scale(1.1f, 1.0f, 0.9f).also { it.postConcat(sat(1.2f)) }
        "Night" -> offset(8f, 8f, 10f).also { it.postConcat(contrast(1.05f)) }
        "Text" -> contrast(1.3f).also { it.postConcat(sat(0.6f)) }
        "Pets", "People" -> sat(1.08f)
        "Snow" -> offset(10f, 10f, 12f)
        "Beach" -> sat(1.2f).also { it.postConcat(offset(4f, 4f, 8f)) }
        else -> null
    }

    fun food(): ColorMatrix = scene("Food")!!

    // ---- My Filters: copy a photo's look ----
    /** Builds a matrix that pushes an average photo toward [ref]'s colour balance, contrast and saturation. */
    fun fromPhoto(ref: Bitmap): FloatArray {
        val w = 160
        val h = (ref.height * w / ref.width).coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(ref, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        val mean = DoubleArray(3)
        val sq = DoubleArray(3)
        var satSum = 0.0
        for (c in px) {
            val r = (c shr 16 and 255).toDouble(); val g = (c shr 8 and 255).toDouble(); val b = (c and 255).toDouble()
            mean[0] += r; mean[1] += g; mean[2] += b
            sq[0] += r * r; sq[1] += g * g; sq[2] += b * b
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            satSum += if (mx > 0) (mx - mn) / mx else 0.0
        }
        val n = px.size.toDouble()
        val std = DoubleArray(3) { sqrt((sq[it] / n - (mean[it] / n) * (mean[it] / n)).coerceAtLeast(1.0)) }
        for (i in 0..2) mean[i] /= n
        val satAvg = satSum / n
        // Typical photo: mean 118, std 62, saturation 0.32.
        val m = ColorMatrix()
        m.postConcat(sat((satAvg / 0.32).toFloat().coerceIn(0f, 1.8f)))
        val k = FloatArray(3) { (std[it] / 62.0).toFloat().coerceIn(0.6f, 1.5f) }
        val off = FloatArray(3) { (mean[it] - 118.0 * k[it]).toFloat().coerceIn(-60f, 60f) }
        m.postConcat(ColorMatrix(floatArrayOf(
            k[0], 0f, 0f, 0f, off[0],
            0f, k[1], 0f, 0f, off[1],
            0f, 0f, k[2], 0f, off[2],
            0f, 0f, 0f, 1f, 0f)))
        return m.array.copyOf()
    }

    // ---- building blocks ----
    fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
    fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
    fun offset(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f))
    fun contrast(c: Float): ColorMatrix {
        val t = 128f * (1f - c)
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f))
    }
}
