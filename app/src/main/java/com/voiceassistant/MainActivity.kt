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
