package com.voiceassistant.model

sealed class CommandAction {
    data class OpenApp(val appName: String) : CommandAction()
    data class Scroll(val direction: Direction) : CommandAction() {
        enum class Direction { UP, DOWN }
    }
    data class TypeText(val text: String) : CommandAction()
}
