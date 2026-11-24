package io.davoice.caragent.agent

import android.util.Log
import com.davoice.llm.DavoiceAgent
import com.davoice.llm.LocalLLMEngine
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.str
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The on-device voice assistant from the shared native agent core (com.davoice.llm.DavoiceAgent, the same core the React Native and
// Flutter packages drive): one turn from the user's words to the spoken reply, with tool calling, fillers and the command scope. Its
// behaviour comes from an agent pack (assets/agent_automotive/agent.json). The app supplies the pack, its own tools (the car's) and the
// UI. A Kotlin port of the Flutter plugin's agent client: JSON in and out, events streamed while a call runs.

/** One app tool the agent can call. [handler] runs on the agent's event thread and returns the result, or a [ToolReply]. */
class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Obj,
    val execution: Obj? = null,
    val handler: (args: Obj, context: Obj) -> Any?,
)

/** A tool result with the app's own sentence, said as it is (the turn ends without a second model round). */
data class ToolReply(val result: Any?, val say: String)

/** An agent event (see davoice_agent.h in the core): text, filler, status, webSearch, toolResult, skill, memory, log. */
class AgentEvent(val json: Obj) {
    val type: String get() = str(json["type"])
    val text: String get() = str(json["text"])
    val reason: String get() = str(json["reason"])
    override fun toString() = Json.stringify(json)
}

/** The result of one turn. */
class TurnResult(val json: Obj) {
    val text: String get() = str(json["text"])

    /** Keep these in the history with the reply. */
    val toolMessages: List<Obj> get() = json["toolMessages"].asList().map { it.asObj() }
    val cancelled: Boolean get() = json["cancelled"] == true
}

class AgentClient private constructor(
    private val onRequest: ((Obj) -> Any?)?,
) {
    private lateinit var native: DavoiceAgent

    // Events and call results are handled on one thread, in the order the core sends them: every event of a call arrives before
    // the call's result.
    private val events = Executors.newSingleThreadExecutor { r -> Thread(r, "DavoiceAgentEvents") }
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(AgentEvent) -> Unit>()
    @Volatile private var tools: Map<String, ToolDefinition> = emptyMap()
    @Volatile private var closed = false

    // Latest-only prefill: partial transcripts arrive faster than they can be processed, and only the newest matters.
    private val prefillLock = Any()
    private var prefillRunning = false
    private var prefillNext: Obj? = null

    companion object {
        private const val TAG = "DavoiceAgent"

        /** Creates the agent on [engine] (its model is the agent's model). Throws when the pack is incomplete. */
        fun create(engine: LocalLLMEngine, layer2: LocalLLMEngine, config: Obj, onEvent: ((AgentEvent) -> Unit)?, onRequest: ((Obj) -> Any?)?): AgentClient {
            val client = AgentClient(onRequest)
            if (onEvent != null) client.listeners += onEvent
            client.native = DavoiceAgent(Json.stringify(config)) { json ->
                client.events.execute { client.dispatch(json) }
            }
            client.native.attach(engine)
            client.native.attachLayer2(layer2)
            return client
        }
    }

    private fun dispatch(text: String) {
        val event = try {
            Json.obj(text)
        } catch (_: Throwable) {
            return
        }
        if (event["type"] == "request") {
            answer((event["id"] as Number).toLong(), event["request"].asObj())
            return
        }
        val e = AgentEvent(event)
        for (l in listeners) {
            try {
                l(e)
            } catch (t: Throwable) {
                Log.w(TAG, "event listener failed: $t")
            }
        }
    }

    private fun answer(id: Long, request: Obj) {
        val result: Any? = try {
            if (request["type"] == "callTool") {
                val tool = tools[str(request["name"])] ?: throw IllegalStateException("There is no tool named \"${str(request["name"])}\".")
                val value = tool.handler(request["arguments"].asObj(), request["context"].asObj())
                if (value is ToolReply) mapOf("result" to value.result, "say" to value.say) else mapOf("result" to value)
            } else if (onRequest != null) {
                onRequest.invoke(request)
            } else {
                throw IllegalStateException("The app does not answer \"${str(request["type"])}\" requests.")
            }
        } catch (t: Throwable) {
            mapOf("error" to (t.message ?: t.toString()))
        }
        native.resolve(id, Json.stringify(result))
    }

    /** Runs a core method (see davoice_agent.h); returns its decoded result. */
    suspend fun call(method: String, args: Obj = emptyMap()): Any? {
        check(!closed) { "The agent was closed" }
        return suspendCancellableCoroutine { cont ->
            native.callAsync(method, Json.stringify(args)) { result, error ->
                // after every event of this call (same queue)
                events.execute {
                    if (error != null) cont.resumeWithException(error)
                    else cont.resume(if (result == null) null else Json.parse(result))
                }
            }
        }
    }

    /** One turn: tools, reply. Text streams as `text` events; fillers come as `filler` events. Load a model first. [input]: `text`,
     *  `history`, `date`, ... */
    suspend fun turn(input: Obj, onEvent: ((AgentEvent) -> Unit)? = null): TurnResult {
        if (onEvent != null) listeners += onEvent
        try {
            return TurnResult(call("turn", input).asObj())
        } finally {
            if (onEvent != null) listeners -= onEvent
        }
    }

    /** Processes the conversation plus the user's words so far into the model's cache. Never throws. */
    fun prefill(history: List<Obj>, partialText: String = "") {
        synchronized(prefillLock) {
            prefillNext = mapOf("history" to history, "text" to partialText)
            if (prefillRunning) return
            prefillRunning = true
        }
        prefillStep()
    }

    private fun prefillStep() {
        val next = synchronized(prefillLock) {
            val n = prefillNext
            prefillNext = null
            if (n == null || closed) prefillRunning = false
            n
        } ?: return
        native.callAsync("prefill", Json.stringify(next)) { _, _ -> events.execute { prefillStep() } }
    }

    /** Stops the running turn. */
    suspend fun cancel() {
        try {
            call("cancel")
        } catch (_: Throwable) {
        }
    }

    /** Changes settings at runtime. */
    suspend fun configure(settings: Obj) {
        call("configure", settings)
    }

    /** The app's own tools. [online] tools get a filler while they run. */
    suspend fun setTools(list: List<ToolDefinition>, online: (ToolDefinition) -> Boolean = { false }) {
        tools = list.associateBy { it.name }
        call("configure", mapOf("externalTools" to list.map { t ->
            linkedMapOf<String, Any?>("name" to t.name, "description" to t.description, "inputSchema" to t.inputSchema).also { m ->
                if (t.execution != null) m["execution"] = t.execution
                m["online"] = online(t)
            }
        }))
    }

    fun addListener(l: (AgentEvent) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    fun close() {
        if (closed) return
        closed = true
        listeners.clear()
        native.close()
        events.shutdown()
    }
}
