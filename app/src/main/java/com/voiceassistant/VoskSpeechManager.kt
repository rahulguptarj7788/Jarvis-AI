package com.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskSpeechManager(
    private val context: Context,
    private val listener: RecognitionListener
) {
    interface RecognitionListener {
        fun onReady()
        fun onPartialResult(hypothesis: String)
        fun onFinalResult(hypothesis: String)
        fun onError(exception: Exception)
        fun onTimeout()
    }

    companion object {
        private const val TAG = "VoskSpeechManager"
    }

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError(SecurityException("Microphone permission not granted"))
            stop()
            return
        }

        try {
            StorageService.unpack(context, "model", "model",
                { unpackedModel ->
                    try {
                        model = unpackedModel
                        recognizer = createRecognizerSafely(model)
                        speechService = SpeechService(recognizer, 16000.0f)
                        speechService?.startListening(createVoskListener())
                        listener.onReady()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Post-unpack init failed", t)
                        listener.onError(RuntimeException("Init error: ${t.message}"))
                        stop()
                    }
                },
                { exception ->
                    Log.e(TAG, "StorageService unpack failed", exception)
                    listener.onError(RuntimeException("Unpack error: ${exception.message}"))
                    stop()
                })
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error calling unpack", t)
            listener.onError(RuntimeException("Unexpected error: ${t.message}"))
            stop()
        }
    }

    private fun createRecognizerSafely(model: Model?): Recognizer {
        if (model == null) throw IOException("Model is null")
        return try {
            Recognizer(model, 16000.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer creation failed", e)
            throw e
        } catch (e: Error) {
            Log.e(TAG, "Recognizer creation crashed (native)", e)
            throw RuntimeException("Native crash while creating recognizer", e)
        }
    }

    private fun createVoskListener(): org.vosk.android.RecognitionListener {
        return object : org.vosk.android.RecognitionListener {
            override fun onPartialResult(hypothesis: String) {
                listener.onPartialResult(hypothesis)
            }
            override fun onResult(hypothesis: String) {
                listener.onFinalResult(hypothesis)
                stop()
            }
            override fun onFinalResult(hypothesis: String) {
                listener.onFinalResult(hypothesis)
                stop()
            }
            override fun onError(exception: Exception) {
                listener.onError(exception)
                stop()
            }
            override fun onTimeout() {
                listener.onTimeout()
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try { speechService?.stop() } catch (_: Exception) {}
        speechService = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
    }
}
