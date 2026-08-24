package com.voiceassistant

import com.voiceassistant.model.CommandAction

object VoiceCommandProcessor {

    fun parse(text: String): CommandAction? {
        val normalized = text.lowercase().trim()

        if (normalized.isEmpty()) return null

        // Flexible Open App Matching (Direct Keyword Detection)
        if (normalized.contains("camera") || normalized.contains("kamra")) {
            return CommandAction.OpenApp("camera")
        }
        if (normalized.contains("youtube") || normalized.contains("utube") || normalized.contains("you tube")) {
            return CommandAction.OpenApp("youtube")
        }
        if (normalized.contains("chrome") || normalized.contains("browser")) {
            return CommandAction.OpenApp("chrome")
        }

        // Standard Open Prefix Matching
        val openPrefixes = listOf("open ", "launch ", "kholo ", "chalao ")
        for (prefix in openPrefixes) {
            if (normalized.startsWith(prefix)) {
                val appName = normalized.removePrefix(prefix).trim()
                if (appName.isNotEmpty()) return CommandAction.OpenApp(appName)
            }
        }

        // Scroll Actions
        if (normalized.contains("scroll up") || normalized.contains("upar")) {
            return CommandAction.Scroll(CommandAction.Scroll.Direction.UP)
        }
        if (normalized.contains("scroll down") || normalized.contains("niche")) {
            return CommandAction.Scroll(CommandAction.Scroll.Direction.DOWN)
        }

        // Type Action
        if (normalized.startsWith("type ")) {
            val textToType = normalized.removePrefix("type ").trim()
            if (textToType.isNotEmpty()) return CommandAction.TypeText(textToType)
        }

        return null
    }
}

