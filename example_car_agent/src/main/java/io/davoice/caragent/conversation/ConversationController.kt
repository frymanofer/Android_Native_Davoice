package io.davoice.caragent.conversation

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Conversation controller: who owns the microphone between requests, for the settings in ConversationSettings. The same phases,
// events and commands as the React Native and Flutter apps (src/conversation/controller.ts, lib/conversation/
// conversation_controller.dart); all three run the scripted flows in conversation_flow.json.
//   off        not in the conversation screen (start-up / the wake-word screen owns the audio)
//   listening  the speech recognizer is open for the user's request
//   busy       a request is being answered (thinking, then the agent speaking)
//   asleep     the recognizer is closed; the wake word (or a tap) opens it again

/** The reply asked something ("Should I open the driver window?"): in the wake-word mode only then does the user get a follow-up
 * turn without the wake word. */
fun isQuestion(reply: String): Boolean = Regex("""[?？]["'”’)\]]*\s*$""").containsMatchIn(reply.trim())

enum class ConversationPhase { OFF, LISTENING, BUSY, ASLEEP;
    val id get() = name.lowercase()
}

/** A timer the controller can cancel. */
fun interface Cancellable {
    fun cancel()
}

class ConversationCommands(
    /** Open the speech recognizer (a fresh transcript). preFetchMs: keep that much audio from before it opened. */
    val openMic: (preFetchMs: Int, why: String) -> Unit,
    /** Close the speech recognizer. */
    val closeMic: (why: String) -> Unit,
    /** Wake word detection on. */
    val armWakeWord: (why: String) -> Unit,
    /** Wake word detection off (the microphone stays with the app). */
    val disarmWakeWord: (why: String) -> Unit,
    /** Stop the agent's reply now: its voice, the sentences still queued, the request. */
    val stopReply: (why: String) -> Unit,
    val onPhase: ((ConversationPhase) -> Unit)? = null,
    val log: ((String) -> Unit)? = null,
    /** Tests pass a fake clock; the app uses a handler or a scheduler. */
    val timer: (ms: Long, fn: () -> Unit) -> Cancellable,
    /** Where the commands run, one after another (null: right away, on the caller's thread: tests). */
    val executor: ExecutorService? = Executors.newSingleThreadExecutor { r -> Thread(r, "ConversationAudio") },
)

class ConversationController(private val commands: ConversationCommands, private var settings: ConversationSettings) {
    @Volatile var phase = ConversationPhase.OFF
        private set
    private var speaking = false
    private var duplex = false
    private var wakeArmed = false
    private var timer: Cancellable? = null

    /** In the conversation screen. */
    val active get() = phase != ConversationPhase.OFF

    /** The agent's voice is playing (replySpeaking .. replyFinished). */
    val agentSpeaking get() = speaking

    /** Interrupt mode: the recognizer is open under the agent's voice. */
    val duplexOpen get() = duplex

    val config get() = settings

    private enum class Window { LISTEN, FOLLOW_UP, IDLE }

    /** New settings; an open listening window restarts with the new timeout. */
    /**
     * New settings (the Save button). An open listening window restarts with the new timeout. Asleep in the wake-word mode and
     * switched to "just talk": the microphone opens (otherwise nothing would ever open it).
     */
    @Synchronized fun update(s: ConversationSettings) {
        val before = settings
        settings = s
        if (phase == ConversationPhase.ASLEEP && before.wakeWord && !s.wakeWord) {
            disarm("just talk")
            run { commands.openMic(0, "just talk") }
            setPhase(ConversationPhase.LISTENING)
            startWindow(Window.IDLE)
        } else if (phase == ConversationPhase.LISTENING && !before.wakeWord && s.wakeWord) {
            sleep("wake word before each request") // from now on every request starts with the wake word
        } else if (phase == ConversationPhase.LISTENING) {
            startWindow(if (settings.wakeWord) Window.LISTEN else Window.IDLE)
        }
    }

    /** The conversation screen opened; the app has opened the recognizer (and paused the wake word). */
    /** Just talk: the app has opened the recognizer (and paused the wake word). Wake word before each request: asleep until the
     *  wake word (the app left the recognizer paused; the wake word that opened the screen is not a request). */
    @Synchronized fun enter() {
        clearWindow()
        speaking = false
        duplex = false
        wakeArmed = false
        if (settings.wakeWord) {
            sleep("wake word before each request")
            return
        }
        setPhase(ConversationPhase.LISTENING)
        startWindow(Window.IDLE)
    }

