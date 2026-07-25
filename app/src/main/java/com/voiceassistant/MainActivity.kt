package com.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voiceassistant.databinding.ActivityMainBinding
import com.voiceassistant.model.CommandAction

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var voskManager: VoskSpeechManager? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listenToggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startListening() else stopListening()
        }

        binding.accessibilitySettingsButton.setOnClickListener {
            openAccessibilitySettings()
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    private fun startListening() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable the accessibility service first", Toast.LENGTH_LONG).show()
            binding.listenToggleButton.isChecked = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            binding.listenToggleButton.isChecked = false
            return
        }

        voskManager = VoskSpeechManager(this, object : VoskSpeechManager.RecognitionListener {
            override fun onReady() {
                runOnUiThread {
                    binding.statusTextView.text = getString(R.string.status_listening)
                }
            }

            override fun onPartialResult(hypothesis: String) {
                runOnUiThread {
                    binding.commandFeedbackTextView.text = hypothesis
                }
            }

            override fun onFinalResult(hypothesis: String) {
                runOnUiThread {
                    binding.commandFeedbackTextView.text = "Command: $hypothesis"
                    val command = VoiceCommandProcessor.parse(hypothesis)
                    if (command != null) {
                        VoiceControlAccessibilityService.instance?.executeCommand(command)
                    } else {
                        Toast.makeText(this@MainActivity, "Could not understand command", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Speech error: ${exception.message}", Toast.LENGTH_LONG).show()
                    stopListening()
                }
            }

            override fun onTimeout() {
                runOnUiThread {
                    stopListening()
                }
            }
        })

        voskManager?.start()
        isListening = true
    }

    private fun stopListening() {
        voskManager?.stop()
        voskManager = null
        isListening = false
        binding.statusTextView.text = getString(R.string.status_not_listening)
        binding.listenToggleButton.isChecked = false
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + VoiceControlAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        voskManager?.stop()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 101
    }
}
