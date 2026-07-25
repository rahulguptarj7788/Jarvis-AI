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
        private const val REQUIRED_FILE_AM = "am/final.mdl"
        private const val REQUIRED_FILE_CONF = "conf/model.conf"
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

        Thread {
            try {
                val destDir = File(context.filesDir, DEST_DIR)

                // Ensure model is complete; if not, delete and re‑copy
                if (!modelDirectoryLooksValid(destDir)) {
                    Log.w(TAG, "Model directory missing essential files. Deleting and re‑copying.")
                    destDir.deleteRecursively()
                    copyModelFromAssets(destDir)
                    if (!modelDirectoryLooksValid(destDir)) {
                        logDirectoryTree(destDir, 0)
                        throw IOException("Failed to copy complete model from assets. Check logs.")
                    }
                }

                // Load model – wrapped heavily for native crashes
                model = loadModelSafely(destDir.absolutePath)

                recognizer = createRecognizerSafely(model)

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

    // ---------------------------------------------------------------------------------
    // Model directory validation
    // ---------------------------------------------------------------------------------
    private fun modelDirectoryLooksValid(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val amFinalMdl = File(dir, REQUIRED_FILE_AM)
        val confModelConf = File(dir, REQUIRED_FILE_CONF)
        return amFinalMdl.exists() && amFinalMdl.length() > 0 &&
                confModelConf.exists() && confModelConf.length() > 0
    }

    private fun logDirectoryTree(dir: File, depth: Int) {
        if (!dir.exists()) return
        val prefix = "  ".repeat(depth)
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isDirectory) {
                Log.d(TAG, "$prefix[DIR]  ${file.name}")
                logDirectoryTree(file, depth + 1)
            } else {
                Log.d(TAG, "$prefix[FILE] ${file.name}  (${file.length()} bytes)")
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Copy model from assets with robust recursion
    // ---------------------------------------------------------------------------------
    private fun copyModelFromAssets(destDir: File) {
        val modelAssetsPath = findModelInAssets()
            ?: throw IOException("No Vosk model folder found in assets/$ASSETS_BASE")

        if (!destDir.mkdirs()) {
            throw IOException("Cannot create model directory: ${destDir.absolutePath}")
        }

        try {
            copyAssetFolder(context.assets, modelAssetsPath, destDir.absolutePath)
        } catch (e: IOException) {
            Log.e(TAG, "Error copying assets", e)
            throw e
        }

        Log.i(TAG, "Model copied to ${destDir.absolutePath}")
        logDirectoryTree(destDir, 0)
    }

    /**
     * Recursively copies assets from [assetPath] to [targetPath] on disk.
     * This version correctly distinguishes files from directories by calling
     * assetManager.list(). If list() returns null or an empty array, the path
     * is treated as a file. Otherwise it's a directory and we create the
     * corresponding target directory before recursing into its children.
     */
    private fun copyAssetFolder(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        targetPath: String
    ) {
        val childNames = assetManager.list(assetPath)
        if (childNames == null || childNames.isEmpty()) {
            // It's a file (or empty directory). Treat as file and copy.
            try {
                assetManager.open(assetPath).use { input ->
                    val outFile = File(targetPath)
                    // Ensure parent directory exists
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                // Possibly an empty directory – ignore
                Log.w(TAG, "Failed to copy asset file $assetPath -> $targetPath", e)
            }
            return
        }

        // It's a directory: create the target directory and recurse
        val targetDir = File(targetPath)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (child in childNames) {
            val childAsset = "$assetPath/$child"
            val childTarget = "$targetPath/$child"
            copyAssetFolder(assetManager, childAsset, childTarget)
        }
    }

    /**
     * Scans assets/models/ for a subdirectory that contains an "am" folder.
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
            // Maybe model files are directly inside models/
            if (entries.contains("am")) {
                return ASSETS_BASE
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error scanning assets/$ASSETS_BASE", e)
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // Safe Vosk object creation
    // ---------------------------------------------------------------------------------
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

    // ---------------------------------------------------------------------------------
    // Speech listener and shutdown
    // ---------------------------------------------------------------------------------
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
