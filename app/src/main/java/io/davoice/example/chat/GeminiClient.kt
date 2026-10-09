package io.davoice.example.chat

import io.davoice.example.BuildConfig
import io.davoice.example.DemoConfig
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
 * Cloud chat via Google Gemini (the "AI Chat (Gemini)" mode). Not a DaVoice
 * library: it only shows the on-device voice pipeline driving a remote LLM.
 * Needs `gemini.apiKey` in local.properties.
 */
class GeminiClient {

    data class Turn(val fromUser: Boolean, val text: String)

    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    suspend fun reply(history: List<Turn>): String = withContext(Dispatchers.IO) {
        check(isConfigured) { "No Gemini API key. Add gemini.apiKey to local.properties and rebuild." }

        val contents = JSONArray()
        history.forEach { turn ->
            contents.put(JSONObject()
                .put("role", if (turn.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", turn.text))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", DemoConfig.GEMINI_SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.7)
                .put("maxOutputTokens", DemoConfig.GEMINI_MAX_OUTPUT_TOKENS)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", DemoConfig.GEMINI_THINKING_BUDGET)))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${DemoConfig.GEMINI_MODEL}:generateContent")
            .header("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val payload = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val message = payload.optJSONObject("error")?.optString("message").orEmpty()
                error(message.ifEmpty { "Gemini request failed with status ${response.code}" })
            }
            val parts = payload.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val text = (0 until (parts?.length() ?: 0))
                .mapNotNull { parts?.optJSONObject(it)?.optString("text") }
                .joinToString(" ").trim()
            text.ifEmpty { error("Gemini returned an empty response.") }
        }
    }

    companion object {
        fun isRateLimit(message: String) =
            Regex("quota|rate limit|429|too many requests", RegexOption.IGNORE_CASE).containsMatchIn(message)

        fun friendlyError(message: String): String = when {
            isRateLimit(message) ->
                "This demo uses Google Gemini as its backend LLM. Gemini is currently experiencing rate limits or heavy demand, so it cannot answer right now. Please try again in about 30 seconds."
            Regex("busy|overloaded|unavailable|timeout|temporar", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                "This demo uses Google Gemini as its backend LLM. Gemini is temporarily busy right now, so the demo could not get a reply. Please try again in a moment."
            message.startsWith("No Gemini API key") ->
                "Gemini chat needs an API key. Add gemini dot api key to local dot properties and rebuild the app."
            else ->
                "This demo uses Google Gemini as its backend LLM. Gemini is temporarily unavailable right now, so the demo could not get a reply. Please try again in a moment."
        }
    }
}
