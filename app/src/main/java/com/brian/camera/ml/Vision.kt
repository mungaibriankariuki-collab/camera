package com.brian.camera.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** All the on-device ML Kit pieces: scene optimizer, QR, text, translate, identify, faces. */
object Vision {
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()) }
    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val faces by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
    }
    private val langId by lazy { LanguageIdentification.getClient() }

    data class Label(val text: String, val confidence: Float)
    data class Code(val text: String, val kind: String, val url: String?, val wifiSsid: String?, val wifiPass: String?, val box: Rect?)
    data class TextBlock(val text: String, val box: Rect?)

    suspend fun labels(bmp: Bitmap): List<Label> =
        labeler.process(InputImage.fromBitmap(bmp, 0)).await().map { Label(it.text, it.confidence) }

    suspend fun codes(bmp: Bitmap): List<Code> =
        barcodes.process(InputImage.fromBitmap(bmp, 0)).await().mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            val kind = when (b.valueType) {
                Barcode.TYPE_URL -> "Link"
                Barcode.TYPE_WIFI -> "Wi-Fi"
                Barcode.TYPE_PHONE -> "Phone"
                Barcode.TYPE_EMAIL -> "Email"
                Barcode.TYPE_SMS -> "SMS"
                Barcode.TYPE_CONTACT_INFO -> "Contact"
                Barcode.TYPE_GEO -> "Location"
                Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> "Product"
                else -> "Text"
            }
            Code(b.displayValue ?: raw, kind, b.url?.url ?: if (raw.startsWith("http")) raw else null, b.wifi?.ssid, b.wifi?.password, b.boundingBox)
        }

    suspend fun read(bmp: Bitmap): List<TextBlock> =
        text.process(InputImage.fromBitmap(bmp, 0)).await().textBlocks.map { TextBlock(it.text, it.boundingBox) }

    suspend fun faces(bmp: Bitmap): List<Rect> =
        faces.process(InputImage.fromBitmap(bmp, 0)).await().map { it.boundingBox }

    /** Translates [src] into [target] (a BCP-47 code like "en" or "sw"), downloading models on first use. */
    suspend fun translate(src: String, target: String): String {
        val tag = langId.identifyLanguage(src).await()
        val from = TranslateLanguage.fromLanguageTag(if (tag == "und") "en" else tag) ?: TranslateLanguage.ENGLISH
        val to = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ENGLISH
        if (from == to) return src
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            t.downloadModelIfNeeded().await()
            return t.translate(src).await()
        } finally {
            t.close()
        }
    }

    /** Maps ML Kit labels to a Samsung-style scene name. */
    fun scene(labels: List<Label>, brightness: Int): String? {
        if (brightness < 45) return "Night"
        val set = labels.associate { it.text.lowercase() to it.confidence }
        fun has(vararg k: String) = k.any { (set[it] ?: 0f) > 0.6f }
        return when {
            has("food", "fast food", "cuisine", "dessert", "cake", "pizza", "fruit", "vegetable", "bread", "meal") -> "Food"
            has("dog", "cat", "pet", "bird", "horse") -> "Pets"
            has("flower", "petal") -> "Flowers"
            has("sunset", "sunrise", "dusk") -> "Sunset"
            has("snow", "ice") -> "Snow"
            has("beach", "sea", "ocean", "lake", "river") -> "Beach"
            has("sky", "cloud") -> "Sky"
            has("plant", "tree", "grass", "garden", "leaf", "forest") -> "Greenery"
            has("paper", "poster", "menu", "receipt", "document", "handwriting", "text") -> "Text"
            has("selfie", "smile", "person", "face", "beard", "hair") -> "People"
            else -> null
        }
    }

    fun brightness(bmp: Bitmap): Int {
        val w = 32
        val h = 32
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return px.sumOf { ((it shr 16 and 255) * 77 + (it shr 8 and 255) * 150 + (it and 255) * 29) shr 8 } / px.size
    }
}
