package io.davoice.caragent

import io.davoice.caragent.conversation.BargeInDecision
import io.davoice.caragent.conversation.Cancellable
import io.davoice.caragent.conversation.ConversationCommands
import io.davoice.caragent.conversation.ConversationController
import io.davoice.caragent.conversation.ConversationPhase
import io.davoice.caragent.conversation.conversationHint
import io.davoice.caragent.conversation.defaultConversationSettings
import io.davoice.caragent.conversation.dropWakePhrase
import io.davoice.caragent.conversation.isQuestion
import io.davoice.caragent.conversation.normalizeConversationSettings
import io.davoice.caragent.conversation.resolveBargeIn
import io.davoice.caragent.conversation.routingConfigForConversation
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Conversation modes: the settings, the interrupt rule, the wake phrase and the controller's scripted flows, on the fixtures shared
// with the React Native and Flutter apps (conversation_*.json).
class ConversationTest {
    private fun merge(base: Any?, patch: Any?): Any? {
        if (patch !is Map<*, *>) return patch ?: base
        val out = LinkedHashMap<String, Any?>(base.asObj())
        for ((k, v) in patch) out["$k"] = if (v is Map<*, *>) merge(out["$k"], v) else v
        return out
    }

    private class FakeClock {
        var now = 0L
        private val timers = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        fun make(ms: Long, fn: () -> Unit): Cancellable {
            val alive = booleanArrayOf(true)
            timers += Triple(now + ms, fn, alive)
            return Cancellable { alive[0] = false }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { it.third[0] && it.first <= end }.minByOrNull { it.first } ?: break
                next.third[0] = false
                now = next.first
                next.second()
            }
            now = end
        }
    }

    @Test fun `defaults keep today's behaviour`() {
        assertEquals(
            Json.parse("""{"interrupt":false,"wakeWord":false,"wakeWordMode":{"endOfSpeechMs":2000,"listenMs":8000,"followUpMs":8000,"preFetchMs":2000,"stopWord":false},"freeMode":{"endOfSpeechMs":2000,"idleMs":0}}"""),
            Json.parse(Json.stringify(defaultConversationSettings.toJson())),
        )
        assertEquals("Just talk · take turns", defaultConversationSettings.description)
    }

    @Test fun `normalize fills, clamps and drops what it does not know`() {
        assertEquals(defaultConversationSettings, normalizeConversationSettings(null))
        val s = normalizeConversationSettings(Json.parse("""{"interrupt":true,"wakeWord":"yes","extra":1,"wakeWordMode":{"endOfSpeechMs":50,"followUpMs":-5,"preFetchMs":99999,"listenMs":7000.4,"stopWord":false},"freeMode":{"idleMs":"x"}}"""))
        assertEquals(
            Json.parse("""{"interrupt":true,"wakeWord":false,"wakeWordMode":{"endOfSpeechMs":500,"listenMs":7000,"followUpMs":0,"preFetchMs":3000,"stopWord":false},"freeMode":{"endOfSpeechMs":2000,"idleMs":0}}"""),
            Json.parse(Json.stringify(s.toJson())),
        )
    }

    @Test fun `interrupting turns echo cancellation on, taking turns leaves the routing as it is`() {
        val base: Obj = mapOf("default" to mapOf("mode" to "default"), "WakewordAEC" to mapOf("regular" to false, "duringTTS" to false))
        assertSame(base, routingConfigForConversation(base, defaultConversationSettings))
        val on = routingConfigForConversation(base, defaultConversationSettings.copy(interrupt = true))
        assertEquals(mapOf("mode" to "always"), on["AEC"])
        assertEquals(mapOf("regular" to true, "duringTTS" to true), on["WakewordAEC"])
    }

    @Test fun `interrupt rule (conversation_barge_in json)`() {
        for (c in TestSupport.fixture("conversation_barge_in.json").asList().map { it.asObj() }) {
            assertEquals("\"${c["heard"]}\"", str(c["decision"]), resolveBargeIn(str(c["heard"]), str(c["agent"])).name.lowercase())
        }
        assertEquals(BargeInDecision.EMPTY, resolveBargeIn("", "x"))
    }

    @Test fun `wake phrase (conversation_wake_phrase json)`() {
        for (c in TestSupport.fixture("conversation_wake_phrase.json").asList().map { it.asObj() }) {
            assertEquals("\"${c["text"]}\"", str(c["out"]), dropWakePhrase(str(c["text"]), str(c["phrase"])))
        }
    }

    @Test fun `microphone line`() {
        assertEquals("Say “Hey Coach” before each request", conversationHint(ConversationPhase.ASLEEP, "Hey Coach", true))
        assertEquals("Say “Hey Coach” or tap to talk", conversationHint(ConversationPhase.ASLEEP, "Hey Coach", false))
        assertEquals("Listening… just talk", conversationHint(ConversationPhase.LISTENING, "Hey Coach", false))
    }

    @Test fun `controller flows (conversation_flow json)`() {
        for (flow in TestSupport.fixture("conversation_flow.json").asList().map { it.asObj() }) {
            val name = str(flow["name"])
            val clock = FakeClock()
            val log = mutableListOf<String>()
            var settings = normalizeConversationSettings(merge(defaultConversationSettings.toJson(), flow["settings"]))
            val c = ConversationController(
                ConversationCommands(
                    openMic = { ms, _ -> log += "openMic:$ms" },
                    closeMic = { log += "closeMic" },
                    armWakeWord = { log += "armWakeWord" },
                    disarmWakeWord = { log += "disarmWakeWord" },
                    stopReply = { log += "stopReply" },
                    timer = { ms, fn -> clock.make(ms, fn) },
                    executor = null,
                ),
                settings,
            )
            for ((i, step) in flow["steps"].asList().map { it.asObj() }.withIndex()) {
                val at = "$name / step $i (${step["do"]})"
                val before = log.size
                var returned: Boolean? = null
                when (step["do"]) {
                    "enter" -> c.enter()
                    "leave" -> c.leave(step["rearm"] as? Boolean ?: true)
                    "turnStarted" -> c.turnStarted()
                    "replySpeaking" -> c.replySpeaking()
                    "replyFinished" -> c.replyFinished(step["reply"]?.let { str(it) } ?: "")
                    "userSpeech" -> c.userSpeech()
                    "tapToTalk" -> c.tapToTalk()
                    "wakeWord" -> returned = c.wakeWord()
                    "heard" -> returned = c.heardWhileBusy(str(step["text"]), str(step["agent"]))
                    "wait" -> clock.advance((step["ms"] as Number).toLong())
                    "update" -> {
                        settings = normalizeConversationSettings(merge(settings.toJson(), step["settings"]))
                        c.update(settings)
                    }
                    else -> error("unknown step ${step["do"]}")
                }
                if ("returns" in step) assertEquals(at, step["returns"], returned)
                if ("log" in step) assertEquals(at, step["log"].asList().map { str(it) }, log.subList(before, log.size).toList())
                if ("phase" in step) assertEquals(at, str(step["phase"]), c.phase.id)
            }
        }
    }

    @Test fun `a reply that asks a question is answered without the wake word`() {
        assertEquals(true, isQuestion("Should I open the passenger window too?"))
        assertEquals(true, isQuestion("Quer que eu abra o vidro do carona? "))
        assertEquals(true, isQuestion("Did you say “the trunk?”"))
        assertEquals(false, isQuestion("I opened the driver window."))
        assertEquals(false, isQuestion("Why? Because it was cold."))
        assertEquals(false, isQuestion(""))
    }
}
