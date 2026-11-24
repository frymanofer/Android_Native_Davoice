package io.davoice.caragent.agent

import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.flow.llmReplyInstructionFor
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.deepCopy

// The DaVoice Automotive Agent's pack (assets/agent_automotive/[pt-BR/]agent.json, the same files as the React Native and Flutter
// example_car_agent): the system prompt the automotive model is trained with, prompt templates, fixed replies and filler phrases. The
// native agent core interprets it. English and Brazilian Portuguese have their own pack; every other ("Basic") language uses the
// English pack with the sentences the user hears translated (basic_languages.json "pack").

object AgentPack {
    private val packs = mutableMapOf<String, Obj>()
    private val basicPacks = mutableMapOf<String, Obj>()

    /** Loads the packs ([read]: an asset path -> its text). */
    fun load(read: (String) -> String) {
        packs["en"] = Json.obj(read("agent_automotive/agent.json"))
        packs["pt-BR"] = Json.obj(read("agent_automotive/pt-BR/agent.json"))
    }

    /** The pack of [language] (English for a language without its own pack). */
    fun of(language: String): Obj = packs[language] ?: basicPack(language) ?: packs["en"]!!

    private fun basicPack(language: String): Obj? {
        val t = BasicLanguage.packTexts(language)
        val en = packs["en"]
        if (t == null || t.isEmpty() || en == null) return null
        return basicPacks.getOrPut(language) {
            @Suppress("UNCHECKED_CAST")
            val pack = deepCopy(en) as MutableMap<String, Any?>
            fun s(k: String) = t[k] as? String
            fun list(k: String) = (t[k] as? List<*>)?.map { it.toString() }
            val prompts = LinkedHashMap(pack["prompts"].asObj())
            for (k in listOf("noReply", "notDoneRest", "done", "failed", "notConfirmed", "notAuthorized", "unsupported", "invalidArgs",
                "guardConflict", "outOfRange", "outOfRangeAsk", "scopeClarify", "scopeUnsupported", "scopeNoRemote")) {
                s(k)?.let { prompts[k] = it }
            }
            pack["prompts"] = prompts
            // "ok" is said in every language: the English "Okay!" (fixed reply "ack") is for the tuned languages only
            (pack["fixedReplies"] as? List<*>)?.let { fixed -> pack["fixedReplies"] = fixed.filterNot { it.asObj()["id"] == "ack" } }
            (pack["remote"] as? Map<*, *>)?.let { r ->
                val remote = LinkedHashMap(r.asObj())
                s("remoteFailed")?.let { remote["failed"] = it }
                val errors = LinkedHashMap(remote["errors"].asObj())
                s("remoteOffline")?.let { errors["offline"] = it }
                s("remoteBusy")?.let { errors["busy"] = it }
                s("remoteTimeout")?.let { errors["timeout"] = it }
                s("remoteUnavailable")?.let { errors["unavailable"] = it }
                remote["errors"] = errors
                list("remoteFillers")?.let { remote["fillers"] = it }
                pack["remote"] = remote
            }
            list("remoteFillers")?.let { pack["onlineToolFillers"] = it }
            val web = pack["webSearch"].asObj()
            if (web["fillers"] is Map<*, *>) {
                val fillers = LinkedHashMap(web["fillers"].asObj())
                list("searchFillers")?.let { fillers["general"] = it }
                list("searchFailed")?.let { fillers["searchFailed"] = it }
                fillers["topics"] = emptyList<Any?>() // topic fillers are English sentences
                pack["webSearch"] = LinkedHashMap(web).also { it["fillers"] = fillers }
            }
            pack
        }
    }

    /** [agent] with the language's reply instruction ending the persona, so it is part of the cached system prompt. */
    fun withReplyLanguage(agent: Obj, language: String): Obj {
        val persona = agent["persona"].asObj()
        return agent + mapOf("persona" to persona + mapOf("systemPrompt" to "${persona["systemPrompt"]} ${llmReplyInstructionFor(language)}".trim()))
    }

    /** For tests. */
    fun strings(v: Any?): List<String> = v.asList().map { it.toString() }
}
