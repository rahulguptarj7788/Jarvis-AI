package com.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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

    // -----------------------------------------------------------------
    // App launching with fuzzy matching
    // -----------------------------------------------------------------
    private fun openApp(appName: String) {
        val query = cleanQuery(appName)
        if (query.isEmpty()) {
            showAppNotFoundToast()
            return
        }

        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolvedApps = pm.queryIntentActivities(mainIntent, 0)

        var bestMatch: android.content.pm.ResolveInfo? = null
        var bestScore = 0.0

        for (app in resolvedApps) {
            val label = app.loadLabel(pm).toString().lowercase().trim()
            if (label.isEmpty()) continue

            val partialBonus = if (label.contains(query) || query.contains(label)) 0.3 else 0.0
            val score = similarity(query, label) + partialBonus

            if (score > bestScore) {
                bestScore = score
                bestMatch = app
            }
        }

        if (bestMatch != null && bestScore >= 0.6) {
            val packageName = bestMatch.activityInfo.packageName
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }
        }

        showAppNotFoundToast()
    }

    private fun cleanQuery(rawInput: String): String {
        val stopWords = listOf("open", "launch", "start", "go to")
        var cleaned = rawInput.lowercase().trim()
        for (word in stopWords) {
            cleaned = cleaned.removePrefix(word).trim()
        }
        return cleaned
    }

    private fun showAppNotFoundToast() {
        android.os.Handler(mainLooper).post {
            Toast.makeText(
                applicationContext,
                "App not found. Please download it or specify its web version.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    // -----------------------------------------------------------------
    // Scroll
    // -----------------------------------------------------------------
    private fun scrollScreen(direction: CommandAction.Scroll.Direction) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2f
        val startY: Float
        val endY: Float

        if (direction == CommandAction.Scroll.Direction.DOWN) {
            startY = height * 0.75f
            endY = height * 0.35f
        } else {
            startY = height * 0.35f
            endY = height * 0.75f
        }

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 500))

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    // -----------------------------------------------------------------
    // Type text
    // -----------------------------------------------------------------
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
