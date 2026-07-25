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

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError(SecurityException("Microphone permission not granted"))
            stop()
            return
        }

        Thread {
            try {
                val destDir = File(context.filesDir, DEST_DIR)

                // Always copy model fresh to avoid any corruption
                copyModelFromAssetsFresh(destDir)

                // Validate essential files exist
                if (!isModelValid(destDir)) {
                    throw IOException("Model files incomplete after copy. Check logs.")
                }

                // Load model
                model = loadModelSafely(destDir.absolutePath)

                // Create recognizer
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

    // ---------------------------------------------------------------
    // Model validation & file listing
    // ---------------------------------------------------------------
    private fun isModelValid(modelDir: File): Boolean {
        val amFinalMdl = File(modelDir, "am/final.mdl")
        val conf = File(modelDir, "conf/model.conf")
        return amFinalMdl.exists() && amFinalMdl.length() > 0 &&
                conf.exists() && conf.length() > 0
    }

    private fun logTree(dir: File, depth: Int) {
        if (!dir.exists()) return
        val prefix = "  ".repeat(depth)
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.isDirectory) {
                Log.d(TAG, "$prefix[DIR ] ${f.name}")
                logTree(f, depth + 1)
            } else {
                Log.d(TAG, "$prefix[FILE] ${f.name} (${f.length()} bytes)")
            }
        }
    }

    // ---------------------------------------------------------------
    // Fresh copy of model from assets with perfect directory replication
    // ---------------------------------------------------------------
    private fun copyModelFromAssetsFresh(destDir: File) {
        // Find model path in assets
        val assetModelPath = findModelInAssets()
            ?: throw IOException("No model folder found in assets/$ASSETS_BASE")

        // Delete any previous model
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        if (!destDir.mkdirs()) {
            throw IOException("Cannot create model directory: ${destDir.absolutePath}")
        }

        // Use iterative BFS copy to ensure all directories and files are reproduced
        copyAssetPathIterative(assetModelPath, destDir.absolutePath)

        Log.i(TAG, "Model copied to ${destDir.absolutePath}")
        logTree(destDir, 0)
    }

    /**
     * Iteratively copies all assets under [assetPath] to [targetPath] on disk.
     * Handles both files and empty directories correctly.
     */
    private fun copyAssetPathIterative(assetPath: String, targetPath: String) {
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(Pair(assetPath, targetPath))

        while (queue.isNotEmpty()) {
            val (currentAsset, currentTarget) = queue.removeFirst()

            val children = context.assets.list(currentAsset)
            if (children == null) {
                // It's a file, copy it
                try {
                    context.assets.open(currentAsset).use { input ->
                        val outFile = File(currentTarget)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy asset file $currentAsset", e)
                }
            } else {
                // It's a directory – create the target directory even if empty
                val targetDir = File(currentTarget)
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory $currentTarget")
                }
                // Enqueue all children
                for (child in children) {
                    queue.addLast(Pair("$currentAsset/$child", "$currentTarget/$child"))
                }
            }
        }
    }

    /**
     * Scans assets/models/ for a subdirectory that contains an "am" folder.
     */
    private fun findModelInAssets(): String? {
        try {
            val entries = context.assets.list(ASSETS_BASE) ?: return null
            for (entry in entries) {
                val path = "$ASSETS_BASE/$entry"
                val sub = context.assets.list(path)
                if (sub != null && sub.contains("am")) {
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

    // ---------------------------------------------------------------
    // Safe Vosk object creation
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // Speech listener and shutdown
    // ---------------------------------------------------------------
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