    /** Back to the start screen; the app closes the recognizer. [rearmWakeWord]: the start screen listens for the wake word (the car
     *  agents); false: the wake word goes back to paused, as in the conversation. */
    @Synchronized fun leave(rearmWakeWord: Boolean = true) {
        clearWindow()
        speaking = false
        duplex = false
        setPhase(ConversationPhase.OFF)
        if (rearmWakeWord) arm("leave") else disarm("leave")
    }

    /** A request was sent (the app closed the recognizer for it). */
    @Synchronized fun turnStarted() {
        if (phase == ConversationPhase.OFF) return // a request outside the conversation screen: not ours
        clearWindow()
        speaking = false
        duplex = false
        setPhase(ConversationPhase.BUSY)
    }

    /** The reply's voice starts. Interrupt mode opens the recognizer under it; the wake-word mode can arm the stop word. */
    @Synchronized fun replySpeaking() {
        if (phase != ConversationPhase.BUSY || speaking) return
        speaking = true
        if (settings.interrupt) {
            duplex = true
            run { commands.openMic(0, "interrupt: listen under the reply") }
        } else if (settings.wakeWord && settings.wakeWordMode.stopWord) {
            arm("stop word during the reply")
        }
    }

    /** The request ended (its reply was spoken, or it ended without one): the next request, or sleep. */
    /**
     * [reply]: what the agent said. Wake-word mode: the user answers without the wake word only when the reply was a
     * question (and the follow-up window is on); then recognition pauses and unpauses for a fresh turn. Otherwise back to the wake word.
     */
    @Synchronized fun replyFinished(reply: String = "") {
        if (phase != ConversationPhase.BUSY) return
        val wasDuplex = duplex
        speaking = false
        duplex = false
        if (settings.wakeWord) {
            val question = isQuestion(reply)
            if (settings.wakeWordMode.followUpMs == 0 || !question) {
                sleep(if (question) "wake word every time" else "the reply was not a question") // a stop word armed for the reply stays armed
                return
            }
            disarm("a question: the user answers")
            run { commands.closeMic("pause before the answer") }
            run { commands.openMic(0, "answer to the question") }
            setPhase(ConversationPhase.LISTENING)
            startWindow(Window.FOLLOW_UP)
            return
        }
        disarm("reply finished")
        // the recognizer open under the reply heard the reply: a fresh session for the user's turn
        if (wasDuplex) run { commands.closeMic("fresh session after the reply") }
        run { commands.openMic(0, "after the reply") }
        setPhase(ConversationPhase.LISTENING)
        startWindow(Window.IDLE)
    }

    /** The user said something while listening: the window timeout no longer applies. */
    @Synchronized fun userSpeech() {
        if (phase == ConversationPhase.LISTENING) clearWindow()
    }

    /** Speech heard while busy. True: the user interrupted the reply (interrupt mode, while the agent speaks, words the agent isn't
     *  saying): the reply was stopped and the words start the user's next request. False: drop them. */
    @Synchronized fun heardWhileBusy(heard: String, agentText: String): Boolean {
        if (phase != ConversationPhase.BUSY || !duplex || !speaking) return false
        if (resolveBargeIn(heard, agentText) != BargeInDecision.INTERRUPT) return false
        speaking = false
        duplex = false
        commands.log?.invoke("[Conversation] the user interrupted: \"$heard\"")
        run { commands.stopReply("the user interrupted") }
        setPhase(ConversationPhase.LISTENING)
        startWindow(if (settings.wakeWord) Window.LISTEN else Window.IDLE)
        return true
    }

    /** The wake word was heard. False when the conversation isn't open (the app's own start-up path handles it). */
    @Synchronized fun wakeWord(): Boolean {
        when (phase) {
            ConversationPhase.OFF -> return false
            // a stop word while the agent talks; while it is thinking the wake word is ignored
            ConversationPhase.BUSY -> if (speaking && wakeArmed) stopAndListen("stop word")
            ConversationPhase.ASLEEP -> {
                disarm("wake word")
                val preFetch = if (settings.wakeWord) settings.wakeWordMode.preFetchMs else 0
                run { commands.openMic(preFetch, "wake word") }
                setPhase(ConversationPhase.LISTENING)
                startWindow(if (settings.wakeWord) Window.LISTEN else Window.IDLE)
            }
            ConversationPhase.LISTENING -> {}
        }
        return true
    }

