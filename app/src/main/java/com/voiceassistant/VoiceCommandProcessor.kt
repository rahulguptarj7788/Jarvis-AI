package com.voiceassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.voiceassistant.model.CommandAction
import com.voiceassistant.model.ScrollDirection

class VoiceCommandProcessor(private val context: Context) {

    private val appCache = mutableMapOf<String, String>() // label.lowercase() -> packageName

    fun process(command: String): CommandAction? {
        val trimmed = command.trim().lowercase()
        return when {
            trimmed.startsWith("open ") -> {
                val appName = trimmed.removePrefix("open ").trim()
                val pkg = resolvePackageName(appName)
                if (pkg != null) CommandAction.LaunchApp(pkg) else null
            }
            trimmed == "scroll up" -> CommandAction.Scroll(ScrollDirection.UP)
            trimmed == "scroll down" -> CommandAction.Scroll(ScrollDirection.DOWN)
            trimmed.startsWith("type ") -> {
                val text = command.trim().removePrefix("type ").trim()
                if (text.isNotBlank()) CommandAction.TypeText(text) else null
            }
            else -> null
        }
    }

    private fun resolvePackageName(appName: String): String? {
        if (appCache.isEmpty()) populateAppCache()
        appCache[appName]?.let { return it }
        return appCache.entries.firstOrNull { (label, _) ->
            label.contains(appName) || appName.contains(label)
        }?.value
    }

    private fun populateAppCache() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        for (ri in resolved) {
            val label = ri.loadLabel(pm).toString().lowercase()
            val pkg = ri.activityInfo.packageName
            appCache[label] = pkg
        }
    }
}
