package com.brian.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File

/**
 * Slow motion through a Camera2 constrained high-speed session (CameraX can't open one).
 * Shows its own preview in [texture]; records at [fps] into an MP4 that is re-timed afterwards.
 */
class HighSpeed(private val ctx: Context, private val cameraId: String, private val size: Size, val fps: Int, private val onError: (String) -> Unit) {
    val texture = TextureView(ctx)
    private val thread = HandlerThread("highspeed").apply { start() }
    private val handler = Handler(thread.looper)
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val chars = cm.getCameraCharacteristics(cameraId)
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var closed = false

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { open(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun ranges(): List<Range<Int>> = runCatching {
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getHighSpeedVideoFpsRangesFor(size).toList()
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    private fun open(st: SurfaceTexture) {
        if (closed || device != null) return
        st.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(st)
        runCatching {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (closed) { d.close(); return }
                    device = d
                    startSession(withRecorder = false)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; onError("Slow-motion camera error $error") }
            }, handler)
        }.onFailure { onError(it.message ?: "Can't open camera") }
    }

    @Suppress("DEPRECATION")
    private fun startSession(withRecorder: Boolean, onReady: () -> Unit = {}) {
        val d = device ?: return
        val prev = previewSurface ?: return
        runCatching { session?.close() }
        session = null
        val surfaces = mutableListOf(prev)
        recorder?.surface?.let { if (withRecorder) surfaces += it }
        runCatching {
            d.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val hs = s as CameraConstrainedHighSpeedCaptureSession
                    session = hs
                    // Preview may run at a lower range (e.g. 30–120); recording needs the fixed fast one.
                    val range = if (withRecorder) ranges().firstOrNull { it.lower == fps && it.upper == fps } ?: Range(fps, fps)
                    else ranges().filter { it.upper == fps }.minByOrNull { it.lower } ?: Range(fps, fps)
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        surfaces.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    }
                    runCatching {
                        hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), null, handler)
                        onReady()
                    }.onFailure { onError("Slow motion: ${it.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { onError("This size/speed isn't accepted by the camera") }
            }, handler)
        }.onFailure { onError("Slow motion: ${it.message}") }
    }

    fun startRecording(out: File) {
        handler.post {
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            runCatching {
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(out.absolutePath)
                mr.setVideoEncodingBitRate((size.width.toLong() * size.height * fps / 5).coerceAtMost(60_000_000L).toInt())
                mr.setVideoFrameRate(fps)
                mr.setVideoSize(size.width, size.height)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setOrientationHint(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
                mr.prepare()
                recorder = mr
                file = out
                startSession(withRecorder = true) { runCatching { mr.start() }.onFailure { onError("Recorder: ${it.message}") } }
            }.onFailure {
                runCatching { mr.release() }
                onError("Slow motion recorder: ${it.message}")
            }
        }
    }

    /** Stops and hands back the raw high-frame-rate file (null if nothing was recorded). */
    fun stopRecording(done: (File?) -> Unit) {
        handler.post {
            val mr = recorder
            recorder = null
            runCatching { session?.stopRepeating() }
            val ok = mr != null && runCatching { mr.stop() }.isSuccess
            runCatching { mr?.release() }
            val f = if (ok) file else null
            file = null
            startSession(withRecorder = false)
            done(f)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            runCatching { recorder?.release() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            device = null
            thread.quitSafely()
        }
    }
}
