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
        private const val DEFAULT_MODEL_DIR = "vosk-model-small-en-us-0.15"
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

        // Determine the exact asset path, with dynamic fallback
        val modelAssetsPath = resolveModelAssetPath()
        if (modelAssetsPath == null) {
            listener.onError(IOException("No Vosk model folder found in assets/$ASSETS_BASE"))
            stop()
            return
        }

        // Unpack (or re-use existing) model from assets
        StorageService.unpack(
            context,
            modelAssetsPath,
            DEST_DIR,
            { modelInstance ->
                model = modelInstance
                initializeRecognizer()
            },
            { exception ->
                Log.e(TAG, "Failed to unpack model from $modelAssetsPath", exception)
                // Fallback: try to load already unpacked model from internal storage
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
                    listener.onError(IOException("Model not found. Ensure the Vosk model is in assets/$modelAssetsPath and not compressed in the APK."))
                    stop()
                }
            }
        )
    }

    /**
     * Returns the asset path to the model directory, trying the default name first,
     * then dynamically scanning for the first subdirectory inside assets/models/.
     */
    private fun resolveModelAssetPath(): String? {
        // Try the well-known path
        val defaultPath = "$ASSETS_BASE/$DEFAULT_MODEL_DIR"
        if (assetDirectoryExists(defaultPath)) return defaultPath

        // Scan for any model folder
        try {
            val entries = context.assets.list(ASSETS_BASE) ?: return null
            for (entry in entries) {
                val candidatePath = "$ASSETS_BASE/$entry"
                if (assetDirectoryExists(candidatePath)) {
                    Log.i(TAG, "Found model folder via scan: $candidatePath")
                    return candidatePath
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error scanning assets/$ASSETS_BASE", e)
        }
        return null
    }

    /** Checks if an asset path exists and is a directory by listing its contents. */
    private fun assetDirectoryExists(path: String): Boolean {
        return try {
            val list = context.assets.list(path)
            list != null && list.isNotEmpty()
        } catch (e: IOException) {
            false
        }
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
