package io.davoice.caragent.conversation

import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asObj

// Conversation mode settings (the settings sheet): how the user and the agent take turns. The same JSON shape and rules as the React
// Native and Flutter apps (src/conversation/settings.ts, lib/conversation/conversation_settings.dart).
//  - interrupt: false = take turns (the microphone is closed while the agent answers), true = the user can talk over the agent. It
//    keeps the microphone open under the agent's voice, so it turns echo cancellation (AEC) on.
//  - wakeWord: true = each request starts with the wake word (a short window after a reply lets a follow-up through without it),
//    false = just talk: the microphone stays open between requests.
//  - Each mode has its own timeouts; the wake-word mode can keep the audio from just before the recognizer opened (preFetchMs).

data class WakeWordModeSettings(
    /** Silence that ends the user's request (ms). */
    val endOfSpeechMs: Int,
    /** After the wake word (or a tap), how long to wait for the first words before going back to the wake word (ms). */
    val listenMs: Int,
    /** After a reply, how long to keep listening for a follow-up without the wake word (ms). 0 = the wake word every time. */
    val followUpMs: Int,
    /** Audio kept from just before the recognizer opened after the wake word (ms). 0 = off. */
    val preFetchMs: Int,
    /** The wake word said while the agent is talking stops it and opens a new request. */
    val stopWord: Boolean,
) {
    fun toJson(): Obj = linkedMapOf("endOfSpeechMs" to endOfSpeechMs, "listenMs" to listenMs, "followUpMs" to followUpMs, "preFetchMs" to preFetchMs, "stopWord" to stopWord)
}

data class FreeModeSettings(
    /** Silence that ends the user's request (ms). */
    val endOfSpeechMs: Int,
    /** With nothing said for this long, the microphone closes until the wake word or a tap (ms). 0 = never. */
    val idleMs: Int,
) {
    fun toJson(): Obj = linkedMapOf("endOfSpeechMs" to endOfSpeechMs, "idleMs" to idleMs)
}

data class ConversationSettings(val interrupt: Boolean, val wakeWord: Boolean, val wakeWordMode: WakeWordModeSettings, val freeMode: FreeModeSettings) {
    fun toJson(): Obj = linkedMapOf("interrupt" to interrupt, "wakeWord" to wakeWord, "wakeWordMode" to wakeWordMode.toJson(), "freeMode" to freeMode.toJson())

    /** The silence that ends a request in the selected mode. */
    val endOfSpeechMs get() = if (wakeWord) wakeWordMode.endOfSpeechMs else freeMode.endOfSpeechMs

    /** One line for the settings row ("Wake word · take turns"). */
    val description get() = "${if (wakeWord) "Wake word" else "Just talk"} · ${if (interrupt) "interrupt anytime" else "take turns"}"
}

/** Today's behaviour: take turns; after the first wake word the microphone stays open (no wake word). */
val defaultConversationSettings = ConversationSettings(
    interrupt = false,
    wakeWord = false,
    wakeWordMode = WakeWordModeSettings(endOfSpeechMs = 1500, listenMs = 8000, followUpMs = 8000, preFetchMs = 2000, stopWord = false),
    freeMode = FreeModeSettings(endOfSpeechMs = 1500, idleMs = 0),
)

data class Choice(val value: Int, val label: String)

val endOfSpeechChoices = listOf(Choice(1000, "1 s"), Choice(1500, "1.5 s"), Choice(2000, "2 s"), Choice(2500, "2.5 s"), Choice(3000, "3 s"))
val listenChoices = listOf(Choice(4000, "4 s"), Choice(6000, "6 s"), Choice(8000, "8 s"), Choice(10000, "10 s"), Choice(15000, "15 s"))
val followUpChoices = listOf(Choice(0, "Off"), Choice(5000, "5 s"), Choice(8000, "8 s"), Choice(15000, "15 s"), Choice(30000, "30 s"))
val preFetchChoices = listOf(Choice(0, "Off"), Choice(500, "0.5 s"), Choice(1000, "1 s"), Choice(2000, "2 s"), Choice(3000, "3 s"))
val idleChoices = listOf(Choice(0, "Never"), Choice(15000, "15 s"), Choice(30000, "30 s"), Choice(60000, "1 min"), Choice(120000, "2 min"))

private fun num(v: Any?, fallback: Int, min: Int, max: Int): Int {
    val n = if (v is Number && !v.toDouble().isNaN() && !v.toDouble().isInfinite()) Math.round(v.toDouble()).toInt() else fallback
    return n.coerceIn(min, max)
}

private fun bool(v: Any?, fallback: Boolean) = v as? Boolean ?: fallback

/** Any stored or edited value -> complete, in-range settings (unknown keys dropped, missing ones defaulted). */
fun normalizeConversationSettings(raw: Any?): ConversationSettings {
    val d = defaultConversationSettings
    val r = raw.asObj()
    val w = r["wakeWordMode"].asObj()
    val f = r["freeMode"].asObj()
    return ConversationSettings(
        interrupt = bool(r["interrupt"], d.interrupt),
        wakeWord = bool(r["wakeWord"], d.wakeWord),
        wakeWordMode = WakeWordModeSettings(
            endOfSpeechMs = num(w["endOfSpeechMs"], d.wakeWordMode.endOfSpeechMs, 500, 6000),
            listenMs = num(w["listenMs"], d.wakeWordMode.listenMs, 2000, 60000),
            followUpMs = num(w["followUpMs"], d.wakeWordMode.followUpMs, 0, 120000),
            preFetchMs = num(w["preFetchMs"], d.wakeWordMode.preFetchMs, 0, 3000),
            stopWord = bool(w["stopWord"], d.wakeWordMode.stopWord),
        ),
        freeMode = FreeModeSettings(
            endOfSpeechMs = num(f["endOfSpeechMs"], d.freeMode.endOfSpeechMs, 500, 6000),
            idleMs = num(f["idleMs"], d.freeMode.idleMs, 0, 600000),
        ),
    )
}

/** The routing JSON (wake word, STT and TTS) for these settings: echo cancellation on for every listener when the user can interrupt,
 *  and unchanged otherwise (as the other apps' routingConfigForConversation). */
fun routingConfigForConversation(base: Obj, s: ConversationSettings): Obj =
    if (!s.interrupt) base else base + mapOf("AEC" to mapOf("mode" to "always"), "WakewordAEC" to mapOf("regular" to true, "duringTTS" to true))
