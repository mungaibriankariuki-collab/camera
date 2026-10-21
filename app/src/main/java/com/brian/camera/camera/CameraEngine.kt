package com.brian.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.brian.camera.Prefs
import com.brian.camera.process.Img
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns CameraX: binds the right use cases for each mode and exposes everything the UI shows. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(val ctx: Context, private val owner: LifecycleOwner, val prefs: Prefs) {

    val previewView = PreviewView(ctx).apply {
        // TextureView mode so live filters can be applied as a layer paint.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    }
    val pipView = PreviewView(ctx).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val mainExec get() = ContextCompat.getMainExecutor(ctx)

    // ---- state the UI reads ----
    var caps by mutableStateOf<Caps?>(null)
    var mode by mutableStateOf(if (prefs.keepLastMode) Mode.of(prefs.lastMode) else Mode.PHOTO)
        private set
    var front by mutableStateOf(prefs.frontCamera)
        private set
    var useWide by mutableStateOf(false)
        private set
    var zoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(15f)
    private var camMaxZoom = 10f

    /** Digital zoom past the camera's own limit: the preview is scaled and the photo is cropped. */
    var extraZoom by mutableFloatStateOf(1f)
        private set
    private fun setExtra(x: Float) {
        extraZoom = x
        previewView.scaleX = x
        previewView.scaleY = x
    }
    var torch by mutableStateOf(false)
    var evIndex by mutableIntStateOf(0)
    var evRange by mutableStateOf(0..0)
    var evStep by mutableFloatStateOf(0f)
    var recording by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var recSeconds by mutableIntStateOf(0)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var lastUri by mutableStateOf<Uri?>(null)
    var lastThumb by mutableStateOf<Bitmap?>(null)
    var screenFlash by mutableStateOf(false)
    var frameW by mutableIntStateOf(0)
    var frameH by mutableIntStateOf(0)
    var bindError by mutableStateOf<String?>(null)

    // Pro: null = auto.
    var proIso by mutableStateOf<Int?>(null)
    var proShutter by mutableStateOf<Long?>(null)
    var proWb by mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    var proFocus by mutableStateOf<Float?>(null)
    var proRaw by mutableStateOf(false)
    var liveIso by mutableIntStateOf(0)
    var liveShutter by mutableStateOf(0L)
    private var lastLiveUpdate = 0L

    // ---- CameraX objects ----
    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    private var videoCapture2: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var activeRecordings = mutableListOf<Recording>()

    /** Camera2 high-speed path for slow motion (null when CameraX is in charge). */
    var highSpeed by mutableStateOf<HighSpeed?>(null)
        private set
    private var hsDone: ((Uri?) -> Unit)? = null
    private val hsTick = object : Runnable {
        override fun run() { if (recording) { recSeconds++; main.postDelayed(this, 1000) } }
    }

    private fun closeHighSpeed() { highSpeed?.close(); highSpeed = null }

    // ---- frame hub: one analysis stream shared by every live feature ----
    class Listener(val intervalMs: Long, val fn: suspend (Bitmap) -> Unit) {
        @Volatile var busy = false
        @Volatile var last = 0L
    }
    private val listeners = ConcurrentHashMap<String, Listener>()
    fun listen(key: String, intervalMs: Long, fn: suspend (Bitmap) -> Unit) { listeners[key] = Listener(intervalMs, fn) }
    fun unlisten(key: String) { listeners.remove(key) }
    @Volatile var latestFrame: Bitmap? = null
        private set

    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun onFrame(proxy: ImageProxy) {
        val bmp = try {
            var b = proxy.toBitmap()
            b = Img.rotate(b, proxy.imageInfo.rotationDegrees)
            if (front) Img.mirror(b) else b
        } catch (t: Throwable) {
            null
        } finally {
            proxy.close()
        }
        bmp ?: return
        latestFrame = bmp
        if (frameW != bmp.width || frameH != bmp.height) main.post { frameW = bmp.width; frameH = bmp.height }
        val now = System.currentTimeMillis()
        for (l in listeners.values) {
            if (l.busy || now - l.last < l.intervalMs) continue
            l.busy = true
            l.last = now
            frameScope.launch {
                try { l.fn(bmp) } catch (_: Throwable) {} finally { l.busy = false }
            }
        }
    }

    // ---- location ----
    @Volatile var location: Location? = null
        private set
    private val locListener = LocationListener { location = it }

    @SuppressLint("MissingPermission")
    fun updateLocation() {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        lm.removeUpdates(locListener)
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!prefs.location || !ok) { location = null; return }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { if (location == null) location = it }
                    lm.requestLocationUpdates(p, 20_000L, 10f, locListener, Looper.getMainLooper())
                }
            }
        }
    }

    // ---- lifecycle ----
    fun start(onReady: () -> Unit = {}) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            provider = p
            if (caps == null) caps = runCatching { Caps.probe(ctx, p) }.getOrNull()
            if (mode == Mode.DUAL && caps?.concurrent == null) mode = Mode.VIDEO
            rebind()
            updateLocation()
            onReady()
        }, mainExec)
    }

    fun release() {
        stopRecording()
        provider?.unbindAll()
        ctx.getSystemService(LocationManager::class.java)?.removeUpdates(locListener)
        listeners.clear()
    }

    /** Frees the camera so RAW capture can open it directly through Camera2. */
    fun unbindAll() { closeHighSpeed(); provider?.unbindAll(); camera = null }

    fun switchMode(m: Mode) {
        if (m == mode || recording) return
        mode = m
        prefs.lastMode = m.name
        if (m == Mode.PRO || m == Mode.PANORAMA || m == Mode.HYPERLAPSE) useWide = false
        rebind()
    }

    fun flip() {
        if (recording) return
        front = !front
        prefs.frontCamera = front
        useWide = false
        rebind()
    }

    // ---- binding ----
    private fun selector(): CameraSelector {
        val c = caps
        return when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            useWide && c?.wide != null -> c.wide.cameraSelector
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun aspect(): Int = when {
        mode == Mode.PANORAMA || mode == Mode.LENS || mode == Mode.PORTRAIT || mode == Mode.NIGHT || mode == Mode.PRO || mode == Mode.FOOD -> AspectRatio.RATIO_4_3
        mode.video -> AspectRatio.RATIO_16_9
        prefs.ratio == "9:16" -> AspectRatio.RATIO_16_9
        else -> AspectRatio.RATIO_4_3
    }

    /** The preview's width/height (portrait), used to size the viewfinder box. */
    fun previewAspect(): Float = if (aspect() == AspectRatio.RATIO_16_9) 9f / 16f else 3f / 4f

    private fun rs(ratio: Int, size: Size? = null, highest: Boolean = false): ResolutionSelector {
        val b = ResolutionSelector.Builder().setAspectRatioStrategy(
            AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        )
        if (highest) b.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        else if (size != null) b.setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        return b.build()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = System.currentTimeMillis()
            if (now - lastLiveUpdate < 300) return
            lastLiveUpdate = now
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            main.post { liveIso = iso; liveShutter = exp }
        }
    }

    fun rebind() {
        val p = provider ?: return
        if (recording) return
        p.unbindAll()
        closeHighSpeed()
        setExtra(1f)
        camera = null; imageCapture = null; videoCapture = null; videoCapture2 = null; analysis = null
        latestFrame = null
        bindError = null
        if (mode == Mode.DOC) return
        val hc = caps
        if (mode == Mode.SLOW_MO && !front && hc != null && hc.slowMoFps == 0 && hc.hsCameraId != null && hc.hsSize != null) {
            highSpeed = HighSpeed(ctx, hc.hsCameraId, hc.hsSize, hc.hsFps) { msg -> main.post { bindError = msg } }
            return
        }
        if (mode == Mode.DUAL) { bindDual(p); return }

        val ratio = aspect()
        val c = caps
        val pb = Preview.Builder().setResolutionSelector(rs(ratio))
        Camera2Interop.Extender(pb).setSessionCaptureCallback(captureCallback)
        if (mode.video && prefs.stabilization && c?.stabilization == true && mode != Mode.SLOW_MO) {
            runCatching { pb.setPreviewStabilizationEnabled(true) }
        }
        val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val cases = mutableListOf<UseCase>(preview)
        var optional: UseCase? = null
        fun analysisOf(size: Size, r: Int = ratio) = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(rs(r, size))
            .build().also { a -> a.setAnalyzer(analysisExec) { onFrame(it) }; analysis = a }

        when (if (quickTake) Mode.VIDEO else mode) {
            Mode.PHOTO, Mode.PORTRAIT, Mode.NIGHT, Mode.FOOD, Mode.PRO -> {
                val size = when (mode) {
                    Mode.NIGHT, Mode.PORTRAIT -> Size(3264, 2448)
                    else -> if (ratio == AspectRatio.RATIO_16_9) Size(4000, 2250) else Size(4000, 3000)
                }
                val detailed = prefs.processing == "Detailed"
                val icb = ImageCapture.Builder()
                    .setCaptureMode(
                        if (mode == Mode.PRO || (!detailed && mode == Mode.PHOTO)) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setResolutionSelector(rs(ratio, size, highest = mode == Mode.PHOTO && prefs.highRes && !front))
                    .setFlashMode(flashMode())
                    .setJpegQuality(98)
                // Keep fine texture: light in-camera noise reduction (frame stacking removes the noise) and best edges.
                Camera2Interop.Extender(icb)
                    .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    .setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        if (detailed && mode != Mode.PRO) CaptureRequest.NOISE_REDUCTION_MODE_FAST else CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )
                val ic = icb.build()
                imageCapture = ic
                cases += ic
                // Live Photos need a sharper stream to build the clip from.
                optional = analysisOf(if (mode == Mode.PHOTO && prefs.livePhoto) Size(960, 720) else Size(640, 480))
            }
            Mode.LENS, Mode.PANORAMA -> cases += analysisOf(Size(1280, 960))
            Mode.HYPERLAPSE -> cases += analysisOf(Size(1920, 1080), AspectRatio.RATIO_16_9)
            Mode.VIDEO, Mode.SLOW_MO -> {
                cases += buildVideo(mode == Mode.SLOW_MO)
                if (mode == Mode.VIDEO) optional = analysisOf(Size(640, 360))
            }
            Mode.SINGLE_TAKE -> {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(rs(ratio, Size(1920, 1080)))
                    .build()
                imageCapture = ic
                cases += ic
                cases += buildVideo(false)
            }
            else -> {}
        }

        val sel = selector()
        val all = if (optional != null) cases + optional!! else cases
        camera = try {
            p.bindToLifecycle(owner, sel, *all.toTypedArray())
        } catch (t: Throwable) {
            // Some combinations are too much for the phone: retry without the analysis stream.
            if (optional != null) {
                analysis = null
                runCatching { p.unbindAll(); p.bindToLifecycle(owner, sel, *cases.toTypedArray()) }
                    .onFailure { bindError = it.message }.getOrNull()
            } else {
                bindError = t.message
                null
            }
        }
        afterBind()
    }

    private fun buildVideo(slow: Boolean): VideoCapture<Recorder> {
        val q = when (prefs.videoQuality) {
            "UHD" -> Quality.UHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> Quality.FHD
        }
        val qs = if (slow) QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
        else QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
        val recorder = Recorder.Builder().setQualitySelector(qs).build()
        val b = VideoCapture.Builder(recorder)
        if (slow) caps?.slowMoFps?.takeIf { it > 0 }?.let { b.setTargetFrameRate(Range(it, it)) }
        if (!slow && prefs.stabilization && caps?.stabilization == true) runCatching { b.setVideoStabilizationEnabled(true) }
        return b.build().also { videoCapture = it }
    }

    private fun bindDual(p: ProcessCameraProvider) {
        val list = caps?.concurrent ?: run { bindError = "This phone can't run two cameras at once"; return }
        val backInfo = list.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        val frontInfo = list.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        fun video(): VideoCapture<Recorder> = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
        )
        val p1 = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val p2 = Preview.Builder().build().also { it.setSurfaceProvider(pipView.surfaceProvider) }
        val v1 = video().also { videoCapture = it }
        val v2 = video().also { videoCapture2 = it }
        val g1 = UseCaseGroup.Builder().addUseCase(p1).addUseCase(v1).build()
        val g2 = UseCaseGroup.Builder().addUseCase(p2).addUseCase(v2).build()
        runCatching {
            val cc = p.bindToLifecycle(listOf(
                ConcurrentCamera.SingleCameraConfig(backInfo.cameraSelector, g1, owner),
                ConcurrentCamera.SingleCameraConfig(frontInfo.cameraSelector, g2, owner),
            ))
            camera = cc.cameras.firstOrNull()
        }.onFailure { bindError = it.message }
        afterBind()
    }

    private fun afterBind() {
        val cam = camera ?: return
        cam.cameraInfo.zoomState.observe(owner) { z ->
            camMaxZoom = z.maxZoomRatio
            maxZoom = if (useWide) z.maxZoomRatio * (caps?.wideFactor ?: 0.6f) else maxOf(z.maxZoomRatio, ZOOM_CAP)
            zoom = if (useWide) (caps?.wideFactor ?: 0.6f) * z.zoomRatio else z.zoomRatio * extraZoom
        }
        val ex = cam.cameraInfo.exposureState
        evRange = if (ex.isExposureCompensationSupported) ex.exposureCompensationRange.lower..ex.exposureCompensationRange.upper else 0..0
        evStep = ex.exposureCompensationStep.toFloat()
        evIndex = ex.exposureCompensationIndex
        if (torch) cam.cameraControl.enableTorch(true)
        if (mode == Mode.PRO) applyPro() else { proIso = null; proShutter = null; proFocus = null }
    }

    // ---- controls ----
    fun flashMode() = when (prefs.flash) {
        1 -> ImageCapture.FLASH_MODE_AUTO
        2 -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    fun cycleFlash() {
        if (mode.video) {
            torch = !torch
            camera?.cameraControl?.enableTorch(torch)
            return
        }
        prefs.flash = (prefs.flash + 1) % 3
        imageCapture?.flashMode = flashMode()
    }

    /** Sets the zoom the user sees. Below 1x switches to the ultra-wide when it's a separate camera. */
    fun setZoomLevel(level: Float) {
        val c = caps
        if (!front && c?.wide != null && mode != Mode.PRO && mode != Mode.PANORAMA) {
            val wantWide = level < 0.99f
            if (wantWide != useWide) {
                useWide = wantWide
                rebind()
                if (!wantWide) camera?.cameraControl?.setZoomRatio(level.coerceAtLeast(1f))
                return
            }
            if (useWide) {
                camera?.cameraControl?.setZoomRatio((level / c.wideFactor).coerceAtLeast(1f))
                return
            }
        }
        val l = level.coerceIn(c?.mainMinZoom ?: 1f, maxZoom)
        if (l > camMaxZoom && !useWide) {
            setExtra(l / camMaxZoom)
            camera?.cameraControl?.setZoomRatio(camMaxZoom)
        } else {
            setExtra(1f)
            camera?.cameraControl?.setZoomRatio(l)
        }
        zoom = l
    }

    companion object { const val ZOOM_CAP = 15f }

    fun minZoomLevel(): Float {
        val c = caps ?: return 1f
        if (front) return 1f
        return if (c.wide != null) c.wideFactor else c.mainMinZoom
    }

    fun focusAt(x: Float, y: Float, lock: Boolean = false) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (lock) b.disableAutoCancel() else b.setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
        cam.cameraControl.startFocusAndMetering(b.build())
    }

    /** Sets exposure compensation and waits for auto-exposure to settle (HDR brackets). */
    suspend fun setEvAndWait(i: Int) {
        val cam = camera ?: return
        val idx = i.coerceIn(evRange.first, evRange.last)
        evIndex = idx
        kotlinx.coroutines.withTimeoutOrNull(1500) {
            suspendCancellableCoroutine<Unit> { cont ->
                val f = cam.cameraControl.setExposureCompensationIndex(idx)
                f.addListener({ if (cont.isActive) cont.resume(Unit) }, mainExec)
            }
        }
        kotlinx.coroutines.delay(120)
    }

    // ---- QuickTake: hold the shutter in Photo to record ----
    var quickTake by mutableStateOf(false)
        private set

    fun quickTakeStart(onDone: (Uri?) -> Unit) {
        if (mode != Mode.PHOTO || recording) return
        quickTake = true
        rebind()
        startRecording(onDone)
    }

    fun quickTakeStop() {
        if (!quickTake) return
        stopRecording()
        // Give the recorder a moment to finalize before switching back to photo.
        main.postDelayed({ quickTake = false; rebind() }, 700)
    }

    fun setEv(i: Int) {
        evIndex = i.coerceIn(evRange.first, evRange.last)
        camera?.cameraControl?.setExposureCompensationIndex(evIndex)
    }

    fun applyPro() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        val iso = proIso
        val sh = proShutter
        if (iso != null || sh != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso ?: liveIso.takeIf { it > 0 } ?: 100)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, sh ?: liveShutter.takeIf { it > 0 } ?: 16_666_666L)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proWb)
        proFocus?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    // ---- photo capture primitives ----
    private fun captureMetadata() = ImageCapture.Metadata().apply {
        isReversedHorizontal = front && prefs.mirrorSelfie
        if (prefs.location) this@CameraEngine.location?.let { location = it }
    }

    /** Straight into the gallery, no processing (fastest, keeps the camera's own JPEG). */
    suspend fun takeToGallery(prefix: String): Uri? = suspendCancellableCoroutine { cont ->
        val ic = imageCapture ?: return@suspendCancellableCoroutine cont.resume(null)
        val name = "${prefix}_${Saver.stamp()}.jpg"
        val opts = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Saver.imageValues(name, "image/jpeg"))
        } else {
            @Suppress("DEPRECATION")
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera").apply { mkdirs() }
            ImageCapture.OutputFileOptions.Builder(File(dir, name))
        }.setMetadata(captureMetadata()).build()
        ic.takePicture(opts, mainExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { cont.resume(r.savedUri) }
            override fun onError(e: ImageCaptureException) { cont.resumeWithException(e) }
        })
    }

    /** To a private temp file (for processing). */
    suspend fun takeToFile(): File = suspendCancellableCoroutine { cont ->
        val ic = imageCapture ?: return@suspendCancellableCoroutine cont.resumeWithException(IllegalStateException("No camera"))
        val f = File(File(ctx.cacheDir, "shots").apply { mkdirs() }, "s_${System.nanoTime()}.jpg")
        val md = ImageCapture.Metadata()
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).setMetadata(md).build(), mainExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { cont.resume(f) }
            override fun onError(e: ImageCaptureException) { cont.resumeWithException(e) }
        })
    }

    /** In memory, upright (used for night stacking). */
    suspend fun takeToBitmap(): Bitmap = suspendCancellableCoroutine { cont ->
        val ic = imageCapture ?: return@suspendCancellableCoroutine cont.resumeWithException(IllegalStateException("No camera"))
        ic.takePicture(analysisExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val b = Img.rotate(image.toBitmap().copy(Bitmap.Config.ARGB_8888, true), image.imageInfo.rotationDegrees)
                    cont.resume(b)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                } finally {
                    image.close()
                }
            }
            override fun onError(e: ImageCaptureException) { cont.resumeWithException(e) }
        })
    }

    // ---- video ----
    @SuppressLint("MissingPermission")
    fun startRecording(onDone: (Uri?) -> Unit) {
        highSpeed?.let { hs ->
            hs.startRecording(File(ctx.cacheDir, "hs_${System.nanoTime()}.mp4"))
            hsDone = onDone
            recording = true
            recSeconds = 0
            main.postDelayed(hsTick, 1000)
            return
        }
        val vc = videoCapture ?: return
        val audio = prefs.recordAudio && mode != Mode.SLOW_MO &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val slowFile = if (mode == Mode.SLOW_MO) File(ctx.cacheDir, "slow_${System.nanoTime()}.mp4") else null

        fun start(v: VideoCapture<Recorder>, tag: String, withAudio: Boolean, finished: (Uri?) -> Unit): Recording {
            val pending = if (slowFile != null) {
                v.output.prepareRecording(ctx, FileOutputOptions.Builder(slowFile).build())
            } else {
                val b = MediaStoreOutputOptions.Builder(ctx.contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(Saver.videoValues("${tag}_${Saver.stamp()}.mp4"))
                if (prefs.location) location?.let { b.setLocation(it) }
                v.output.prepareRecording(ctx, b.build())
            }
            val pr = if (withAudio) pending.withAudioEnabled() else pending
            return pr.start(mainExec) { e ->
                when (e) {
                    is VideoRecordEvent.Status -> if (tag != "PIP") recSeconds = (e.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                    is VideoRecordEvent.Finalize -> finished(e.outputResults.outputUri.takeIf { it != Uri.EMPTY })
                }
            }
        }

        activeRecordings.clear()
        recSeconds = 0
        activeRecordings += start(vc, if (mode == Mode.DUAL) "DUAL" else "VID", audio) { uri ->
            if (slowFile != null) {
                scope.launch(Dispatchers.IO) {
                    val out = File(ctx.cacheDir, "SLOWMO_${Saver.stamp()}.mp4")
                    val fps = caps?.slowMoFps ?: 120
                    val ok = com.brian.camera.process.SlowMo.stretch(slowFile, out, fps / 30.0)
                    val u = if (ok) Saver.publishFile(ctx, out, out.name, "video/mp4") else null
                    slowFile.delete(); out.delete()
                    launch(Dispatchers.Main) { onDone(u) }
                }
            } else onDone(uri)
        }
        videoCapture2?.let { v2 -> activeRecordings += start(v2, "PIP", false) {} }
        recording = true
        paused = false
    }

    fun stopRecording() {
        highSpeed?.let { hs ->
            if (!recording) return
            recording = false
            main.removeCallbacks(hsTick)
            val done = hsDone
            hsDone = null
            busy = "Saving slow motion…"
            hs.stopRecording { raw ->
                scope.launch(Dispatchers.IO) {
                    var uri: Uri? = null
                    if (raw != null) {
                        val out = File(ctx.cacheDir, "SLOWMO_${Saver.stamp()}.mp4")
                        if (com.brian.camera.process.SlowMo.stretch(raw, out, hs.fps / 30.0)) uri = Saver.publishFile(ctx, out, out.name, "video/mp4")
                        raw.delete(); out.delete()
                    }
                    launch(Dispatchers.Main) { busy = null; done?.invoke(uri) }
                }
            }
            return
        }
        activeRecordings.forEach { runCatching { it.stop() } }
        activeRecordings.clear()
        recording = false
        paused = false
    }

    fun togglePause() {
        if (!recording) return
        if (paused) activeRecordings.forEach { it.resume() } else activeRecordings.forEach { it.pause() }
        paused = !paused
    }

    fun toast(msg: String) {
        message = msg
        main.postDelayed({ if (message == msg) message = null }, 2500)
    }
}
