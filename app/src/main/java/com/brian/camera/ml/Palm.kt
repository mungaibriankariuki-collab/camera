package com.brian.camera.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/** Spots an open palm facing the front camera (MediaPipe gesture recognizer). */
class Palm(ctx: Context) {
    private val rec: GestureRecognizer? = runCatching {
        val opts = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .build()
        GestureRecognizer.createFromOptions(ctx, opts)
    }.getOrNull()

    val available get() = rec != null

    fun isOpenPalm(bmp: Bitmap): Boolean {
        val r = rec ?: return false
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val res = runCatching { r.recognize(BitmapImageBuilder(argb).build()) }.getOrNull() ?: return false
        return res.gestures().any { hand -> hand.any { it.categoryName() == "Open_Palm" && it.score() > 0.6f } }
    }

    fun close() { runCatching { rec?.close() } }
}
