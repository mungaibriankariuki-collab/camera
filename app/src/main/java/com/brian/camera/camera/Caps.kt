package com.brian.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import kotlin.math.atan
import kotlin.math.max

/**
 * What this phone lets a third-party app do. Every "maybe" feature is switched on or hidden from here,
 * and [report] is the text on the Camera Check screen.
 */
class Caps(
    val back: CameraInfo?,
    val front: CameraInfo?,
    val wide: CameraInfo?,           // separate ultra-wide camera, if apps can see it
    val mainMinZoom: Float,          // < 1 means the main camera itself can zoom out to the ultra-wide
    val wideFactor: Float,           // how "zoomed out" the ultra-wide looks, e.g. 0.6
    val manualSensor: Boolean,
    val raw: Boolean,
    val rawCameraId: String?,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDist: Float,         // 0 = fixed focus
    val wbModes: IntArray,
    val slowMoFps: Int,              // 0 = no slow motion
    val stabilization: Boolean,
    val concurrent: List<CameraInfo>?,
    val videoQualities: List<String>,
    val report: String,
) {
    val hasWide get() = wide != null || mainMinZoom < 0.95f
    val pro get() = manualSensor

    companion object {
        @SuppressLint("RestrictedApi")
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
        fun probe(ctx: Context, provider: ProcessCameraProvider): Caps {
            val sb = StringBuilder()
            val infos = provider.availableCameraInfos
            val back = runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).firstOrNull() }.getOrNull()
            val front = runCatching { CameraSelector.DEFAULT_FRONT_CAMERA.filter(infos).firstOrNull() }.getOrNull()

            fun ch(info: CameraInfo) = Camera2CameraInfo.from(info)
            fun fov(info: CameraInfo): Double {
                val c = ch(info)
                val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0.0
                val f = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return 0.0
                return Math.toDegrees(2 * atan(size.width / (2.0 * f)))
            }

            sb.appendLine("Cameras apps can use: ${infos.size}")
            infos.forEach { info ->
                val c = ch(info)
                val facing = when (info.lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "back"
                    CameraSelector.LENS_FACING_FRONT -> "front"
                    else -> "other"
                }
                val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                val level = when (c.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "?"
                }
                sb.appendLine("• id ${c.cameraId} $facing  focal $focal mm  FOV ${"%.0f".format(fov(info))}°  level $level")
                val zr = info.zoomState.value
                if (zr != null) sb.appendLine("   zoom ${"%.2f".format(zr.minZoomRatio)}–${"%.1f".format(zr.maxZoomRatio)}x")
                sb.appendLine("   fps ranges ${info.supportedFrameRateRanges.joinToString()}")
                val ois = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                sb.appendLine("   OIS ${if (ois?.contains(1) == true) "yes" else "no"}  flash ${info.hasFlashUnit()}")
            }

            // Ultra-wide: another back camera with a wider view than the main one.
            val mainFov = back?.let { fov(it) } ?: 0.0
            val wide = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK && it != back }
                .filter { fov(it) > mainFov * 1.2 }
                .maxByOrNull { fov(it) }
            val mainMinZoom = back?.zoomState?.value?.minZoomRatio ?: 1f
            val wideFactor = if (wide != null) {
                (Math.tan(Math.toRadians(mainFov / 2)) / Math.tan(Math.toRadians(fov(wide) / 2))).toFloat().coerceIn(0.3f, 0.95f)
            } else mainMinZoom

            // Manual controls and RAW, from the main back camera.
            var manual = false
            var raw = false
            var rawId: String? = null
            var iso: Range<Int>? = null
            var exp: Range<Long>? = null
            var minFocus = 0f
            var wb = intArrayOf()
            back?.let { info ->
                val c = ch(info)
                val capsList = c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                manual = capsList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                raw = capsList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                if (raw) rawId = c.cameraId
                iso = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                exp = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                minFocus = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                wb = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
                sb.appendLine()
                sb.appendLine("Main camera capabilities: ${capsList.joinToString { capName(it) }}")
                sb.appendLine("Manual (Pro) controls: ${yes(manual)}   RAW/DNG: ${yes(raw)}")
                sb.appendLine("ISO range: ${iso ?: "-"}")
                sb.appendLine("Shutter range: ${exp?.let { "${fmtShutter(it.lower)} – ${fmtShutter(it.upper)}" } ?: "-"}")
                sb.appendLine("Manual focus: ${yes(minFocus > 0f)} (min distance $minFocus)")
                val hs = c.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                hs?.let { map ->
                    sb.appendLine("Max JPEG: ${map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }}")
                    sb.appendLine("High-speed video: ${map.highSpeedVideoSizes?.joinToString().orEmpty().ifEmpty { "none" }}")
                }
            }

            // Slow motion: a fixed frame rate of 60+ that a normal session accepts.
            val slow = back?.supportedFrameRateRanges
                ?.filter { it.lower == it.upper && it.upper >= 60 }
                ?.maxOfOrNull { it.upper } ?: 0

            var stab = false
            var qualities = emptyList<String>()
            back?.let { info ->
                runCatching {
                    val vc = Recorder.getVideoCapabilities(info)
                    stab = vc.isStabilizationSupported
                    qualities = vc.getSupportedQualities(androidx.camera.core.DynamicRange.SDR).map { qName(it) }
                }
            }
            val conc = runCatching {
                provider.availableConcurrentCameraInfos.firstOrNull { list ->
                    list.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } && list.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                }
            }.getOrNull()

            sb.appendLine()
            sb.appendLine("Ultra-wide for apps: ${if (wide != null) "yes (separate camera)" else if (mainMinZoom < 0.95f) "yes (zoom ${"%.1f".format(mainMinZoom)}x)" else "no"}")
            sb.appendLine("Slow motion: ${if (slow > 0) "$slow fps" else "no"}")
            sb.appendLine("Video stabilization: ${yes(stab)}")
            sb.appendLine("Video qualities: ${qualities.joinToString().ifEmpty { "-" }}")
            sb.appendLine("Front + back at once (Director's view): ${yes(conc != null)}")

            // Cameras the system lists but apps normally don't get (hidden aux cameras).
            runCatching {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val listed = cm.cameraIdList.toSet()
                val hidden = (0..15).map { it.toString() }.filter { it !in listed }.filter { id ->
                    runCatching { cm.getCameraCharacteristics(id); true }.getOrDefault(false)
                }
                sb.appendLine()
                sb.appendLine("Camera2 ids: ${listed.joinToString()}")
                sb.appendLine("Hidden camera ids: ${hidden.joinToString().ifEmpty { "none" }}")
            }

            return Caps(back, front, wide, mainMinZoom, wideFactor, manual, raw, rawId, iso, exp, minFocus, wb, slow, stab, conc, qualities, sb.toString())
        }

        private fun yes(b: Boolean) = if (b) "yes" else "no"

        fun fmtShutter(ns: Long): String =
            if (ns >= 1_000_000_000L) "%.1fs".format(ns / 1e9) else "1/${max(1L, Math.round(1e9 / ns))}"

        fun qName(q: Quality) = when (q) {
            Quality.UHD -> "UHD"
            Quality.FHD -> "FHD"
            Quality.HD -> "HD"
            Quality.SD -> "SD"
            else -> q.toString()
        }

        private fun capName(c: Int) = when (c) {
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BASIC"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROC"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIV_REPROC"
            else -> "cap$c"
        }
    }
}
