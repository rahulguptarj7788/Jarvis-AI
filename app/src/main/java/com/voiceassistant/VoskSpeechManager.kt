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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val DEST_DIR = "model"
        private const val REQUIRED_FILE = "am/final.mdl"
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
                val modelDir = File(context.filesDir, DEST_DIR)
                if (!isModelValid(modelDir)) {
                    modelDir.deleteRecursively()
                    downloadAndUnzipModel(modelDir)
                    if (!isModelValid(modelDir)) {
                        throw IOException("Model download succeeded but required files missing")
                    }
                }

                model = loadModelSafely(modelDir.absolutePath)
                recognizer = createRecognizerSafely(model)

                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(createVoskListener())

                listener.onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vosk", e)
                listener.onError(e)
                stop()
            } catch (e: Error) {
                Log.e(TAG, "Native error", e)
                listener.onError(RuntimeException("Vosk native error: ${e.message}"))
                stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected throwable", t)
                listener.onError(RuntimeException("Unexpected: ${t.message}"))
                stop()
            }
        }.start()
    }

    private fun isModelValid(modelDir: File): Boolean {
        val amFinalMdl = File(modelDir, REQUIRED_FILE)
        val conf = File(modelDir, "conf/model.conf")
        return amFinalMdl.exists() && amFinalMdl.length() > 0 &&
                conf.exists() && conf.length() > 0
    }

    private fun downloadAndUnzipModel(targetDir: File) {
        val zipFile = File(context.cacheDir, "vosk-model.zip")
        try {
            Log.i(TAG, "Downloading model from $MODEL_URL")
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed with HTTP ${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Downloaded model: ${zipFile.length()} bytes")

            if (!targetDir.mkdirs()) throw IOException("Cannot create model dir")
            unzip(zipFile, targetDir)
            Log.i(TAG, "Unzipped model to ${targetDir.absolutePath}")
        } finally {
            zipFile.delete()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output -> zis.copyTo(output) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun loadModelSafely(path: String): Model? {
        return try {
            Model(path)
        } catch (e: Exception) {
            Log.e(TAG, "Model(path) Exception", e)
            throw e
        } catch (e: Error) {
            Log.e(TAG, "Model(path) Error (native crash)", e)
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
            Log.e(TAG, "Recognizer creation crashed", e)
            throw RuntimeException("Native crash creating recognizer", e)
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
