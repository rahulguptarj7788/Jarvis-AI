package com.voiceassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.voiceassistant.model.CommandAction
import com.voiceassistant.model.ScrollDirection

class VoiceControlAccessibilityService : AccessibilityService() {

    private lateinit var commandProcessor: VoiceCommandProcessor
    private lateinit var voskManager: VoskSpeechManager

    private var isListening = false
    private var isModelReady = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        commandProcessor = VoiceCommandProcessor(this)
        voskManager = VoskSpeechManager()
        createNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info

        startForeground(NOTIFICATION_ID, buildNotification(false))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopVoiceRecognition()
    }

    override fun onDestroy() {
        voskManager.release()
        stopForeground(true)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for voice control service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(isListening: Boolean): Notification {
        val toggleActionText = if (isListening) "Stop Listening" else "Start Listening"
        val toggleIntent = Intent(this, VoiceControlAccessibilityService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Control Assistant")
            .setContentText(if (isListening) "Listening..." else "Paused")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, toggleActionText, togglePendingIntent)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            toggleListening()
        }
        return START_STICKY
    }

    private fun toggleListening() {
        if (isListening) {
            stopVoiceRecognition()
        } else {
            if (!isModelReady) {
                prepareModelAndStart()
            } else {
                startVoiceRecognition()
            }
        }
    }

    private fun prepareModelAndStart() {
        val assetsPath = "models/vosk-model-small-en-us-0.15"
        voskManager.prepare(
            this,
            assetsPath,
            onReady = {
                isModelReady = true
                voskManager.init(object : VoskSpeechManager.RecognitionCallback {
                    override fun onPartialResult(hypothesis: String) {}
                    override fun onFinalResult(hypothesis: String) {
                        val text = extractTextFromJson(hypothesis)
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Recognized: $text")
                            val action = commandProcessor.process(text)
                            action?.let { executeAction(it) }
                        }
                    }
                    override fun onError(exception: Exception) {
                        Log.e(TAG, "Recognition error", exception)
                    }
                })
                startVoiceRecognition()
            },
            onError = { e ->
                Log.e(TAG, "Model preparation failed", e)
                isListening = false
                updateNotification()
            }
        )
    }

    private fun startVoiceRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("request_permission", true)
                }
                startActivity(intent)
                isListening = false
                updateNotification()
                return
            }
        }
        isListening = true
        voskManager.startListening()
        updateNotification()
    }

    private fun stopVoiceRecognition() {
        isListening = false
        voskManager.stopListening()
        updateNotification()
    }

    private fun updateNotification() {
        val notification = buildNotification(isListening)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun extractTextFromJson(json: String): String {
        return try {
            val key = "\"text\""
            val start = json.indexOf(key)
            if (start == -1) return ""
            val colon = json.indexOf(":", start)
            if (colon == -1) return ""
            val firstQuote = json.indexOf("\"", colon + 1)
            val lastQuote = json.indexOf("\"", firstQuote + 1)
            if (firstQuote == -1 || lastQuote == -1) return ""
            json.substring(firstQuote + 1, lastQuote).trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun executeAction(action: CommandAction) {
        when (action) {
            is CommandAction.LaunchApp -> launchApp(action.packageName)
            is CommandAction.Scroll -> scrollScreen(action.direction)
            is CommandAction.TypeText -> typeTextInFocusedField(action.text)
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Log.w(TAG, "No launch intent for $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app", e)
        }
    }

    private fun scrollScreen(direction: ScrollDirection) {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val action = when (direction) {
                ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) {
                scrollable.recycle()
                return
            }
            scrollable.recycle()
        }
        performScrollGesture(direction)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun performScrollGesture(direction: ScrollDirection) {
        val displayMetrics = resources.displayMetrics
        val midX = displayMetrics.widthPixels / 2f
        val midY = displayMetrics.heightPixels / 2f
        val swipeDistance = 500f
        val path = Path()
        path.moveTo(midX, midY)
        when (direction) {
            ScrollDirection.UP -> path.lineTo(midX, midY + swipeDistance)
            ScrollDirection.DOWN -> path.lineTo(midX, midY - swipeDistance)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun typeTextInFocusedField(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            // Attempt ACTION_SET_TEXT first
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                focused.recycle()
                return
            }

            // Clipboard fallback
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("voice_input", text)
                clipboard.setPrimaryClip(clip)
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard paste fallback failed", e)
            }
            focused.recycle()
        }
    }

    companion object {
        private const val TAG = "VoiceControlService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_control_channel"
        private const val ACTION_TOGGLE = "com.voiceassistant.TOGGLE_LISTENING"
    }
}
