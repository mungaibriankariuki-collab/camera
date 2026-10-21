package com.brian.camera.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brian.camera.process.FrameEncoder
import com.brian.camera.process.Filters
import com.brian.camera.process.Img
import com.brian.camera.process.Night
import com.brian.camera.process.Panorama
import com.brian.camera.process.Portrait
import com.brian.camera.process.Align
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that happens when the shutter is pressed, per mode. */
class Shooter(private val e: CameraEngine) {
    private val ctx = e.ctx
    private val prefs = e.prefs
    private val sound = MediaActionSound()

    /** Scene picked by the scene optimizer, applied to the next photo (null = none). */
    var scene by mutableStateOf<String?>(null)
    var sceneOn by mutableStateOf(true)

    /** Food mode focus spot, as fractions of the frame. */
    var foodX by mutableFloatStateOf(0.5f)
    var foodY by mutableFloatStateOf(0.5f)
    var foodRadius by mutableFloatStateOf(0.3f)

    // Panorama / hyperlapse / single take progress.
    var panoRunning by mutableStateOf(false)
    var panoProgress by mutableFloatStateOf(0f)
    var panoTooFast by mutableStateOf(false)
    var lapseRunning by mutableStateOf(false)
    var lapseFrames by mutableStateOf(0)
    var singleTakeRunning by mutableStateOf(false)

    companion object { const val DETAIL_FRAMES = 4 }

    /** Auto HDR: set from the preview when highlights clip or shadows crush. */
    var hdrNeeded by mutableStateOf(false)
    fun hdrActive() = e.mode == Mode.PHOTO && !(prefs.flash == 2 && !e.front) &&
        (prefs.hdr == "On" || (prefs.hdr == "Auto" && hdrNeeded))
    fun autoNight() = e.mode == Mode.PHOTO && prefs.processing == "Detailed" && sceneOn && prefs.sceneOptimizer && scene == "Night"

    // Live Photos
    val live = com.brian.camera.process.MotionPhoto.Recorder()
    private var pendingLive: kotlinx.coroutines.CompletableDeferred<List<ByteArray>>? = null

    // Burst
    var bursting by mutableStateOf(false)
    var burstCount by mutableStateOf(0)