    /** The microphone button: wakes the conversation, or stops the agent and listens. */
    @Synchronized fun tapToTalk() {
        if (phase == ConversationPhase.ASLEEP) {
            disarm("tap")
            run { commands.openMic(0, "tap") }
            setPhase(ConversationPhase.LISTENING)
            startWindow(if (settings.wakeWord) Window.LISTEN else Window.IDLE)
        } else if (phase == ConversationPhase.BUSY && speaking) {
            stopAndListen("tap")
        }
    }

    private fun stopAndListen(why: String) {
        val wasDuplex = duplex
        speaking = false
        duplex = false
        run { commands.stopReply(why) }
        disarm(why)
        if (wasDuplex) run { commands.closeMic("fresh session") }
        // no pre-fetch: the audio before this moment is the agent's voice and the wake word itself
        run { commands.openMic(0, "after the $why") }
        setPhase(ConversationPhase.LISTENING)
        startWindow(if (settings.wakeWord) Window.LISTEN else Window.IDLE)
    }

    private fun sleep(why: String) {
        clearWindow()
        speaking = false
        duplex = false
        run { commands.closeMic(why) }
        arm(why)
        setPhase(ConversationPhase.ASLEEP)
    }

    private fun startWindow(kind: Window) {
        clearWindow()
        val ms = when (kind) {
            Window.LISTEN -> settings.wakeWordMode.listenMs
            Window.FOLLOW_UP -> settings.wakeWordMode.followUpMs
            Window.IDLE -> settings.freeMode.idleMs
        }
        if (ms <= 0) return
        var self: Cancellable? = null
        self = commands.timer(ms.toLong()) {
            synchronized(this) {
                if (timer !== self) return@synchronized
                timer = null
                if (phase == ConversationPhase.LISTENING) sleep(if (kind == Window.IDLE) "nothing said" else "no request")
            }
        }
        timer = self
    }

    private fun clearWindow() {
        timer?.cancel()
        timer = null
    }

    private fun arm(why: String) {
        if (wakeArmed) return
        wakeArmed = true
        run { commands.armWakeWord(why) }
    }

    private fun disarm(why: String) {
        if (!wakeArmed) return
        wakeArmed = false
        run { commands.disarmWakeWord(why) }
    }

    private fun setPhase(p: ConversationPhase) {
        if (p == phase) return
        phase = p
        commands.log?.invoke("[Conversation] ${p.id}")
        commands.onPhase?.invoke(p)
    }

    private fun run(command: () -> Unit) {
        val ex = commands.executor
        val safe = Runnable {
            try {
                command()
            } catch (t: Throwable) {
                commands.log?.invoke("[Conversation] command failed (ignored): $t")
            }
        }
        if (ex == null) safe.run() else ex.execute(safe)
    }

    /** Resolves when every command issued so far has run (tests, and before a screen change). */
    fun idle() {
        commands.executor?.submit(Runnable {})?.get()
    }
}

// ---- the interrupt rule, the microphone line and the wake phrase (shared fixtures conversation_barge_in.json /
// conversation_wake_phrase.json) -------------------------------------------------------------------------------------------------

enum class BargeInDecision { INTERRUPT, ECHO, EMPTY }

private val nonWord = Regex("[^\\p{L}\\p{N}']+")

fun bargeInWords(text: String): List<String> = text.lowercase().replace(nonWord, " ").split(' ').filter { it.isNotEmpty() }

/** Interrupt mode: a word the agent is not saying can only have come from the user, and one is enough; a window made only of the
 *  agent's words is echo leaking past echo cancellation. */
fun resolveBargeIn(heard: String, agentText: String): BargeInDecision {
    val words = bargeInWords(heard)
    if (words.isEmpty()) return BargeInDecision.EMPTY
    val agentWords = bargeInWords(agentText).toSet()
    return if (words.all { it in agentWords }) BargeInDecision.ECHO else BargeInDecision.INTERRUPT
}

/** The microphone line: what the conversation is doing now and how to go on. */
fun conversationHint(phase: ConversationPhase, wakePhrase: String, wakeWord: Boolean): String = when (phase) {
    ConversationPhase.ASLEEP -> if (wakeWord) "Say “$wakePhrase” before each request" else "Say “$wakePhrase” or tap to talk"
    ConversationPhase.LISTENING -> if (wakeWord) "Listening… say your request" else "Listening… just talk"
    ConversationPhase.BUSY -> "Answering… tap to stop"
    ConversationPhase.OFF -> "“$wakePhrase” + your command"
}

