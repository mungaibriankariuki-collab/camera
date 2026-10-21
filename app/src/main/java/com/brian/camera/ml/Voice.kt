package com.brian.camera.ml

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice commands: "cheese", "smile", "capture", "shoot" take a photo; "record video" starts and
 * "stop" stops a recording. Listens in a loop while the camera is open.
 */
class Voice(private val ctx: Context, private val onCommand: (String) -> Unit) {
    private var sr: SpeechRecognizer? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (running || !SpeechRecognizer.isRecognitionAvailable(ctx)) return
        running = true
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.destroy() }
        sr = null
    }

    private fun listen() {
        if (!running) return
        runCatching { sr?.destroy() }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) { handle(results); again() }
            override fun onPartialResults(partial: Bundle?) { handle(partial) }
            override fun onError(error: Int) { again() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(i) }.onFailure { again() }
    }

    private var lastFire = 0L

    private fun handle(b: Bundle?) {
        val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase() ?: return
        val now = System.currentTimeMillis()
        if (now - lastFire < 2500) return
        val cmd = when {
            listOf("record video", "start recording", "record").any { it in words } -> "record"
            listOf("stop").any { it in words } -> "stop"
            listOf("cheese", "smile", "capture", "shoot", "take photo", "take a picture").any { it in words } -> "photo"
            else -> null
        } ?: return
        lastFire = now
        onCommand(cmd)
    }

    private fun again() {
        if (running) main.postDelayed({ listen() }, 400)
    }
}
