package com.brian.camera.process

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Re-times a high-frame-rate recording so it plays back slowly (video track only). */
object SlowMo {
    fun stretch(src: File, dst: File, factor: Double): Boolean = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val vIdx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(vIdx)
        val fmt = ex.getTrackFormat(vIdx)
        val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (fmt.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION))
        val t = mux.addTrack(fmt)
        mux.start()
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var first = -1L
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            if (first < 0) first = ex.sampleTime
            info.set(0, size, ((ex.sampleTime - first) * factor).toLong(), ex.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
            mux.writeSampleData(t, buf, info)
            ex.advance()
        }
        mux.stop(); mux.release(); ex.release()
        true
    }.getOrDefault(false)
}
