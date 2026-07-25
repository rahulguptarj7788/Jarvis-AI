package com.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
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

        // Check microphone permission before any heavy work
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError(SecurityException("Microphone permission (RECORD_AUDIO) not granted"))
            stop()
            return
        }

        Thread {
            try {
                ensureModelReady()
                val destDir = File(context.filesDir, DEST_DIR)
                if (!modelDirectoryLooksValid(destDir)) {
                    // Something is corrupted, delete and re‑copy
                    destDir.deleteRecursively()
                    copyModelFromAssets(destDir)
                    if (!modelDirectoryLooksValid(destDir)) {
                        throw IOException("Model files incomplete after copy from assets")
                    }
                }

                // Load the model – native code might crash, wrap in catch all
                model = loadModelSafely(destDir.absolutePath)

                // Create recognizer – also wrapped
                recognizer = createRecognizerSafely(model)

                // Start speech service
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(createVoskListener())

                listener.onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vosk", e)
                listener.onError(e)
                stop()
            } catch (e: Error) {
                Log.e(TAG, "Native error during Vosk initialization", e)
                listener.onError(RuntimeException("Vosk native error: ${e.message}"))
                stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected throwable", t)
                listener.onError(RuntimeException("Unexpected error: ${t.message}"))
                stop()
            }
        }.start()
    }

    /**
     * Ensures the model directory exists and contains valid model files.
     */
    private fun ensureModelReady() {
        val destDir = File(context.filesDir, DEST_DIR)
        if (!destDir.exists() || !modelDirectoryLooksValid(destDir)) {
            // Delete any partial data
            destDir.deleteRecursively()
            copyModelFromAssets(destDir)
        }
    }

    /**
     * Copies the model from assets/models/ to the internal storage destination.
     */
    private fun copyModelFromAssets(destDir: File) {
        val modelAssetsPath = findModelInAssets()
            ?: throw IOException("No Vosk model folder found in assets/$ASSETS_BASE")

        if (!destDir.mkdirs()) {
            throw IOException("Cannot create model directory: ${destDir.absolutePath}")
        }

        copyAssetFolder(context.assets, modelAssetsPath, destDir.absolutePath)
        Log.i(TAG, "Model copied to ${destDir.absolutePath}")
    }

    /**
     * Checks that the model directory contains the essential file "am/final.mdl"
     * or at least the "am" subdirectory with some files.
     */
    private fun modelDirectoryLooksValid(modelDir: File): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        val amDir = File(modelDir, "am")
        // Minimum check: am directory exists and contains final.mdl
        if (amDir.exists() && amDir.isDirectory) {
            val finalMdl = File(amDir, "final.mdl")
            if (finalMdl.exists() && finalMdl.length() > 0) return true
        }
        // Alternative: some models have "ivector" or "conf" instead; we'll be strict
        Log.w(TAG, "Model directory missing essential files: ${modelDir.absolutePath}")
        return false
    }

    /**
     * Loads a Vosk Model safely, catching native crashes.
     */
    private fun loadModelSafely(path: String): Model? {
        return try {
            Model(path)
        } catch (e: Exception) {
            Log.e(TAG, "Model(path) threw Exception", e)
            throw e
        } catch (e: Error) {
            Log.e(TAG, "Model(path) threw Error (native crash)", e)
            throw RuntimeException("Native crash while loading model", e)
        }
    }

    /**
     * Creates a Recognizer from a Model safely.
     */
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

    /**
     * Recursively copies asset files/folders to the target path.
     */
    private fun copyAssetFolder(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        targetPath: String
    ) {
        val files: Array<String>? = assetManager.list(assetPath)
        if (files == null || files.isEmpty()) {
            // It's a single file
            try {
                assetManager.open(assetPath).use { input ->
                    val outFile = File(targetPath)
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                // Might be an empty directory, ignore
            }
            return
        }

        val dir = File(targetPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        for (file in files) {
            val childAsset = "$assetPath/$file"
            val childTarget = "$targetPath/$file"
            copyAssetFolder(assetManager, childAsset, childTarget)
        }
    }

    /**
     * Scans assets/models/ for a subdirectory containing an "am" folder.
     */
    private fun findModelInAssets(): String? {
        try {
            val entries = context.assets.list(ASSETS_BASE) ?: return null
            if (entries.isEmpty()) return null

            for (entry in entries) {
                val path = "$ASSETS_BASE/$entry"
                val subList = context.assets.list(path)
                if (subList != null && subList.contains("am")) {
                    return path
                }
            }
            // Maybe model files are directly in models/
            if (entries.contains("am")) {
                return ASSETS_BASE
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error scanning assets/$ASSETS_BASE", e)
        }
        return null
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
        try {
            speechService?.stop()
        } catch (_: Exception) {}
        speechService = null

        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null

        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
    }
}