    private fun click() { if (prefs.shutterSound) runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) } }
    fun recSound(start: Boolean) {
        if (prefs.shutterSound) runCatching { sound.play(if (start) MediaActionSound.START_VIDEO_RECORDING else MediaActionSound.STOP_VIDEO_RECORDING) }
    }

    /** True when a plain photo can be saved straight from the camera with no editing. */
    private fun plainPhoto(): Boolean =
        e.mode == Mode.PHOTO && prefs.filter == "None" && !prefs.watermark && !prefs.heif && prefs.ratio != "1:1" &&
            !(sceneOn && prefs.sceneOptimizer && scene != null) && !prefs.livePhoto && prefs.styleTone == 0 && prefs.styleWarmth == 0

    fun photo() {
        if (e.busy != null) return
        e.scope.launch {
            try {
                val frontFlash = e.front && prefs.flash == 2
                if (frontFlash) { e.screenFlash = true; delay(350) }
                pendingLive = if (e.mode == Mode.PHOTO && prefs.livePhoto) {
                    kotlinx.coroutines.CompletableDeferred<List<ByteArray>>().also { d -> live.snap { d.complete(it) } }
                } else null
                when (e.mode) {
                    Mode.NIGHT -> night()
                    Mode.PORTRAIT -> processed("PORTRAIT") { Portrait.apply(it, prefs.portraitEffect, prefs.portraitStrength) }
                    Mode.FOOD -> processed("FOOD") { food(it) }
                    Mode.PRO -> if (e.proRaw && e.caps?.rawCameraId != null && !e.front) raw() else if (prefs.heif || prefs.watermark) processed("IMG") { it } else plain()
                    else -> when {
                        autoNight() -> night()
                        hdrActive() -> hdr()
                        e.mode == Mode.PHOTO && prefs.processing == "Detailed" && !(prefs.flash == 2 && !e.front) -> detailed()
                        plainPhoto() && e.extraZoom <= 1.01f -> plain()
                        else -> processed("IMG") { Img.digitalZoom(it, e.extraZoom) }
                    }
                }
                e.screenFlash = false
            } catch (t: Throwable) {
                e.screenFlash = false
                e.busy = null
                e.toast("Couldn't take the picture: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private suspend fun plain() {
        click()
        val uri = e.takeToGallery("IMG")
        done(uri, null)
    }

    /** Capture → decode → mode effect → ratio/filter/scene/watermark → save. */
    private suspend fun processed(prefix: String, effect: suspend (Bitmap) -> Bitmap) {
        click()
        val file = e.takeToFile()
        e.screenFlash = false
        e.busy = "Processing…"
        val uri = withContext(Dispatchers.Default) {
            var bmp = Img.decodeUpright(file, 13_000_000)
            file.delete()
            if (e.front && prefs.mirrorSelfie) bmp = Img.mirror(bmp)
            bmp = effect(bmp)
            finishAndSave(bmp, prefix)
        }
        e.busy = null
        done(uri, null)
    }

    /** Shared last steps for every processed photo. */
    private suspend fun finishAndSave(src: Bitmap, prefix: String, allowFilter: Boolean = true, enhance: Boolean = true): Uri? {
        var bmp = src
        if (enhance && prefs.processing == "Detailed") bmp = Img.enhance(bmp, 0.35f, 0.15f)
        if (prefs.ratio == "1:1" && e.mode == Mode.PHOTO) bmp = Img.cropRatio(bmp, 1, 1)
        if (allowFilter) {
            Filters.matrix(prefs.filter, prefs.myFilters())?.let { bmp = Img.applyMatrix(bmp, it) }
            if (sceneOn && prefs.sceneOptimizer && e.mode == Mode.PHOTO) scene?.let { s -> Filters.scene(s)?.let { bmp = Img.applyMatrix(bmp, it) } }
            Filters.style(prefs.styleTone, prefs.styleWarmth)?.let { bmp = Img.applyMatrix(bmp, it) }
        }
        if (prefs.watermark) bmp = watermark(bmp)
        // Live Photo: wait for the 1.5 s after the shot, then build the clip.
        val motion = pendingLive?.let { d ->
            pendingLive = null
            val frames = kotlinx.coroutines.withTimeoutOrNull(5000) { d.await() }
            frames?.let { com.brian.camera.process.MotionPhoto.encode(it, File(ctx.cacheDir, "live_${System.nanoTime()}.mp4")) }
        }
        val uri = Saver.saveBitmap(ctx, bmp, prefix, prefs.heif, if (prefs.location) e.location else null, motion)
        e.lastThumb = Bitmap.createScaledBitmap(bmp, 160, (160f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        return uri
    }

    private fun done(uri: Uri?, thumb: Bitmap?) {
        if (uri == null) { e.toast("Couldn't save"); return }
        e.lastUri = uri
        if (thumb != null) e.lastThumb = thumb
        else if (e.lastThumb == null || plainPhoto()) loadThumb(uri)
    }

    fun loadThumb(uri: Uri) {
        e.scope.launch(Dispatchers.IO) {
            val t = runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null) else null
            }.getOrNull()
            if (t != null) withContext(Dispatchers.Main) { e.lastThumb = t }
        }
    }

    // ---- Detailed photo: stack a burst, then sharpen ----
    private suspend fun detailed() {
        click()
        val zoomExtra = e.extraZoom
        val first = e.takeToBitmap()
        e.screenFlash = false
        var stack: Night? = withContext(Dispatchers.Default) { Night(first) }
        repeat(DETAIL_FRAMES - 1) {
            val f = runCatching { e.takeToBitmap() }.getOrNull() ?: return@repeat
            withContext(Dispatchers.Default) { stack!!.add(f) }
        }
        e.busy = "Processing…"
        try {
            val uri = withContext(Dispatchers.Default) {
                var bmp = stack!!.finish(lift = 0f, curve = false)
                stack = null
                bmp = Img.digitalZoom(bmp, zoomExtra)
                // More sharpening when zoomed in, since the crop was enlarged.
                bmp = Img.enhance(bmp, if (zoomExtra > 1.01f || e.zoom >= 2f) 0.9f else 0.6f, 0.22f)
                if (e.front && prefs.mirrorSelfie) bmp = Img.mirror(bmp)
                finishAndSave(bmp, "IMG", enhance = false)
            }
            done(uri, null)
        } finally {
            e.busy = null
        }
    }

    // ---- Smart HDR: under / normal / over brackets fused ----
    private suspend fun hdr() {
        val r = e.evRange
        val step = if (e.evStep > 0f) Math.round(1f / e.evStep) else 0
        val d = minOf(2 * step, r.last, -r.first)
        if (d <= 0) { detailed(); return }
        val ev = e.evIndex
        click()
        e.busy = "HDR…"
        val zoomExtra = e.extraZoom
        try {
            val normal = e.takeToBitmap()
            e.setEvAndWait(ev - d)
            val under = e.takeToBitmap()
            e.setEvAndWait(ev + d)
            val over = e.takeToBitmap()
            e.setEvAndWait(ev)
            e.busy = "Processing…"
            val uri = withContext(Dispatchers.Default) {
                var bmp = com.brian.camera.process.Hdr.fuse(under, normal, over)
                under.recycle(); over.recycle(); normal.recycle()
                bmp = Img.digitalZoom(bmp, zoomExtra)
                bmp = Img.enhance(bmp, 0.5f, 0.18f)
                if (e.front && prefs.mirrorSelfie) bmp = Img.mirror(bmp)
                finishAndSave(bmp, "HDR", enhance = false)
            }
            done(uri, null)
        } finally {
            e.setEv(ev)
            e.busy = null
        }
    }

    // ---- Burst: keep shooting while the shutter is slid left ----
    fun burstStart() {
        if (bursting || e.mode != Mode.PHOTO) return
        bursting = true
        burstCount = 0
        val stamp = Saver.stamp()
        e.scope.launch {
            while (bursting && burstCount < 100) {
                click()
                val u = runCatching { e.takeToGallery("BURST_${stamp}_%03d".format(burstCount + 1)) }.getOrNull() ?: break
                burstCount++
                e.lastUri = u
            }
            bursting = false
            e.lastUri?.let { loadThumb(it) }
            if (burstCount > 0) e.toast("Burst: $burstCount photos")
        }
    }

    fun burstStop() { bursting = false }

    // ---- Night ----
    private suspend fun night() {
        val ev = e.evIndex
        val r = e.evRange
        if (r.last > 0) e.setEv((r.last * 2) / 3)
        e.busy = "Hold still…"
        click()
        try {
            val first = e.takeToBitmap()
            val stack = withContext(Dispatchers.Default) { Night(first) }
            repeat(prefs.nightFrames - 1) {
                val f = e.takeToBitmap()
                withContext(Dispatchers.Default) { stack.add(f) }
            }
            e.busy = "Processing…"
            val uri = withContext(Dispatchers.Default) {
                var bmp = stack.finish(0.6f)
                if (e.front && prefs.mirrorSelfie) bmp = Img.mirror(bmp)
                finishAndSave(bmp, "NIGHT")
            }
            done(uri, null)
        } finally {
            e.setEv(ev)
            e.busy = null
        }
    }

    // ---- Food ----
    private fun food(src: Bitmap): Bitmap {
        val blurred = Img.cheapBlur(src, 18).copy(Bitmap.Config.ARGB_8888, true)
        val fg = src.copy(Bitmap.Config.ARGB_8888, true)
        val cx = foodX * src.width
        val cy = foodY * src.height
        val r = foodRadius * src.width
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 1.35f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        Canvas(fg).drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), p)
        Canvas(blurred).drawBitmap(fg, 0f, 0f, null)
        return Img.applyMatrix(blurred, Filters.food())
    }

    // ---- Pro RAW ----
    private suspend fun raw() {
        val id = e.caps?.rawCameraId ?: return
        e.busy = "Saving RAW…"
        click()
        e.unbindAll()
        try {
            val uri = withContext(Dispatchers.IO) { RawShooter.shoot(ctx, id, e.proIso, e.proShutter) }
            if (uri != null) { e.lastUri = uri; e.toast("RAW (DNG) saved") } else e.toast("RAW not available")
        } finally {
            e.busy = null
            e.rebind()
        }
    }

    // ---- Watermark ----
    private fun watermark(src: Bitmap): Bitmap {
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val size = out.width / 32f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(size / 6, 0f, 0f, Color.argb(160, 0, 0, 0))
        }
        val small = Paint(p).apply { textSize = size * 0.7f; typeface = Typeface.DEFAULT }
        val date = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault()).format(Date())
        val x = size
        val y = out.height - size * 1.2f
        c.drawText(prefs.watermarkText, x, y - small.textSize * 1.3f, p)
        c.drawText(date, x, y, small)
        return out
    }

    // ---- Single take: 8 s of video plus photos, then the best shots and looks ----
    fun singleTake() {
        if (singleTakeRunning || e.videoCapture == null) return
        singleTakeRunning = true
        e.scope.launch {
            val shots = mutableListOf<File>()
            var videoUri: Uri? = null
            recSound(true)
            e.startRecording { videoUri = it }
            try {
                for (i in 0 until 5) {
                    delay(1400)
                    runCatching { shots += e.takeToFile() }
                }
                delay(800)
            } finally {
                e.stopRecording()
                recSound(false)
            }
            e.busy = "Picking the best shots…"
            val uri = withContext(Dispatchers.Default) {
                val decoded = shots.mapNotNull { f -> runCatching { Img.decodeUpright(f, 4_000_000) }.getOrNull().also { f.delete() } }
                    .map { if (e.front && prefs.mirrorSelfie) Img.mirror(it) else it }
                if (decoded.isEmpty()) return@withContext null
                val ranked = decoded.sortedByDescending { Img.sharpness(it) }
                val best = ranked.first()
                val stamp = Saver.stamp()
                var first: Uri? = null
                ranked.take(2).forEachIndexed { i, b ->
                    val u = Saver.saveBitmap(ctx, b, "ST_${stamp}_best${i + 1}", prefs.heif, e.location.takeIf { prefs.location })
                    if (first == null) first = u
                }
                for (look in listOf("Vivid", "Mono", "Film")) {
                    Filters.matrix(look)?.let { Saver.saveBitmap(ctx, Img.applyMatrix(best, it), "ST_${stamp}_$look", prefs.heif, null) }
                }
                // Square "smart crop" of the sharpest shot.
                Saver.saveBitmap(ctx, Img.cropRatio(best, 1, 1), "ST_${stamp}_square", prefs.heif, null)
                e.lastThumb = Bitmap.createScaledBitmap(best, 160, (160f * best.height / best.width).toInt().coerceAtLeast(1), true)
                first
            }
            e.busy = null
            singleTakeRunning = false
            if (uri != null) { e.lastUri = uri; e.toast("Single take: video + ${2 + 4} photos saved") }
            else if (videoUri != null) e.lastUri = videoUri
        }
    }

    // ---- Panorama ----
    private var pano: Panorama? = null

    fun panoToggle() {
        if (panoRunning) { panoStop(); return }
        pano = Panorama()
        panoProgress = 0f
        panoRunning = true
        click()
        e.listen("pano", 0) { f ->
            val p = pano ?: return@listen
            p.add(f)
            withContext(Dispatchers.Main) {
                panoProgress = p.progress
                panoTooFast = p.tooFast
                if (p.full) panoStop()
            }
        }
    }

    private fun panoStop() {
        e.unlisten("pano")
        panoRunning = false
        val p = pano ?: return
        pano = null
        e.busy = "Stitching…"
        e.scope.launch {
            delay(150)
            val uri = withContext(Dispatchers.Default) { p.finish()?.let { finishAndSave(it, "PANO", allowFilter = false) } }
            e.busy = null
            if (uri == null) e.toast("Move the phone slowly to the right for longer") else e.lastUri = uri
        }
    }

    // ---- Hyperlapse: timelapse frames, steadied by tracking the shake and cropping against it ----
    private var encoder: FrameEncoder? = null

    fun lapseToggle() {
        if (lapseRunning) { lapseStop(); return }
        val fw = e.frameW
        val fh = e.frameH
        if (fw == 0) { e.toast("Camera not ready"); return }
        val out = File(ctx.cacheDir, "HYPERLAPSE_${Saver.stamp()}.mp4")
        val enc = runCatching { FrameEncoder(out, (fw * 0.9f).toInt(), (fh * 0.9f).toInt()) }.getOrNull()
            ?: run { e.toast("Video encoder unavailable"); return }
        encoder = enc
        lapseFile = out
        lapseFrames = 0
        lapseRunning = true
        recSound(true)
        val keepEveryMs = prefs.hyperlapseSpeed * 1000L / 30
        var prev: IntArray? = null
        var px = 0f; var py = 0f   // raw camera path
        var sx = 0f; var sy = 0f   // smoothed path
        e.listen("lapse", keepEveryMs) { f ->
            val en = encoder ?: return@listen
            val lw = f.width / 8
            val lh = f.height / 8
            val l = Img.luma(f, lw, lh)
            prev?.let { p ->
                val (dx, dy) = Align.shift(p, l, lw, lh, 6, step = 2)
                px -= dx * 8; py -= dy * 8
            }
            prev = l
            sx += (px - sx) * 0.12f; sy += (py - sy) * 0.12f
            val cw = (f.width * 0.9f).toInt()
            val ch = (f.height * 0.9f).toInt()
            val maxX = (f.width - cw) / 2
            val maxY = (f.height - ch) / 2
            val ox = (maxX + (px - sx)).toInt().coerceIn(0, maxX * 2)
            val oy = (maxY + (py - sy)).toInt().coerceIn(0, maxY * 2)
            en.add(f, Rect(ox, oy, ox + cw, oy + ch))
            withContext(Dispatchers.Main) { lapseFrames++ }
        }
    }

    private var lapseFile: File? = null

    private fun lapseStop() {
        e.unlisten("lapse")
        lapseRunning = false
        recSound(false)
        val en = encoder ?: return
        encoder = null
        val f = lapseFile ?: return
        e.busy = "Saving hyperlapse…"
        e.scope.launch {
            delay(300)
            val uri = withContext(Dispatchers.IO) {
                if (en.finish()) Saver.publishFile(ctx, f, f.name, "video/mp4") else null
            }
            f.delete()
            e.busy = null
            if (uri == null) e.toast("Record a little longer") else { e.lastUri = uri; loadThumb(uri) }
        }
    }

    fun release() { runCatching { sound.release() } }
}
