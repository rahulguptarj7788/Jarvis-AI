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
        private const val ASSETS_BASE = "models"
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

        // 1. Find the model folder inside assets/models/
        val modelAssetsPath = findModelInAssets()
        if (modelAssetsPath == null) {
            val message = buildString {
                append("No Vosk model folder found in assets/$ASSETS_BASE. ")
                append("Detected contents: ")
                try {
                    val entries = context.assets.list(ASSETS_BASE)
                    append(entries?.joinToString(", ") ?: "NULL")
                } catch (e: Exception) {
                    append("error listing: ${e.message}")
                }
            }
            listener.onError(IOException(message))
            stop()
            return
        }

        Log.i(TAG, "Using model assets path: $modelAssetsPath")

        // 2. Unpack (or reuse) the model
        StorageService.unpack(
            context,
            modelAssetsPath,
            DEST_DIR,
            { modelInstance ->
                model = modelInstance
                initializeRecognizer()
            },
            { exception ->
                Log.e(TAG, "StorageService.unpack failed for $modelAssetsPath", exception)
                // Fallback: try to load an already unpacked model from previous run
                val existingDir = File(context.filesDir, DEST_DIR)
                if (existingDir.exists() && File(existingDir, "am").exists()) {
                    try {
                        model = Model(existingDir.absolutePath)
                        initializeRecognizer()
                    } catch (e: IOException) {
                        listener.onError(e)
                        stop()
                    }
                } else {
                    listener.onError(IOException("Model unpacking failed: ${exception.message}"))
                    stop()
                }
            }
        )
    }

    /**
     * Scans assets/models/ and returns the path of the first subdirectory that looks like a model
     * (contains an "am" directory). If no subdirs exist, it returns "models" if the base directory itself
     * contains model files.
     */
    private fun findModelInAssets(): String? {
        try {
            val entries = context.assets.list(ASSETS_BASE) ?: return null
            if (entries.isEmpty()) return null

            // Check each entry to see if it's a directory containing an "am" subdirectory
            for (entry in entries) {
                val path = "$ASSETS_BASE/$entry"
                val subList = context.assets.list(path)
                if (subList != null && subList.contains("am")) {
                    Log.i(TAG, "Found model directory: $path")
                    return path
                }
            }

            // If none have "am", maybe the model files are directly inside assets/models/
            if (entries.contains("am")) {
                Log.i(TAG, "Model files directly inside $ASSETS_BASE")
                return ASSETS_BASE
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error listing assets/$ASSETS_BASE", e)
        }
        return null
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
