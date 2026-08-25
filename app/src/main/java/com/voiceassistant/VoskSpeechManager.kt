package com.voiceassistant

import android.content.Context
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
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

    private var model: Model? = null
    private var speechService: SpeechService? = null

    fun start() {
        if (speechService != null) return

        StorageService.unpack(context, "model-en-us", "model",
            { loadedModel ->
                model = loadedModel
                setupRecognizer()
            },
            { exception ->
                listener.onError(exception)
            }
        )
    }

    private fun setupRecognizer() {
        try {
            // Strict Grammar List for 100% Offline Accuracy
            val grammarList = JSONArray().apply {
                put("camera")
                put("youtube")
                put("chrome")
                put("browser")
                put("open")
                put("launch")
                put("kholo")
                put("chalao")
                put("scroll up")
                put("scroll down")
                put("upar")
                put("niche")
                put("type")
                put("[unk]")
            }

            val recognizer = Recognizer(model, 16000.0f, grammarList.toString())
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis?.let { listener.onPartialResult(extractText(it)) }
                }

                override fun onResult(hypothesis: String?) {
                    hypothesis?.let { listener.onFinalResult(extractText(it)) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    hypothesis?.let { listener.onFinalResult(extractText(it)) }
                }

                override fun onError(exception: java.lang.Exception?) {
                    exception?.let { listener.onError(it) }
                }

                override fun onTimeout() {
                    listener.onTimeout()
                }
            })
            listener.onReady()
        } catch (e: IOException) {
            listener.onError(e)
        }
    }

    private fun extractText(jsonResult: String): String {
        return try {
            val json = org.json.JSONObject(jsonResult)
            json.optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    fun stop() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }
}

