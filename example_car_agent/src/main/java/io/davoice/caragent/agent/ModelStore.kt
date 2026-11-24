package io.davoice.caragent.agent

import android.util.Log
import io.davoice.caragent.util.Obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// The one model of the car agent (as the React Native and Flutter example_car_agent): DaVoice Automotive Agent 0.6B (g3c_it3639,
// Qwen3-0.6B fine-tuned for the car, q4_0, 382 MB), downloaded on first use, sha256-checked; and its pre-warmed KV states (the
// system prompt already processed, per language), so the first start does not spend ~40 s processing the prompt on the phone.

data class PrebuiltState(val url: String, val sha256: String, val sizeBytes: Long)

object CarModel {
    const val ID = "davoice-automotive-agent-0.6b-g3c-it3639"
    const val DISPLAY_NAME = "DaVoice Automotive Agent 0.6B"
    const val FILENAME = "davoice-automotive-qwen3-0.6b-g3c-it3639-q4_0.gguf"
    private const val R2 = "https://pub-27f94b1602c647ab95a601086cf52820.r2.dev/agent-llm"
    const val URL = "$R2/auto16_g3c_it3639-q4_0.gguf"
    const val SIZE_BYTES = 381565920L
    const val SHA256 = "c39fbd65b259573d67dfb7da1935f1d0087156d7eefd97e131536a436c4d0ba8"

    /** Same decisions as 8192 on the phone, 0.23 GB less. */
    const val CONTEXT_SIZE = 6144

    /** Qwen3's chat format, thinking off. */
    val chatTemplate: Obj = mapOf(
        "systemPrefix" to "<|im_start|>system\n",
        "userPrefix" to "<|im_start|>user\n",
        "assistantPrefix" to "<|im_start|>assistant\n",
        "turnSuffix" to "<|im_end|>\n",
        "systemMode" to "role",
        "stopSequences" to listOf("<|im_end|>"),
        "generationPrefix" to "<think>\n\n</think>\n\n",
        "spacedToolJson" to true,
    )

    // The model's agent settings (the same as the other apps' model registry entry).
    const val TOOL_WORDING = "h3d"
    const val CALL_GUARDS = "correct"
    const val HISTORY_AS_RUN = true
    const val GUIDED_TOOL_CALLS = true
    const val PROMPT_ORDER = "cache"
    const val GUARD_RETRY = true
    const val MAX_OPERATIONS = 2
    const val HISTORY_TURNS = 6
    const val COMPACT_TOOL_RESULTS = true
    val scope: Obj = mapOf("enabled" to true, "localMaxWords" to 10, "strictMaxWords" to 15, "hardMaxWords" to 22)
    const val UNDO = true

    val prebuiltStates = mapOf(
        "en" to PrebuiltState("$R2/prewarm/g3c_it3639_prewarm_en.kvstate", "75a35e15c774b9b63223b4b71e3283f6abffbf3b52e6343f16b04d9f26cd325f", 426699589),
        "pt-BR" to PrebuiltState("$R2/prewarm/g3c_it3639_prewarm_pt-BR.kvstate", "f794a202844afdcb754678ce092da729dbdc66377c5e39c78d81300c683f11a0", 429681893),
    )
}

class ModelStore(baseDir: File) {
    private val dir = File(baseDir, "local_llm/models")
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val modelFile get() = File(dir, CarModel.FILENAME)
    fun isDownloaded() = modelFile.exists()

    private val kvPrefix = "${CarModel.FILENAME}.${CarModel.SHA256.take(12)}.ctx${CarModel.CONTEXT_SIZE}."

    /** A saved KV state for the prompt's system part ([promptKey], see [stablePromptKey]). */
    fun kvStatePath(promptKey: String) = File(dir, "$kvPrefix$promptKey.kvstate").path
    private fun prebuiltStatePath(language: String) = File(dir, "${kvPrefix}prebuilt-$language.kvstate")

    /** Downloads the model (sha256 checked; a temporary file renamed when complete). */
    suspend fun download(onProgress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        if (modelFile.exists()) return@withContext
        fetch(CarModel.URL, modelFile, CarModel.SHA256, CarModel.SIZE_BYTES, onProgress)
    }

    /** The pre-warmed state of [language], downloaded if there is one and the phone has not saved its own; null when none. A failure
     *  only logs: the phone then warms up itself. */
    suspend fun downloadPrebuiltState(language: String, onProgress: (Double) -> Unit): String? = withContext(Dispatchers.IO) {
        val entry = CarModel.prebuiltStates[language] ?: return@withContext null
        val dest = prebuiltStatePath(language)
        if (dest.exists()) return@withContext dest.path
        // the phone already saved its own state for this language (states are named "<language>-<prompt hash>")
        if (dir.listFiles()?.any { it.name.startsWith("$kvPrefix$language-") && it.name.endsWith(".kvstate") } == true) return@withContext null
        try {
            fetch(entry.url, dest, entry.sha256, entry.sizeBytes, onProgress)
            dest.path
        } catch (t: Throwable) {
            Log.w("LocalLLM", "pre-warmed state not downloaded (the phone warms up itself): $t")
            null
        }
    }

    private fun fetch(url: String, dest: File, sha256: String, size: Long, onProgress: (Double) -> Unit) {
        dir.mkdirs()
        val temp = File(dest.path + ".download")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("download failed: no body")
                val total = if (body.contentLength() > 0) body.contentLength() else size
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            received += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 100) {
                                lastReport = now
                                onProgress(received.toDouble() / total)
                            }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != sha256.lowercase()) throw IllegalStateException("download failed: checksum mismatch")
            }
            if (!temp.renameTo(dest)) throw IllegalStateException("could not save ${dest.name}")
            onProgress(1.0)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Saved states: the newest [KV_STATES_KEPT] are kept, the others deleted (each is ~430 MB). */
    fun deleteOldStates() {
        val states = dir.listFiles()?.filter { it.name.endsWith(".kvstate") || it.name.endsWith(".kvstate.tmp") }?.sortedByDescending { it.lastModified() } ?: return
        var kept = 0
        for (f in states) {
            if (f.name.startsWith(kvPrefix) && !f.name.endsWith(".tmp") && kept < KV_STATES_KEPT) {
                kept++
                continue
            }
            f.delete()
        }
    }

    companion object {
        const val KV_STATES_KEPT = 2
    }
}

/** Which saved state fits this prompt: a hash of its system part WITHOUT the date line (which changes every day). FNV-1a, 32 bits,
 *  the same value as the other apps' stablePromptKey. */
fun stablePromptKey(prompt: String, dateText: String): String {
    val userAt = prompt.indexOf("<|im_start|>user")
    val system = (if (userAt >= 0) prompt.substring(0, userAt) else prompt).split(dateText).joinToString("")
    var h = 0x811c9dc5L
    for (unit in system) {
        h = h xor unit.code.toLong()
        h = (h * 0x01000193L) and 0xffffffffL
    }
    return h.toString(16).padStart(8, '0')
}
