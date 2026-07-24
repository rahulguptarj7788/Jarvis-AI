package com.voiceassistant.model

sealed class CommandAction {
    data class LaunchApp(val packageName: String) : CommandAction()
    data class Scroll(val direction: ScrollDirection) : CommandAction()
    data class TypeText(val text: String) : CommandAction()
}

enum class ScrollDirection { UP, DOWN }
