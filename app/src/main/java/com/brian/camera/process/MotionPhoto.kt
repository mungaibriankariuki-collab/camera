package com.brian.camera.process

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque

/**
 * Live Photos, saved as Google Motion Photos (MVIMG_*.jpg = JPEG + XMP + MP4 appended), which
 * Google Photos and most galleries play. [Recorder] keeps ~1.5 s of compressed preview frames.
 */
object MotionPhoto {
    const val FPS = 15
    const val PRE_MS = 1500L
    const val POST_MS = 1500L

    class Recorder {
        private class Frame(val t: Long, val jpeg: ByteArray)
        private val ring = ArrayDeque<Frame>()
        private var capture: MutableList<Frame>? = null
        private var captureUntil = 0L
        private var onDone: ((List<ByteArray>) -> Unit)? = null

        @Synchronized
        fun add(frame: Bitmap) {
            val now = System.currentTimeMillis()
            val bos = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            val f = Frame(now, bos.toByteArray())
            ring.addLast(f)
            while (ring.isNotEmpty() && now - ring.first().t > PRE_MS) ring.removeFirst()
            capture?.let { c ->
                c += f
                if (now >= captureUntil) {
                    val cb = onDone
                    capture = null; onDone = null
                    cb?.invoke(c.map { it.jpeg })
                }
            }
        }

        /** Called at the shutter: hands back pre + post frames once the post part has been recorded. */
        @Synchronized
        fun snap(done: (List<ByteArray>) -> Unit) {
            capture = ring.toMutableList()
            captureUntil = System.currentTimeMillis() + POST_MS
            onDone = done
        }

        @Synchronized
        fun clear() { ring.clear(); capture = null; onDone = null }
    }

    /** Encodes frames to an MP4. Returns null when there's too little to show. */
    fun encode(frames: List<ByteArray>, out: File): File? {
        if (frames.size < 6) return null
        val first = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size) ?: return null
        val enc = runCatching { FrameEncoder(out, first.width, first.height, FPS) }.getOrNull() ?: return null
        for (j in frames) {
            val b = BitmapFactory.decodeByteArray(j, 0, j.size) ?: continue
            enc.add(b)
        }
        return if (enc.finish()) out else null
    }

    /** Builds MVIMG bytes: JPEG with a Motion Photo XMP block inserted after SOI, then the MP4. */
    fun combine(jpeg: File, mp4: File, out: File) {
        val j = jpeg.readBytes()
        val v = mp4.readBytes()
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            """GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${v.size}" """ +
            """GCamera:MicroVideoPresentationTimestampUs="${PRE_MS * 1000}"/></rdf:RDF></x:xmpmeta>"""
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val payload = ns + xmp.toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        out.outputStream().use { o ->
            o.write(j, 0, 2)          // SOI
            o.write(seg)
            o.write(j, 2, j.size - 2)
            o.write(v)
        }
    }

    /** Pulls the video out of a Motion Photo (for the in-app gallery). */
    fun extract(bytes: ByteArray, out: File): File? {
        val head = String(bytes, 0, minOf(bytes.size, 65536), Charsets.ISO_8859_1)
        val m = Regex("MicroVideoOffset=\"(\\d+)\"").find(head) ?: Regex("Item:Length=\"(\\d+)\"[^>]*Semantic=\"MotionPhoto\"").find(head)
        val len = m?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (len <= 0 || len >= bytes.size) return null
        out.writeBytes(bytes.copyOfRange(bytes.size - len, bytes.size))
        return out
    }
}
