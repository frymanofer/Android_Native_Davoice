package io.davoice.example.chat

import android.content.Context
import android.util.Log
import com.davoice.llm.LocalLLMEngine
import com.davoice.llm.LocalLLMLoadOptions
import io.davoice.example.DemoConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** How a model family wraps system/user/assistant turns. */
data class ChatTemplate(
    val systemPrefix: String,
    val userPrefix: String,
    val assistantPrefix: String,
    val turnSuffix: String,
    /** Gemma has no system role: the system prompt goes into the first user turn. */
    val systemIntoFirstUserTurn: Boolean,
    val stopSequences: List<String>,
) {
    companion object {
        val GEMMA = ChatTemplate("", "<start_of_turn>user\n", "<start_of_turn>model\n", "<end_of_turn>\n", true, listOf("<end_of_turn>"))
        val CHATML = ChatTemplate("<|im_start|>system\n", "<|im_start|>user\n", "<|im_start|>assistant\n", "<|im_end|>\n", false, listOf("<|im_end|>"))
    }
}

data class LocalModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val contextSize: Int,
    val template: ChatTemplate,
    val sizeBytes: Long,
)

/**
 * On-device chat with com.davoice.llm.LocalLLMEngine (llama.cpp). Models are
 * GGUF files downloaded on first use (1-5 GB), never bundled in the APK.
 */
class LocalLlm(context: Context) {

    data class Turn(val fromUser: Boolean, val text: String)

    companion object {
        val MODELS = listOf(
            LocalModel(
                "qwen2.5-1.5b-instruct", "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_0.gguf",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf",
                contextSize = 8192, template = ChatTemplate.CHATML, sizeBytes = 1_066_227_232,
            ),
            LocalModel(
                "gemma-3n-e2b-it", "Gemma 3n E2B", "gemma-3n-E2B-it-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf",
                contextSize = 16384, template = ChatTemplate.GEMMA, sizeBytes = 3_030_000_000,
            ),
            LocalModel(
                "gemma-3n-e4b-it", "Gemma 3n E4B", "gemma-3n-E4B-it-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf",
                contextSize = 16384, template = ChatTemplate.GEMMA, sizeBytes = 4_540_000_000,
            ),
        )
        /** Smallest built-in: fastest to download, load and run. */
        val DEFAULT = MODELS.first()

        private const val TAG = "LocalLlm"
        private const val DOWNLOAD_ATTEMPTS = 6
    }

    private val prefs = context.getSharedPreferences("local_llm", Context.MODE_PRIVATE)
    private val modelsDir = File(context.filesDir, "local_llm/models")
    private val engine = LocalLLMEngine()
    // Generous timeouts: the CDN can take a while to start a multi-GB response.
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // At most one load at a time; concurrent callers join it.
    private val loadLock = Mutex()
    @Volatile private var loadedModelId: String? = null

    var selectedModel: LocalModel
        get() = MODELS.firstOrNull { it.id == prefs.getString("selected", null) } ?: DEFAULT
        set(value) { prefs.edit().putString("selected", value.id).apply() }

    fun file(model: LocalModel) = File(modelsDir, model.fileName)

    fun isDownloaded(model: LocalModel) = file(model).exists()

    fun isReady(model: LocalModel = selectedModel) = loadedModelId == model.id

    /** Downloads (if needed), licenses and loads the selected model. */
    suspend fun ensureLoaded(onDownloadProgress: (Float) -> Unit = {}): LocalModel = loadLock.withLock {
        val model = selectedModel
        if (loadedModelId == model.id) return model
        if (!isDownloaded(model)) download(model, onDownloadProgress)
        withContext(Dispatchers.IO) {
            check(engine.setLicense(DemoConfig.DAVOICE_LICENSE_KEY)) { "Local LLM license was rejected" }
            // useMmap stays true: the model is mapped from the .gguf, not copied into RAM.
            engine.loadModel(file(model).absolutePath, LocalLLMLoadOptions(contextSize = model.contextSize, useMmap = true))
        }
        loadedModelId = model.id
        model
    }

