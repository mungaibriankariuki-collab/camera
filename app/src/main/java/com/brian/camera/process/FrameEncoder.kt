package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** Turns a series of bitmaps into an H.264 MP4 at a fixed frame rate (used by Hyperlapse). */
class FrameEncoder(private val file: File, wantW: Int, wantH: Int, private val fps: Int = 30) {
    val width: Int
    val height: Int
    private val codec: MediaCodec
    private val surface: Surface
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var started = false
    private var written = 0
    private val info = MediaCodec.BufferInfo()

    init {
        val (w, h) = pickSize(wantW, wantH)
        width = w; height = h
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.2).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (android.os.Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /** Draws [bmp] (source rect [src], or all of it) scaled to the video size. */
    fun add(bmp: Bitmap, src: Rect? = null) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bmp, src, Rect(0, 0, width, height), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        drain(false)
    }

    fun finish(): Boolean {
        runCatching { codec.signalEndOfInputStream() }
        drain(true)
        runCatching { codec.stop(); codec.release() }
        surface.release()
        val ok = started && written > 0
        runCatching { if (started) muxer.stop() }
        runCatching { muxer.release() }
        return ok
    }

    private fun drain(end: Boolean) {
        val deadline = System.currentTimeMillis() + if (end) 3000 else 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || System.currentTimeMillis() > deadline) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); started = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && started && !config && info.size > 0) {
                        // Canvas frames carry wall-clock times; re-time them to a steady frame rate.
                        info.presentationTimeUs = written * 1_000_000L / fps
                        muxer.writeSampleData(track, buf, info)
                        written++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun pickSize(w: Int, h: Int): Pair<Int, Int> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val caps = list.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        val even = { v: Int -> v / 16 * 16 }
        val candidates = listOf(even(w) to even(h), 1080 to 1920, 720 to 1280)
        return candidates.firstOrNull { (cw, ch) -> caps?.isSizeSupported(cw, ch) ?: true } ?: (720 to 1280)
    }
}
