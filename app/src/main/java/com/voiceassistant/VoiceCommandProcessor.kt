package com.voiceassistant

import com.voiceassistant.model.CommandAction

object VoiceCommandProcessor {

    fun parse(text: String): CommandAction? {
        val normalized = text.lowercase().trim()

        // "open <app>"
        val openRegex = Regex("""^open\s+(.+)$""")
        openRegex.matchEntire(normalized)?.let {
            val appName = it.groupValues[1].trim()
            if (appName.isNotEmpty()) return CommandAction.OpenApp(appName)
        }

        // "scroll up" / "scroll down"
        if (normalized == "scroll up") return CommandAction.Scroll(CommandAction.Scroll.Direction.UP)
        if (normalized == "scroll down") return CommandAction.Scroll(CommandAction.Scroll.Direction.DOWN)

        // "type <text>"
        val typeRegex = Regex("""^type\s+(.+)$""")
        typeRegex.matchEntire(normalized)?.let {
            val textToType = it.groupValues[1].trim()
            if (textToType.isNotEmpty()) return CommandAction.TypeText(textToType)
        }

        return null
    }
}