    /**
     * Downloads [model] to a ".download" file, then renames it. A dropped or
     * stalled connection resumes where it stopped (HTTP Range) instead of
     * starting the 1-5 GB file over.
     */
    private suspend fun download(model: LocalModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val partial = File(modelsDir, model.fileName + ".download")
        var lastError: Exception? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                downloadOnce(model, partial, onProgress)
                check(partial.renameTo(file(model))) { "Could not save ${model.fileName}" }
                onProgress(1f)
                return@withContext
            } catch (e: java.io.IOException) {
                lastError = e
                Log.w(TAG, "Download attempt $attempt/$DOWNLOAD_ATTEMPTS of ${model.fileName} failed at ${partial.length()} bytes: $e")
                delay(2000L * attempt)
            }
        }
        throw java.io.IOException("Model download failed for ${model.displayName}: ${lastError?.message}", lastError)
    }

    private fun downloadOnce(model: LocalModel, partial: File, onProgress: (Float) -> Unit) {
        val resumeFrom = partial.length()
        val request = Request.Builder().url(model.url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) return // already complete
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = requireNotNull(response.body)
            // 206 = the server honoured the Range header; 200 = it sent the whole file again.
            val append = response.code == 206
            val start = if (append) resumeFrom else 0L
            val total = body.contentLength().takeIf { it > 0 }?.let { it + start } ?: model.sizeBytes
            var written = start
            var lastReport = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) { onProgress(written.toFloat() / total); lastReport = now }
                    }
                }
            }
            if (written < total) throw java.io.IOException("connection closed at $written of $total bytes")
        }
    }

    /** Deletes downloaded .gguf files that are no longer in [MODELS]. */
    fun deleteUnusedModelFiles() {
        val inUse = MODELS.map { it.fileName }.toSet()
        modelsDir.listFiles()?.filter { it.name.endsWith(".gguf") && it.name !in inUse }?.forEach { it.delete() }
    }

    /**
     * While the user is still talking, processes the conversation plus their
     * words so far into the model's cache, so only the last few words remain
     * when they stop. Fire-and-forget; ignored until the model is loaded.
     */
    fun prefill(history: List<Turn>, partialUserText: String) {
        val model = selectedModel
        if (!isReady(model)) return
        engine.prefill(promptPrefix(model.template, history, partialUserText))
    }

    /**
     * Generates a reply to [userText], calling [onDelta] with text as it streams
     * (already cut at the template's stop marker). Returns the full reply.
     */
    suspend fun generate(history: List<Turn>, userText: String, onDelta: (String) -> Unit): String {
        val model = ensureLoaded()
        val prompt = prompt(model.template, history, userText)
        val filter = StopSequenceFilter(model.template.stopSequences)
        val reply = StringBuilder()
        val finished = CompletableDeferred<Unit>()
        engine.generateStream(prompt, { token, done ->
            if (!done) {
                val wasStopped = filter.isStopped
                val text = filter.push(token)
                if (text.isNotEmpty()) { reply.append(text); onDelta(text) }
                if (!wasStopped && filter.isStopped) engine.cancel() // native still sends done=true
            } else {
                filter.flush().takeIf { it.isNotEmpty() }?.let { reply.append(it); onDelta(it) }
                finished.complete(Unit)
            }
        }, { error -> finished.completeExceptionally(error) })
        finished.await()
        return reply.toString()
    }

    fun cancel() = engine.cancel()

    // ---- Prompt assembly (port of src/local_llm/PromptAssembler.ts) ----------------

    private fun prompt(t: ChatTemplate, history: List<Turn>, userText: String): String {
        val out = StringBuilder()
        var pendingSystem = ""
        if (t.systemIntoFirstUserTurn) pendingSystem = DemoConfig.LOCAL_LLM_SYSTEM_PROMPT
        else out.append(t.systemPrefix).append(DemoConfig.LOCAL_LLM_SYSTEM_PROMPT).append(t.turnSuffix)
        for (turn in history + Turn(true, userText)) {
            if (turn.fromUser) {
                val content = if (pendingSystem.isNotEmpty()) "$pendingSystem\n\n${turn.text}" else turn.text
                pendingSystem = ""
                out.append(t.userPrefix).append(content).append(t.turnSuffix)
            } else {
                out.append(t.assistantPrefix).append(turn.text).append(t.turnSuffix)
            }
        }
        return out.append(t.assistantPrefix).toString()
    }

    /** The beginning of [prompt] up to and including [partialUserText], without the turn's closing. */
    private fun promptPrefix(t: ChatTemplate, history: List<Turn>, partialUserText: String): String {
        val marker = "\u0000partial-cut\u0000"
        val full = prompt(t, history, partialUserText + marker)
        return full.substring(0, full.indexOf(marker))
    }
}

/**
 * Cuts streamed output at the first stop sequence (e.g. "<|im_end|>"), holding
 * back text that might be the start of one so a marker split across tokens
 * never reaches the speaker.
 */
class StopSequenceFilter(private val stopSequences: List<String>) {
    private var held = ""
    var isStopped = false
        private set
    private val maxHold = (stopSequences.maxOfOrNull { it.length } ?: 1) - 1

    fun push(delta: String): String {
        if (isStopped) return ""
        val text = held + delta
        val cut = stopSequences.filter { it.isNotEmpty() }.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (cut != null) {
            isStopped = true
            held = ""
            return text.substring(0, cut)
        }
        var hold = 0
        for (len in minOf(maxHold, text.length) downTo 1) {
            val tail = text.substring(text.length - len)
            if (stopSequences.any { it.startsWith(tail) }) { hold = len; break }
        }
        held = text.substring(text.length - hold)
        return text.substring(0, text.length - hold)
    }

    fun flush(): String {
        val rest = if (isStopped) "" else held
        held = ""
        return rest
    }
}
