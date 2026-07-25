package com.voiceassistant

import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

class VoskSpeechManager(
    private val modelPath: String,
    private val listener: RecognitionListener
) {
    interface RecognitionListener {
        fun onPartialResult(hypothesis: String)
        fun onFinalResult(hypothesis: String)
        fun onError(exception: Exception)
        fun onTimeout()
    }

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        try {
            model = Model(modelPath)
            recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : org.vosk.android.RecognitionListener {
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
            })
            isRunning = true
        } catch (e: IOException) {
            listener.onError(e)
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