/** A tap on the microphone line does something (wakes the conversation, or stops the agent). */
fun conversationTapEnabled(phase: ConversationPhase) = phase == ConversationPhase.ASLEEP || phase == ConversationPhase.BUSY

/** How speech recognition also writes each wake-phrase word, as word sequences (on the phone: "Hey couch", "A coach", "Hey the
 *  voice", "Hey da voice", "Hello voice"). */
private val soundsLike: Map<String, List<List<String>>> = mapOf(
    "hey" to listOf(listOf("hey"), listOf("hi"), listOf("hay"), listOf("hei"), listOf("a"), listOf("okay"), listOf("ok")),
    "hello" to listOf(listOf("hello"), listOf("hallo"), listOf("hullo"), listOf("hola"), listOf("allo"), listOf("hey"), listOf("hi")),
    "coach" to listOf(listOf("coach"), listOf("couch"), listOf("koch"), listOf("coachs"), listOf("coaches")),
    "davoice" to listOf(
        listOf("davoice"), listOf("da", "voice"), listOf("the", "voice"), listOf("de", "voice"), listOf("devoice"), listOf("divoice"),
        listOf("dvoice"), listOf("d", "voice"), listOf("davoices"), listOf("the", "voices"), listOf("voice"), listOf("voices"),
        listOf("davos"), listOf("davis"),
    ),
)

/** A first word that counts alone ("Hey, open the window"); "a" or "ok" only count in front of the next word of the phrase. */
private val aloneWords = mapOf("hey" to listOf("hey", "hi", "hay", "hei"), "hello" to listOf("hello", "hallo", "hullo"))

private val wakeNonWord = Regex("[^\\p{L}\\p{N}]+")
private val wordRegex = Regex("[\\p{L}\\p{N}']+")
private val leadingSeparators = Regex("^[\\s,.!?;:'\"“”‘’-]+")
private fun norm(w: String) = w.lowercase().replace(wakeNonWord, "")
private fun forms(phraseWord: String) = soundsLike[phraseWord] ?: listOf(listOf(phraseWord))
private fun aloneWord(word: String, phraseWord: String) = (aloneWords[phraseWord] ?: listOf(phraseWord)).contains(norm(word))

/**
 * The transcript without the wake phrase that leads it, so the model never gets it: "Hey DaVoice, open the window" -> "open the
 * window". Also part of the phrase ("DaVoice, lock the car", "The voice, lock the car", "Hey, open the window"), the way
 * recognition mishears it ("Hey the voice", "Hey couch"), and the phrase said twice. Only wake words ("Hey DaVoice.", "The voice",
 * "Hey") -> "" (the request is still to come). Words are compared whole: "Coaching tips", "Heyday", "a car" stay as they are.
 * As the React Native apps' dropWakePhrase (src/conversation/wakePhrase.ts), the same fixture (conversation_wake_phrase.json).
 */
fun dropWakePhrase(text: String, wakePhrase: String): String {
    val phrase = wakePhrase.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }
    val words = wordRegex.findAll(text).toList()
    if (words.isEmpty()) return ""
    if (phrase.isEmpty()) return text.trim()
    val normalized = words.map { norm(it.value) }
    var at = 0 // words consumed
    var round = 0
    while (round < 3 && at < words.size) {
        val taken = leadingPhrase(normalized.subList(at, normalized.size), phrase)
        if (taken == 0) break
        at += taken
        round++
    }
    if (at == 0) return text.trim()
    if (at >= words.size) return ""
    return text.substring(words[at - 1].range.last + 1).replace(leadingSeparators, "").trim()
}

/** How many words phrase[from..] takes at the start of words (each phrase word in one of its forms), or -1. Longest first. */
private fun matchFrom(words: List<String>, phrase: List<String>, from: Int, at: Int = 0): Int {
    if (from == phrase.size) return at
    var best = -1
    for (form in forms(phrase[from])) {
        val fits = at + form.size <= words.size && form.indices.all { words[at + it] == form[it] }
        if (fits) best = maxOf(best, matchFrom(words, phrase, from + 1, at + form.size))
    }
    return best
}

/** How many leading words are the wake phrase: all of it, its end ("davoice", "the voice"), or its first word alone ("hey"). */
private fun leadingPhrase(words: List<String>, phrase: List<String>): Int {
    for (start in phrase.indices) {
        val taken = matchFrom(words, phrase, start)
        if (taken > 0) return taken
    }
    if (phrase.size > 1 && aloneWord(words[0], phrase[0])) return 1
    return 0
}
