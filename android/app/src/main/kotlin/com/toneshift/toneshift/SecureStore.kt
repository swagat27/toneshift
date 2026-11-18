package com.toneshift.toneshift

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the API key, presets and usage log.
 * Both the Flutter UI (via MethodChannel in MainActivity) and the native
 * background pieces (ProcessTextActivity, AccessibilityService) read and
 * write here, so the key never has to wake the Flutter engine.
 */
object SecureStore {
    private const val TAG = "ToneShiftStore"
    private const val FILE = "toneshift_secure_prefs"
    private const val FALLBACK_FILE = "toneshift_prefs_fallback"

    private const val KEY_API = "gemini_api_key"
    private const val KEY_PRESETS = "presets_json"
    private const val KEY_LOG = "usage_log_json"

    data class Preset(val id: String, val label: String, val instruction: String)

    val defaultPresets: List<Preset> = listOf(
        Preset("SHORT", "Shorten", "Condense to the shortest, punchiest version possible (under 50 percent of the original length) while keeping the exact point intact. Cut all fluff and repetition."),
        Preset("FORMAL", "Formal", "Rewrite into polished, sharp, professional communication suitable for senior management, professors, or official emails."),
        Preset("GRAMMAR", "Grammar Fix", "Fix all grammar, spelling, punctuation, and awkward phrasing while keeping my natural voice and casual tone unchanged."),
        Preset("BULLET", "Professional Bullet", "Rewrite as a single clean, professional bullet point suitable for a resume, report, or LinkedIn post. Start directly with a strong action verb, no bullet symbol in the output."),
        Preset("POLITE_NO", "Polite No", "Turn this rough refusal or delay into a polite, diplomatic, yet firm message.")
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created: SharedPreferences = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted prefs failed, using fallback", e)
                context.applicationContext.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
            prefs = created
            return created
        }
    }

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API).apply()
    }

    fun getPresets(context: Context): List<Preset> {
        val raw = prefs(context).getString(KEY_PRESETS, null) ?: return defaultPresets
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Preset>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Preset(o.getString("id"), o.getString("label"), o.getString("instruction")))
            }
            if (out.isEmpty()) defaultPresets else out
        } catch (e: Exception) {
            Log.e(TAG, "Preset parse failed", e)
            defaultPresets
        }
    }

    fun setPresets(context: Context, presets: List<Preset>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("instruction", p.instruction)
            })
        }
        prefs(context).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun presetsToJson(presets: List<Preset>): String {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("id", p.id); put("label", p.label); put("instruction", p.instruction)
            })
        }
        return arr.toString()
    }

    fun presetsFromJson(raw: String): List<Preset> {
        val arr = JSONArray(raw)
        val out = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Preset(o.optString("id"), o.optString("label"), o.optString("instruction")))
        }
        return out
    }

    fun logUsage(context: Context, mode: String, inputChars: Int, outputChars: Int, success: Boolean) {
        try {
            val current = getUsageLog(context).toMutableList()
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("mode", mode)
                put("inputChars", inputChars)
                put("outputChars", outputChars)
                put("success", success)
            }
            current.add(0, entry)
            val capped = JSONArray()
            for (i in 0 until minOf(current.size, 100)) capped.put(current[i])
            prefs(context).edit().putString(KEY_LOG, capped.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Usage log failed", e)
        }
    }

    fun getUsageLog(context: Context): List<JSONObject> {
        val raw = prefs(context).getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun usageLogJson(context: Context): String {
        val arr = JSONArray()
        for (e in getUsageLog(context)) arr.put(e)
        return arr.toString()
    }

    fun clearUsageLog(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }
}
