package com.voiceassistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

/**
 * Manages offline speech recognition using Vosk.
 * Handles unpacking the model from assets to internal storage automatically.
 */
class VoskSpeechManager(private val sampleRate: Float = 16000.0f) {

    interface RecognitionCallback {
        fun onPartialResult(hypothesis: String)
        fun onFinalResult(hypothesis: String)
        fun onError(exception: Exception)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioThread: Thread? = null
    private var isRunning = false
    private var callback: RecognitionCallback? = null
    private var modelPath: String? = null

    @Volatile
    private var shouldStop = false

    /**
     * Unpack the Vosk model from assets into internal storage.
     * After successful unpacking, [onReady] is called on the main thread.
     * [onError] is also called on the main thread if unpacking fails.
     */
    fun prepare(context: Context, assetsModelPath: String, onReady: () -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                val targetDir = File(context.filesDir, "vosk_model").absolutePath
                Log.d(TAG, "Unpacking model from $assetsModelPath to $targetDir")
                StorageService.unpack(context, assetsModelPath, targetDir,
                    { unpackedModel ->
                        // This runs on a background thread from Vosk SDK
                        this.model = unpackedModel
                        this.modelPath = targetDir
                        Log.d(TAG, "Model unpacked successfully")
                        // Notify on the main thread
                        Handler(Looper.getMainLooper()).post { onReady.invoke() }
                    },
                    { e ->
                        Log.e(TAG, "Unpacking failed", e)
                        Handler(Looper.getMainLooper()).post {
                            onError.invoke(IOException("Failed to unpack model: ${e.message}"))
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during model preparation", e)
                Handler(Looper.getMainLooper()).post { onError.invoke(e) }
            }
        }.start()
    }

    /**
     * Initialise the recognizer. Must be called after the model has been prepared.
     */
    fun init(callback: RecognitionCallback) {
        this.callback = callback
        if (model == null) {
            throw IllegalStateException("Model not prepared. Call prepare() first.")
        }
        try {
            recognizer = Recognizer(model, sampleRate)
        } catch (e: IOException) {
            callback.onError(e)
            Log.e(TAG, "Failed to create recognizer", e)
        }
    }

    fun startListening() {
        if (isRunning) return
        if (recognizer == null) throw IllegalStateException("Recognizer not initialized. Call init() first.")
        isRunning = true
        shouldStop = false

        audioThread = Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw RuntimeException("AudioRecord not initialised")
                }
                recorder.startRecording()

                val buffer = ShortArray(bufferSize)
                while (!shouldStop) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) throw RuntimeException("AudioRecord read error")
                    if (recognizer!!.acceptWaveForm(buffer, read)) {
                        callback?.onFinalResult(recognizer!!.result)
                    } else {
                        callback?.onPartialResult(recognizer!!.partialResult)
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                callback?.onError(e)
                Log.e(TAG, "Audio thread error", e)
            }
        }.apply {
            name = "VoskAudioThread"
            start()
        }
    }

    fun stopListening() {
        shouldStop = true
        audioThread?.interrupt()
        audioThread = null
        isRunning = false
    }

    fun release() {
        stopListening()
        recognizer?.close()
        model?.close()
    }

    companion object {
        private const val TAG = "VoskSpeechManager"
    }
}
