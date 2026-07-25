package com.voiceassistant

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
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

        Thread {
            try {
                // 1. Find model folder inside assets/models/
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
                    throw IOException(message)
                }

                Log.i(TAG, "Using model assets path: $modelAssetsPath")

                // 2. Prepare clean destination directory
                val destDir = File(context.filesDir, DEST_DIR)
                if (destDir.exists()) {
                    destDir.deleteRecursively()
                }
                if (!destDir.mkdirs()) {
                    throw IOException("Failed to create destination directory: ${destDir.absolutePath}")
                }

                // 3. Copy model from assets to internal storage
                copyAssetFolder(context.assets, modelAssetsPath, destDir.absolutePath)

                Log.i(TAG, "Model copied to ${destDir.absolutePath}")

                // 4. Load model directly from the copied files
                model = Model(destDir.absolutePath)
                initializeRecognizer()

            } catch (e: Exception) {
                Log.e(TAG, "Model initialization failed", e)
                listener.onError(e)
                stop()
            }
        }.start()
    }

    /**
     * Recursively copies all files and folders from the given asset path to the target file system directory.
     */
    private fun copyAssetFolder(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        targetPath: String
    ) {
        val files: Array<String>? = assetManager.list(assetPath)
        if (files == null || files.isEmpty()) {
            // If no children, it might be a single file – try to copy it
            try {
                assetManager.open(assetPath).use { input ->
                    val outFile = File(targetPath)
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                // It's a directory, ignore the copy attempt
            }
            return
        }

        // It's a directory; create it and recurse
        val dir = File(targetPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        for (file in files) {
            val childAssetPath = "$assetPath/$file"
            val childTargetPath = "$targetPath/$file"
            copyAssetFolder(assetManager, childAssetPath, childTargetPath)
        }
    }

    /**
     * Finds the first model folder inside assets/models/ that contains an "am" directory.
     */
    private fun findModelInAssets(): String? {
        try {
            val entries = context.assets.list(ASSETS_BASE) ?: return null
            if (entries.isEmpty()) return null

            // Look for a subdirectory that contains "am"
            for (entry in entries) {
                val path = "$ASSETS_BASE/$entry"
                val subList = context.assets.list(path)
                if (subList != null && subList.contains("am")) {
                    return path
                }
            }

            // Maybe the model files are directly inside assets/models/
            if (entries.contains("am")) {
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
