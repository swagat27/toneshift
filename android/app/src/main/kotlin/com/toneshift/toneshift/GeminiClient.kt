package com.toneshift.toneshift

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini rewrite engine. Design goal: silent text replacement.
 * It returns ONLY the rewritten text, never explanations or quotes.
 */
object GeminiClient {

    class GeminiException(message: String) : Exception(message)

    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_INSTRUCTION =
        "You are an invisible, inline keyboard text-replacement engine. Your ONLY job is to rewrite the user's raw draft according to the specified TONE_MODE.\n\n" +
        "STRICT RULES:\n" +
        "1. Output ONLY the final rewritten text. Never include greetings, explanations, markdown formatting, or surrounding quotation marks.\n" +
        "2. Preserve the original language (including Hinglish or regional nuances if casual, or convert to clean English if Formal is requested).\n" +
        "3. If the input text ends with '// [instruction]', treat everything before '//' as the draft and everything after '//' as the exact transformation instruction.\n" +
        "4. Keep all names, numbers, dates, and core facts intact."

    /**
     * Splits a trailing "// custom instruction" off the draft.
     * Returns Pair(draft, customInstruction or null).
     */
    fun splitCustomInstruction(raw: String): Pair<String, String?> {
        val idx = raw.lastIndexOf("//")
        if (idx <= 0) return Pair(raw, null)
        val after = raw.substring(idx + 2).trim()
        val before = raw.substring(0, idx).trim()
        if (after.isEmpty() || before.isEmpty()) return Pair(raw, null)
        // Guard: ignore "//" inside a URL like https://example.com
        if (idx > 0 && raw[idx - 1] == ':') return Pair(raw, null)
        return Pair(before, after)
    }

    suspend fun rewrite(
        context: Context,
        rawText: String,
        preset: SecureStore.Preset
    ): String = withContext(Dispatchers.IO) {
        val apiKey = SecureStore.getApiKey(context)
            ?: throw GeminiException("No Gemini API key saved. Open ToneShift and add your key first.")

        val (draft, custom) = splitCustomInstruction(rawText.trim())
        if (draft.isEmpty()) throw GeminiException("Nothing to rewrite.")

        val modeText = if (custom != null) {
            preset.instruction + " Additional specific instruction from the user: " + custom
        } else {
            preset.instruction
        }

        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(
                    JSONObject().put("text", "TONE_MODE: " + preset.id + " - " + modeText + "\nRAW_TEXT: " + draft)
                ))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.25)
                put("maxOutputTokens", 500)
            })
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendly = when (response.code) {
                        400 -> "Gemini rejected the request (400). Check the text and try again."
                        403 -> "Gemini API key is invalid or not enabled (403). Check your key in ToneShift."
                        429 -> "Gemini rate limit hit (429). Wait a few seconds and try again."
                        else -> "Gemini error " + response.code + ". Try again in a moment."
                    }
                    SecureStore.logUsage(context, preset.id, draft.length, 0, false)
                    throw GeminiException(friendly + if (bodyText.isNotEmpty()) "" else "")
                }
                val out = parseResponse(bodyText)
                SecureStore.logUsage(context, preset.id, draft.length, out.length, true)
                out
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            SecureStore.logUsage(context, preset.id, draft.length, 0, false)
            throw GeminiException("Network error. Check your internet and try again.")
        }
    }

    suspend fun testApiKey(context: Context, apiKey: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", "Reply with exactly: OK")))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 10)
            })
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey.trim())
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GeminiException(
                        when (response.code) {
                            403, 400 -> "Key rejected (" + response.code + "). Check the key and that the Gemini API is enabled."
                            429 -> "Rate limited (429), but the key works."
                            else -> "Gemini returned " + response.code
                        }
                    )
                }
                "Key works. Gemini $MODEL responded successfully."
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Network error. Check your internet and try again.")
        }
    }

    private fun parseResponse(bodyText: String): String {
        val json = JSONObject(bodyText)
        val candidates = json.optJSONArray("candidates")
            ?: throw GeminiException("Empty response from Gemini. Try again.")
        if (candidates.length() == 0) throw GeminiException("Empty response from Gemini. Try again.")
        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw GeminiException("Empty response from Gemini. Try again.")
        val parts = content.optJSONArray("parts")
            ?: throw GeminiException("Empty response from Gemini. Try again.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        var out = sb.toString().trim()
        // Defensive cleanup: the system prompt forbids these, strip if they slip through anyway.
        if (out.startsWith("```")) {
            out = out.removePrefix("```").removeSuffix("```").trim()
        }
        if (out.length >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length - 1).trim()
        }
        if (out.isEmpty()) throw GeminiException("Gemini returned empty text. Try again.")
        return out
    }
}
