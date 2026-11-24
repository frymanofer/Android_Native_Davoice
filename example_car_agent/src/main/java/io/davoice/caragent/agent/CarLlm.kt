package io.davoice.caragent.agent

import android.content.Context
import android.util.Log
import com.davoice.llm.LocalLLMEngine
import com.davoice.llm.LocalLLMLoadOptions
import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.car.Car
import io.davoice.caragent.car.CarTools
import io.davoice.caragent.flow.AppLanguage
import io.davoice.caragent.flow.didNotCatchTexts
import io.davoice.caragent.flow.generalQuestionsMessage
import io.davoice.caragent.flow.isTooShortForDecline
import io.davoice.caragent.flow.lowConfidenceTexts
import io.davoice.caragent.flow.noRemoteTexts
import io.davoice.caragent.flow.notInDemoTexts
import io.davoice.caragent.speaker.CurrentSpeaker
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The agent side of the car app (port of the Flutter / React Native apps' local LLM service): downloads and loads the DaVoice
// Automotive Agent ahead of time, configures the shared native agent core for it (the same settings as the other apps), and runs
// turns. History is the chat in the agent's shape: role user / model, text, and a model reply's toolMessages.

/** e.g. "Saturday, October 17, 2026". Changes once a day, so the system prompt (and the engine's cache of it) stays the same. */
fun currentDateText(now: Date = Date()): String = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(now)

class CarLlm(private val context: Context, private val licenseKey: String) {
    private val engine = LocalLLMEngine()
    private val layer2 = LocalLLMEngine()
    val store = ModelStore(context.filesDir)
    private val agentLock = Mutex()
    private var agent: AgentClient? = null
    private var agentLanguage: String? = null
    private var configuredFor: AgentClient? = null
    private var confidenceKey: String? = null
    private var steeringSent: String? = null
    @Volatile var confidence = ConfidenceSettings.defaults
    private var nativeLoads = 0
    private var stateRestoredFor: String? = null

    // The engine holds one model; racing callers join one load.
    private val loadLock = Mutex()
    @Volatile private var loaded = false
    val isModelReady get() = loaded

    /** The app's one agent, for the current language (a new agent after a language change). */
    private suspend fun agent(): AgentClient = agentLock.withLock {
        val lang = AppLanguage.current
        agent?.let { if (agentLanguage == lang) return it }
        agent?.close()
        val dir = context.filesDir
        val db = "${if (lang == "en") "davoice_agent" else "davoice_agent_${lang.replace('-', '_').lowercase()}"}_car"
        val created = withContext(Dispatchers.IO) {
            AgentClient.create(
                engine, layer2,
                mapOf(
                    "pack" to mapOf("agent" to AgentPack.withReplyLanguage(AgentPack.of(lang), lang), "skills" to emptyList<Any?>()),
                    "databasePath" to File(dir, "$db.db").path,
                    // The whole chat (user, model outputs, tools, replies), readable off the phone: adb shell run-as ... cat files/davoice_chat.log
                    "chatLogPath" to File(dir, "davoice_chat.log").path,
                    "webSearch" to mapOf("engine" to "wikipedia", "wikipediaLanguage" to if (lang.lowercase().startsWith("pt")) "pt" else "en"),
                ),
                onEvent = { e -> if (e.type == "log") Log.i("DavoiceAgent", "${str(e.json["level"])}: ${str(e.json["message"])}") },
                onRequest = { request -> answerRequest(request) },
            )
        }
        created.setTools(CarTools.tools(wording = CarModel.TOOL_WORDING)) { it.name !in CarTools.names }
        agent = created
        agentLanguage = lang
        configuredFor = null
        setUpRouterEmbeddings(created)
        created
    }

    /** The agent's requests to the app: the remote assistant (none in this demo) and owner-only tools. */
    private fun answerRequest(request: Obj): Any? {
        val language = AppLanguage.current
        return when (request["type"]) {
            "remote" -> when {
                // The model said it can't do this: not available in this demo, unless it is a fragment of speech the model declined
                request["reason"] == "cannot" ->
                    if (isTooShortForDecline(str(request["userText"] ?: request["query"] ?: ""))) {
                        mapOf("text" to (didNotCatchTexts[language] ?: BasicLanguage.message(language, "didNotCatch") ?: didNotCatchTexts["en"]))
                    } else {
                        mapOf("text" to (notInDemoTexts[language] ?: BasicLanguage.message(language, "notInDemo") ?: notInDemoTexts["en"]))
                    }
                // Local confidence held a car action back and no remote model is set up
                request["reason"] == "low-confidence" ->
                    mapOf("text" to (lowConfidenceTexts[language] ?: BasicLanguage.message(language, "lowNoRemote") ?: lowConfidenceTexts["en"]))
                // No remote assistant in this demo: say so instead of answering
                else -> mapOf("text" to (generalQuestionsMessage[language] ?: BasicLanguage.message(language, "noRemote") ?: generalQuestionsMessage["en"] ?: noRemoteTexts["en"]))
            }
            "authorizeTool" -> {
                // Doors (unlock_car, open_door): only the enrolled owner's voice, when speaker verification is on.
                val ok = if (request["scope"] == "door_locks") CurrentSpeaker.ownerMayAct().also {
                    Log.i("DavoiceAgent", "authorize ${str(request["name"])} (door_locks): ${if (it.first) "yes" else "no"} - ${it.second}")
                }.first else false
                mapOf("authorized" to ok)
            }
            else -> throw IllegalStateException("The app does not answer \"${str(request["type"])}\" requests.")
        }
    }

    /** The router's sentence-embedding model (25 MB), downloaded once in the background; until then the router compares words. */
    private fun setUpRouterEmbeddings(a: AgentClient) {
        Thread({
            try {
                val file = File(context.filesDir, "models/all-MiniLM-L6-v2-Q8_0.gguf")
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".download")
                    OkHttpClient().newCall(Request.Builder().url(EMBEDDING_MODEL_URL).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                    tmp.renameTo(file)
                }
                kotlinx.coroutines.runBlocking { a.configure(mapOf("router" to mapOf("embeddingModel" to file.path))) }
                Log.i("DavoiceAgent", "router embedding model set")
            } catch (t: Throwable) {
                Log.i("DavoiceAgent", "router embedding model not available (word overlap is used): $t")
            }
        }, "RouterEmbeddings").start()
    }

    /** The core's call guards ('correct' fixes / blocks; the split-tool opposite check can only block: 'enforce'). */
    private fun callGuards(mode: String, basic: Boolean = false): Obj {
        val m = linkedMapOf<String, Any?>(
            "polarity" to mode, "direction" to mode, "absRel" to mode, "zone" to mode, "capability" to mode, "numberGround" to mode,
            "opposite" to (if (mode == "correct") "enforce" else mode), "followUp" to (if (CarModel.HISTORY_AS_RUN) mode else "off"),
            "dualZone" to mode, "enumWords" to mode,
        )
        if (basic) {
            m["capability"] = "off"
            m["polarity"] = "off"
        }
        return m
    }

    /** The agent, set up for the model (chat template, remote, router, guards, scope, undo, confidence, safety, vehicle). */
    private suspend fun agentForModel(): AgentClient {
        val a = agent()
        if (configuredFor !== a) {
            configuredFor = a
            confidenceKey = null
            steeringSent = null
            a.configure(mapOf(
                "chatTemplate" to CarModel.chatTemplate,
                // the remote assistant: the "no remote model" message (answerRequest)
                "remote" to mapOf("enabled" to true, "timeoutMs" to 15000),
                // false: the model decides every turn with every tool (no router, no skill rules)
                "intentRouter" to false,
                "webSearchTool" to true,
                "timeTool" to true,
                "memory" to mapOf("profile" to "small", "relevantMax" to 0),
                "sampling" to mapOf("temperature" to 0),
                "guards" to callGuards(CarModel.CALL_GUARDS),
                "historyAsRun" to CarModel.HISTORY_AS_RUN,
                "guidedToolCalls" to CarModel.GUIDED_TOOL_CALLS,
                "promptOrder" to CarModel.PROMPT_ORDER,
                "guardRetry" to CarModel.GUARD_RETRY,
                "maxOperations" to CarModel.MAX_OPERATIONS,
                "historyTurns" to CarModel.HISTORY_TURNS,
                "compactToolResults" to CarModel.COMPACT_TOOL_RESULTS,
                "scope" to CarModel.scope,
                "undo" to CarModel.UNDO,
            ))
        }
        // the car's results carry their undo only when the agent uses it
        CarTools.undoResults = CarModel.UNDO
        val language = AppLanguage.current
        val key = "${Json.stringify(confidence.toJson())}|$language"
        if (confidenceKey != key) {
            confidenceKey = key
            val pt = language.lowercase().startsWith("pt")
            val basic = BasicLanguage.isBasic(language)
            a.configure(mapOf(
                "confidence" to toCoreConfidence(confidence, language),
                "safety" to mapOf(
                    "neverAct" to true,
                    "seatAsk" to true,
                    "messages" to mapOf(
                        "whichSeat" to (BasicLanguage.message(language, "whichSeat") ?: if (pt) "Qual banco: o seu, o do carona ou os dois?" else "Which seat: yours, the passenger's, or both?"),
                        "unsupported" to (notInDemoTexts[language] ?: BasicLanguage.message(language, "notInDemo") ?: notInDemoTexts["en"]),
                    ),
                ),
                // Basic language: the command scope and the checks that look for English / Portuguese words would drop or block nearly
                // every request, so they are off; every car action is asked first and runs only after a yes instead.
                "scope" to if (basic) mapOf("enabled" to false) else CarModel.scope,
                "guards" to callGuards(CarModel.CALL_GUARDS, basic),
            ))
        }
        // The car's steering-wheel side: "o banco da direita" is the passenger in a left-hand-drive car.
        val steering = Car.state.steering
        if (steeringSent != steering) {
            steeringSent = steering
            a.configure(mapOf("vehicle" to mapOf("steering" to steering)))
        }
        return a
    }

    /** Downloads (if needed) and loads the model, then restores or processes the system prompt, so the first turn pays for none of it.
     *  Call when the conversation opens and after a language change, never mid-turn. */
    suspend fun warmUp(history: List<Obj>, onDownloadProgress: (Double) -> Unit, onWarming: () -> Unit) = coroutineScope {
        val language = AppLanguage.current
        val prebuilt = CarModel.prebuiltStates[language]
        val modelBytes = CarModel.SIZE_BYTES.toDouble()
        val stateBytes = (prebuilt?.sizeBytes ?: 0L).toDouble()
        var modelPart = 0.0
        var statePart = 0.0
        fun report() = onDownloadProgress((modelPart * modelBytes + statePart * stateBytes) / (modelBytes + stateBytes))
        val prebuiltDownload: Deferred<String?> = async(Dispatchers.IO) { store.downloadPrebuiltState(language) { statePart = it; report() } }
        ensureLoaded { modelPart = it; report() }
        val prebuiltPath = prebuiltDownload.await()
        onWarming()
        try {
            val a = agentForModel()
            val date = currentDateText()
            val prompt = a.call("prompt", mapOf("text" to "Hello", "history" to history, "date" to date)) as String
            val statePath = store.kvStatePath("$language-${stablePromptKey(prompt, date)}")
            val stateExists = File(statePath).exists()
            val restorePath = if (stateExists) statePath else prebuiltPath
            var restoredPrebuilt = false
            val load = "${CarModel.ID}#$nativeLoads"
            if (stateRestoredFor != load && restorePath != null) {
                val t0 = System.currentTimeMillis()
                try {
                    val n = withContext(Dispatchers.IO) { engine.loadState(restorePath) }
                    restoredPrebuilt = !stateExists
                    Log.i("LocalLLM", "KV state restored${if (restoredPrebuilt) " (pre-warmed)" else ""}: $n tokens in ${System.currentTimeMillis() - t0} ms")
                } catch (t: Throwable) {
                    Log.w("LocalLLM", "KV state not usable, deleted: $t")
                    File(restorePath).delete()
                }
            }
            stateRestoredFor = load
            // Answer once, briefly, on the agent's real prompt: the system prompt is in the cache and the first decode has run.
            val started = System.currentTimeMillis()
            val tokens = generateBriefly(prompt)
            val warmMs = System.currentTimeMillis() - started
            Log.i("LocalLLM", "warm-up answered ($tokens tokens) in $warmMs ms")
            if ((!stateExists && !(restoredPrebuilt && warmMs <= 5000)) || warmMs > 5000) {
                val t0 = System.currentTimeMillis()
                val n = withContext(Dispatchers.IO) { engine.saveState(statePath) }
                Log.i("LocalLLM", "KV state saved: $n tokens in ${System.currentTimeMillis() - t0} ms")
                withContext(Dispatchers.IO) { store.deleteOldStates() }
            }
        } catch (t: Throwable) {
            // Only a speed-up: a failure here must not block the chat.
            Log.w("LocalLLM", "warm-up state step skipped: $t")
            prefill(history, "")
        }
    }

    private suspend fun generateBriefly(prompt: String): Int {
        val done = CompletableDeferred<Int>()
        var tokens = 0
        engine.generateStream(prompt, { _, finished ->
            if (finished) done.complete(tokens) else if (++tokens == 4) engine.cancel()
        }, { error -> done.completeExceptionally(error) })
        return done.await()
    }

    private suspend fun ensureLoaded(onDownloadProgress: (Double) -> Unit) = loadLock.withLock {
        if (loaded) return@withLock
        if (!store.isDownloaded()) store.download(onDownloadProgress)
        withContext(Dispatchers.IO) {
            check(engine.setLicense(licenseKey)) { "The license was rejected for ${CarModel.DISPLAY_NAME}" }
            engine.loadModel(store.modelFile.path, LocalLLMLoadOptions(CarModel.CONTEXT_SIZE))
        }
        nativeLoads++
        loaded = true
    }

    /** Processes the conversation plus the user's words so far into the model's cache. No-op until the model is loaded. */
    suspend fun prefill(history: List<Obj>, partialUserText: String) {
        if (!loaded) return
        try {
            agentForModel().prefill(history, partialUserText)
        } catch (_: Throwable) {
        }
    }

    /** One turn. Reply text streams to [onDelta]; [onEvent] gets everything else (filler, status, ...). */
    suspend fun turn(history: List<Obj>, userText: String, onDelta: (String) -> Unit, onEvent: (AgentEvent) -> Unit): TurnResult {
        ensureLoaded {}
        val a = agentForModel()
        try {
            return a.turn(mapOf("text" to userText, "history" to history, "date" to currentDateText())) { e ->
                if (e.type == "text") onDelta(e.text) else onEvent(e)
            }
        } finally {
            // The next request starts with nobody heard (door authorization).
            CurrentSpeaker.clearHeard()
        }
    }

    /** A new agent for the language (call after a language change). */
    suspend fun languageChanged() = agentLock.withLock {
        agent?.close()
        agent = null
        agentLanguage = null
    }

    suspend fun cancel() {
        agent?.cancel()
    }

    companion object {
        private const val EMBEDDING_MODEL_URL = "https://pub-27f94b1602c647ab95a601086cf52820.r2.dev/agent-llm/router/all-MiniLM-L6-v2-Q8_0.gguf"
    }
}
