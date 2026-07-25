package com.voiceassistant

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
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
        // Matches the folder created by the build workflow:
        // app/src/main/assets/models/vosk-model-small-en-us-0.15/
        private const val ASSETS_MODEL_PATH = "models/vosk-model-small-en-us-0.15"
        // Destination inside internal storage
        private const val DEST_DIR = "model"
    }

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        // Try to unpack the model from assets. If it already exists, StorageService will skip.
        StorageService.unpack(
            context,
            ASSETS_MODEL_PATH,
            DEST_DIR,
            { modelInstance ->
                model = modelInstance
                initializeRecognizer()
            },
            { exception ->
                Log.e(TAG, "Failed to unpack model from assets: $ASSETS_MODEL_PATH", exception)
                // Fallback: check if model was already unpacked previously
                val existingModelPath = File(context.filesDir, DEST_DIR).absolutePath
                if (File(existingModelPath).exists() && File(existingModelPath, "am").exists()) {
                    try {
                        model = Model(existingModelPath)
                        initializeRecognizer()
                    } catch (e: IOException) {
                        listener.onError(e)
                        stop()
                    }
                } else {
                    listener.onError(IOException("Model not found. Please ensure the Vosk model is in assets/$ASSETS_MODEL_PATH and not compressed in the APK."))
                    stop()
                }
            }
        )
    }

    private fun initializeRecognizer() {
        try {
            recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(createVoskListener())
            listener.onReady()
        } catch (e: IOException) {
            listener.onError(e)
            stop()
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
        speechService?.stop()
        speechService = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
