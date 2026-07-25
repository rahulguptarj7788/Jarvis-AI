package com.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceassistant.model.CommandAction

class VoiceControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        this.serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun executeCommand(command: CommandAction) {
        when (command) {
            is CommandAction.OpenApp -> openApp(command.appName)
            is CommandAction.Scroll -> scrollScreen(command.direction)
            is CommandAction.TypeText -> typeText(command.text)
        }
    }

    private fun openApp(appName: String) {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(getPackageNameForApp(appName))
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(launchIntent)
        }
    }

    private fun getPackageNameForApp(appName: String): String {
        val lowerName = appName.lowercase()
        return when {
            lowerName.contains("chrome") -> "com.android.chrome"
            lowerName.contains("gmail") -> "com.google.android.gm"
            lowerName.contains("maps") -> "com.google.android.apps.maps"
            lowerName.contains("youtube") -> "com.google.android.youtube"
            lowerName.contains("camera") -> "com.android.camera"
            lowerName.contains("settings") -> "com.android.settings"
            lowerName.contains("calculator") -> "com.android.calculator2"
            lowerName.contains("calendar") -> "com.google.android.calendar"
            lowerName.contains("clock") -> "com.google.android.deskclock"
            else -> "com.android.vending"
        }
    }

    private fun scrollScreen(direction: CommandAction.Scroll.Direction) {
        // Simulate a vertical swipe gesture
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2f
        val startY: Float
        val endY: Float

        // Swipe up -> scroll down (show lower content)
        // Swipe down -> scroll up (show upper content)
        if (direction == CommandAction.Scroll.Direction.DOWN) {
            startY = height * 0.7f
            endY = height * 0.3f
        } else { // UP
            startY = height * 0.3f
            endY = height * 0.7f
        }

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 500))

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focusedNode = findFocusedEditText(root)
        focusedNode?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
            it.recycle()
        }
        root.recycle()
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true && node.isFocused) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditText(child)
            if (result != null) return result
        }
        return null
    }

    companion object {
        var instance: VoiceControlAccessibilityService? = null
    }
}
