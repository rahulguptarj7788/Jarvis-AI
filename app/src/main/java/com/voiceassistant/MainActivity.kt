package com.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voiceassistant.databinding.ActivityMainBinding

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

        binding.setDefaultAssistantButton.setOnClickListener {
            openDefaultAssistantSettings()
        }

        binding.showAppsButton.setOnClickListener {
            showInstalledAppsDialog()
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
            Toast.makeText(this, "Please enable accessibility service", Toast.LENGTH_LONG).show()
            binding.listenToggleButton.isChecked = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            binding.listenToggleButton.isChecked = false
            return
        }

        isListening = true
        binding.listenToggleButton.isChecked = true

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
                        Toast.makeText(this@MainActivity, "Could not understand: $hypothesis", Toast.LENGTH_SHORT).show()
                    }
                    if (isListening) {
                        voskManager?.stop()
                        voskManager = null
                        startListening()
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
                    if (isListening) {
                        voskManager?.stop()
                        voskManager = null
                        startListening()
                    }
                }
            }
        })

        voskManager?.start()
    }

    private fun stopListening() {
        isListening = false
        voskManager?.stop()
        voskManager = null
        binding.statusTextView.text = getString(R.string.status_not_listening)
        binding.listenToggleButton.isChecked = false
    }

    private fun showInstalledAppsDialog() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appNames = packages.map { app ->
            "${pm.getApplicationLabel(app)} (${app.packageName})"
        }.sorted().toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Installed Apps (${appNames.size})")
            .setItems(appNames, null)
            .setPositiveButton("OK", null)
            .show()
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

    private fun openDefaultAssistantSettings() {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        voskManager?.stop()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 101
    }
}

