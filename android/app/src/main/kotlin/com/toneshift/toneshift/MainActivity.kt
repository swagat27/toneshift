package com.toneshift.toneshift

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flutter dashboard bridge.
 * Channel: com.toneshift/native
 * All storage goes through SecureStore (native), never through Flutter
 * storage, so ProcessTextActivity and the AccessibilityService always
 * see the same key and presets without waking Flutter.
 */
class MainActivity : FlutterActivity() {

    private val channelName = "com.toneshift/native"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "saveApiKey" -> {
                        val key = call.argument<String>("key").orEmpty()
                        SecureStore.setApiKey(this, key)
                        result.success(true)
                    }
                    "clearApiKey" -> {
                        SecureStore.clearApiKey(this)
                        result.success(true)
                    }
                    "hasApiKey" -> {
                        result.success(SecureStore.getApiKey(this) != null)
                    }
                    "getMaskedApiKey" -> {
                        val key = SecureStore.getApiKey(this)
                        result.success(if (key == null) null else maskKey(key))
                    }
                    "testApiKey" -> {
                        val key = call.argument<String>("key")
                            ?: SecureStore.getApiKey(this)
                        if (key.isNullOrBlank()) {
                            result.error("NO_KEY", "No API key provided.", null)
                        } else {
                            scope.launch {
                                try {
                                    val msg = GeminiClient.testApiKey(this@MainActivity, key)
                                    result.success(msg)
                                } catch (e: GeminiClient.GeminiException) {
                                    result.error("TEST_FAILED", e.message, null)
                                }
                            }
                        }
                    }
                    "getPresets" -> {
                        result.success(SecureStore.presetsToJson(SecureStore.getPresets(this)))
                    }
                    "savePresets" -> {
                        val json = call.argument<String>("json").orEmpty()
                        try {
                            SecureStore.setPresets(this, SecureStore.presetsFromJson(json))
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("BAD_PRESETS", e.message, null)
                        }
                    }
                    "resetPresets" -> {
                        SecureStore.setPresets(this, SecureStore.defaultPresets)
                        result.success(SecureStore.presetsToJson(SecureStore.defaultPresets))
                    }
                    "isAccessibilityEnabled" -> {
                        result.success(isAccessibilityServiceEnabled(this))
                    }
                    "openAccessibilitySettings" -> {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    }
                    "getUsageLog" -> {
                        result.success(SecureStore.usageLogJson(this))
                    }
                    "clearUsageLog" -> {
                        SecureStore.clearUsageLog(this)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun maskKey(key: String): String {
        if (key.length <= 8) return "********"
        return key.substring(0, 4) + "..." + key.substring(key.length - 4)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ToneShiftAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
