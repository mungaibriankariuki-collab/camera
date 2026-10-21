package com.brian.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos and videos into DCIM/Camera so they show up in every gallery app. */
object Saver {
    const val FOLDER = "DCIM/Camera"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun imageValues(name: String, mime: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
    }

    fun videoValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
    }

    /** Encodes [bmp] (JPEG, or HEIF when asked and possible), adds EXIF, and publishes it. */
    fun saveBitmap(ctx: Context, bmp: Bitmap, prefix: String, heif: Boolean, location: Location?): Uri? {
        val tmpDir = File(ctx.cacheDir, "out").apply { mkdirs() }
        val name = "${prefix}_${stamp()}"
        if (heif) {
            val f = File(tmpDir, "$name.heic")
            val ok = runCatching {
                val w = HeifWriter.Builder(f.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(92).build()
                w.start(); w.addBitmap(bmp); w.stop(10_000); w.close()
                true
            }.getOrDefault(false)
            if (ok) return publishFile(ctx, f, "$name.heic", "image/heic").also { f.delete() }
        }
        val f = File(tmpDir, "$name.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        runCatching {
            val ex = ExifInterface(f)
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
            ex.setAttribute(ExifInterface.TAG_DATETIME, now)
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Camera")
            if (location != null) ex.setGpsInfo(location)
            ex.saveAttributes()
        }
        return publishFile(ctx, f, "$name.jpg", "image/jpeg").also { f.delete() }
    }

    /** Copies a finished file into the shared gallery folder. */
    fun publishFile(ctx: Context, file: File, displayName: String, mime: String): Uri? {
        val video = mime.startsWith("video")
        if (Build.VERSION.SDK_INT >= 29) {
            val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cv = (if (video) videoValues(displayName) else imageValues(displayName, mime)).apply {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(coll, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }
        val dest = File(dir, displayName)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest)
    }

    /** Saves a PDF (document scans) into Documents/Scans. */
    fun savePdf(ctx: Context, src: Uri): Uri? {
        val name = "Scan_${stamp()}.pdf"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Scans")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(out) } }
            return uri
        }
        return null
    }
}
