package com.brian.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pro mode RAW: opens the camera directly through Camera2 and writes a DNG. */
object RawShooter {

    @SuppressLint("MissingPermission")
    suspend fun shoot(ctx: Context, cameraId: String, iso: Int?, shutter: Long?): Uri? {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val chars = cm.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: return null
        val yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: return null

        val thread = HandlerThread("raw").apply { start() }
        val h = Handler(thread.looper)
        val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
        yuvReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, h)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            device = withTimeout(4000) {
                suspendCancellableCoroutine { cont ->
                    cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { cont.resume(d) }
                        override fun onDisconnected(d: CameraDevice) { d.close() }
                        override fun onError(d: CameraDevice, error: Int) { d.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error")) }
                    }, h)
                }
            }
            val dev = device!!
            @Suppress("DEPRECATION")
            session = withTimeout(4000) {
                suspendCancellableCoroutine { cont ->
                    dev.createCaptureSession(listOf(rawReader.surface, yuvReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { cont.resumeWithException(IllegalStateException("RAW session failed")) }
                    }, h)
                }
            }
            val ses = session!!
            // Let auto exposure / focus settle on a small stream first.
            val preview = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(yuvReader.surface) }
            ses.setRepeatingRequest(preview.build(), null, h)
            delay(900)

            val still = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawReader.surface)
                if (iso != null || shutter != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso ?: 100)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter ?: 16_666_666L)
                }
            }
            val imageWait = kotlinx.coroutines.CompletableDeferred<Image>()
            rawReader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { imageWait.complete(it) } }, h)
            val result = withTimeout(8000) {
                suspendCancellableCoroutine<TotalCaptureResult> { cont ->
                    ses.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { cont.resume(res) }
                    }, h)
                }
            }
            val image = withTimeout(8000) { imageWait.await() }

            val name = "RAW_${Saver.stamp()}.dng"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Saver.FOLDER)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            val orient = when (chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            DngCreator(chars, result).use { dng ->
                dng.setOrientation(orient)
                ctx.contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, image) }
            }
            image.close()
            return uri
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            rawReader.close(); yuvReader.close()
            thread.quitSafely()
        }
    }
}
