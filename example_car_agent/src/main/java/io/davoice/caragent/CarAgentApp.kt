package io.davoice.caragent

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.davoice.caragent.agent.AgentPack
import io.davoice.caragent.agent.CarLlm
import io.davoice.caragent.agent.CarModel
import io.davoice.caragent.agent.ConfidenceSettings
import io.davoice.caragent.agent.TurnInfo
import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.car.Car
import io.davoice.caragent.car.CarTools
import io.davoice.caragent.car.FollowUp
import io.davoice.caragent.car.SpeechComposer
import io.davoice.caragent.conversation.Cancellable
import io.davoice.caragent.conversation.ConversationCommands
import io.davoice.caragent.conversation.ConversationController
import io.davoice.caragent.conversation.ConversationPhase
import io.davoice.caragent.conversation.ConversationSettings
import io.davoice.caragent.conversation.defaultConversationSettings
import io.davoice.caragent.conversation.dropWakePhrase
import io.davoice.caragent.conversation.normalizeConversationSettings
import io.davoice.caragent.conversation.routingConfigForConversation
import io.davoice.caragent.flow.AppLanguage
import io.davoice.caragent.flow.appLanguageInfo
import io.davoice.caragent.flow.didNotCatchTexts
import io.davoice.caragent.flow.isBareDone
import io.davoice.caragent.flow.isNoiseFragment
import io.davoice.caragent.flow.mergeTranscript
import io.davoice.caragent.flow.normalizeTextForSpeech
import io.davoice.caragent.flow.originalTtsVoices
import io.davoice.caragent.flow.splitCompletedSentences
import io.davoice.caragent.speaker.CurrentSpeaker
import io.davoice.caragent.speaker.enrollmentSentences
import io.davoice.caragent.tts.LanguagePacks
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.voice.AssetInstaller
import io.davoice.caragent.voice.SpeakerVerificationController
import io.davoice.caragent.voice.SpeechController
import io.davoice.caragent.voice.WakewordController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The whole app flow (the Kotlin counterpart of the React Native / Flutter example_car_agent's App / main): language -> voice ->
// speaker verification -> the wake word -> the car agent. Screens read this object's state (Compose state) and call its methods.

enum class Stage { LANGUAGE, DOWNLOAD, PREPARING, VOICE, SPEAKER, ENROLL, WAKE, CAR }

/** Shown on the speaker screen after a failed recording (the same text as the React Native and Flutter apps). */
const val SV_ENROLLMENT_FAILED_NOTICE = "I couldn't hear the samples clearly. Try again in a quiet place, or skip this step."

class CarAgentApp(private val context: Context, private val scope: CoroutineScope, private val requestMicPermission: suspend () -> Boolean) {
    private val tag = "CarAgent"
    private val assetRead: (String) -> String = { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
    val packs = LanguagePacks(assetRead, context.filesDir)
    private val assets = AssetInstaller(context)
    val speech = SpeechController(context)
    private val wakeword = WakewordController(context)
    private val sv = SpeakerVerificationController(context, assets)
    val llm = CarLlm(context, VoiceConfig.DAVOICE_LICENSE_KEY)

    // ---- screen state ----------------------------------------------------------------------------------------------------------
    var stage by mutableStateOf(Stage.LANGUAGE)
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    var language by mutableStateOf("en")
    var downloadDone by mutableStateOf(0L)
    var downloadTotal by mutableStateOf(0L)
    var voices by mutableStateOf(listOf<String>())
    var voice by mutableStateOf("Rich")
    var svHasSaved by mutableStateOf(false)
    var svCollected by mutableStateOf(0)
    var svSentences by mutableStateOf(listOf<String>())
    var driverOnly by mutableStateOf(false)
    var modelStatus by mutableStateOf("")
    var modelProgress by mutableStateOf(0.0)
    var heard by mutableStateOf("")
    var reply by mutableStateOf("")
    var working by mutableStateOf(false)
    var ignored by mutableStateOf(false)
    var lastTurn by mutableStateOf(TurnInfo())
    var conversationPhase by mutableStateOf(ConversationPhase.OFF)
    var conversationSettings by mutableStateOf(defaultConversationSettings)
    var confidence by mutableStateOf(ConfidenceSettings.defaults)
    var carVersion by mutableIntStateOf(0)

    // ---- conversation --------------------------------------------------------------------------------------------------------
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ConversationTimers") }
    val conversation = ConversationController(
        ConversationCommands(
            openMic = { preFetchMs, why -> runBlocking { openMic(preFetchMs, why) } },
            closeMic = { _ -> runBlocking { closeMic() } },
            armWakeWord = { _ -> runBlocking { wakeword.unPauseDetection() } },
            disarmWakeWord = { _ -> runBlocking { wakeword.pauseDetection(false) } },
            stopReply = { why -> runBlocking { endReplyNow(why); speech.stopSpeaking() } },
            onPhase = { p ->
                scope.launch(Dispatchers.Main) {
                    conversationPhase = p
                    if (p == ConversationPhase.ASLEEP) message = if (conversationSettings.wakeWord) "Say “${VoiceConfig.WAKE_PHRASE}” before each request." else "Say “${VoiceConfig.WAKE_PHRASE}” to continue."
                }
            },
            log = { Log.i(tag, it) },
            timer = { ms, fn -> val f = timers.schedule(fn, ms, TimeUnit.MILLISECONDS); Cancellable { f.cancel(false) } },
        ),
        defaultConversationSettings,
    )

    // ---- the current request ---------------------------------------------------------------------------------------------------
    private val chat = mutableListOf<Obj>()
    @Volatile private var inFlight = false
    @Volatile private var speechEpoch = 0
    @Volatile private var lastTranscript = ""
    @Volatile private var spokenThisTurn = ""
    @Volatile private var wakePrefixPending = false
    private var silenceJob: Job? = null
    private var warmUp: Job? = null
    private val prefs = context.getSharedPreferences("car_agent", Context.MODE_PRIVATE)
    private val conversationFile = File(context.filesDir, "conversation_settings.json")

    init {
        CarTools.load(assetRead)
        AgentPack.load(assetRead)
        Car.listen { scope.launch(Dispatchers.Main) { carVersion++ } }
        confidence = ConfidenceSettings.parse(runCatching { Json.parse(prefs.getString("confidence", "") ?: "") }.getOrNull())
        llm.confidence = confidence
        conversationSettings = runCatching { normalizeConversationSettings(Json.parse(conversationFile.readText())) }.getOrDefault(defaultConversationSettings)
        conversation.update(conversationSettings)
        language = prefs.getString("language", "en") ?: "en"
        scope.launch { collectSpeechEvents() }
        wakeword.onDetection = { _, _ -> scope.launch(Dispatchers.Main) { onWakeWord() } }
    }

    private val speakerSpeed get() = if (AppLanguage.current != "en") 1.0f else if (voice == "Rich") 0.95f else 1.0f

    // ---- 1. language ---------------------------------------------------------------------------------------------------------
    fun chooseLanguage(id: String) {
        if (busy) return
        language = id
        AppLanguage.set(id)
        prefs.edit().putString("language", id).apply()
        if (packs.isReady(id)) prepare() else {
            stage = Stage.DOWNLOAD
            scope.launch {
                try {
                    packs.download(id, { done, total -> scope.launch(Dispatchers.Main) { downloadDone = done; downloadTotal = total } })
                    prepare()
                } catch (t: Throwable) {
                    message = "The voice could not be downloaded: ${t.message}"
                    stage = Stage.LANGUAGE
                }
            }
        }
    }

    // ---- 2. wake word + speech ------------------------------------------------------------------------------------------------
    private var wakewordCreated = false

    private fun prepare() {
        stage = Stage.PREPARING
        message = "Preparing the voice…"
        scope.launch {
            try {
                if (!requestMicPermission()) error("The microphone permission is needed.")
                if (!wakewordCreated) {
                    wakeword.setAudioRoutingConfig(VoiceConfig.audioRoutingJson(routingConfigForConversation(VoiceConfig.audioRouting, conversationSettings)))
                    wakeword.create(VoiceConfig.WAKE_WORDS)
                    check(wakeword.setLicense(VoiceConfig.DAVOICE_LICENSE_KEY) && speech.setLicense(VoiceConfig.DAVOICE_LICENSE_KEY)) {
                        "License not valid: Please contact info@davoice.io for a new license"
                    }
                    wakeword.startKeywordDetection()
                    wakewordCreated = true
                } else {
                    speech.destroyAll()
                }
                wakeword.pauseDetection(false)
                initSpeech(null)
                wakeword.unPauseDetection()
                // a new language: a new agent (its pack and reply language)
                llm.languageChanged()
                stage = Stage.VOICE
                message = ""
            } catch (t: Throwable) {
                Log.e(tag, "voice setup failed", t)
                message = "Voice setup failed: ${t.message}"
                stage = Stage.LANGUAGE
            }
        }
    }

    private suspend fun initSpeech(enrollmentJson: String?) {
        speech.initAll(appLanguageInfo(language).sttLocale, packs.modelPath(language), enrollmentJson, VoiceConfig.SV_SPEAKER_ID)
        speech.pauseSpeechRecognition()
        val all = speech.getAllVoices()
        voices = all.ifEmpty { listOf("Rich") }
        if (voice !in voices) {
            val preferred = originalTtsVoices[language] ?: "Rich"
            voice = if (preferred in voices) preferred else if ("Rich" in voices) "Rich" else voices.first()
        }
        speech.changeVoice(voice)
    }

    // ---- 3. voice -------------------------------------------------------------------------------------------------------------
    fun chooseVoice(v: String) {
        voice = v
        scope.launch {
            speech.stopSpeaking()
            speech.changeVoice(v)
            speech.speak(if (AppLanguage.isPortuguese) "Olá! Eu sou a voz do seu carro." else "Hi! I'll be the voice of your car.", 0, speakerSpeed)
        }
    }

    fun continueFromVoice() {
        svHasSaved = sv.loadSavedEnrollment() != null
        stage = Stage.SPEAKER
    }

    // ---- 4. speaker verification ----------------------------------------------------------------------------------------------
    fun skipSpeaker() = scope.launch {
        CurrentSpeaker.setGate(false, null)
        driverOnly = false
        toWakeWord()
    }

    fun useSavedSpeaker() = scope.launch { applySpeaker(sv.loadSavedEnrollment() ?: return@launch) }

    fun createSpeaker() {
        svSentences = enrollmentSentences(language, VoiceConfig.WAKE_PHRASE, VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT)
        svCollected = 0
        stage = Stage.ENROLL
        scope.launch {
            try {
                // The wake word releases the microphone while the samples are recorded (it kept it before, and a sample never came).
                wakeword.pauseDetection(true)
                val json = sv.enroll(VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT) { collected, _ -> scope.launch(Dispatchers.Main) { svCollected = collected } }
                sv.saveEnrollment(json)
                applySpeaker(json)
            } catch (t: Throwable) {
                Log.w(tag, "voice signature not recorded: $t")
                message = SV_ENROLLMENT_FAILED_NOTICE
                stage = Stage.SPEAKER
            } finally {
                wakeword.unPauseDetection()
            }
        }
    }

    private suspend fun applySpeaker(json: String) {
        try {
            wakeword.addSpeaker(VoiceConfig.SV_SPEAKER_ID, json)
            speech.addSpeaker(VoiceConfig.SV_SPEAKER_ID, json)
            CurrentSpeaker.setGate(true, VoiceConfig.SV_SPEAKER_ID)
            driverOnly = true
        } catch (t: Throwable) {
            Log.w(tag, "speaker verification not applied: $t")
            CurrentSpeaker.setGate(false, null)
            driverOnly = false
        }
        toWakeWord()
    }

    private fun toWakeWord() {
        stage = Stage.WAKE
        message = "Say “${VoiceConfig.WAKE_PHRASE}” to start."
        startWarmUp()
    }

    /** The model downloads and warms up in the background while the driver gets to the car screen. */
    private fun startWarmUp() {
        if (warmUp?.isActive == true || llm.isModelReady) return
        warmUp = scope.launch {
            try {
                modelStatus = if (llm.store.isDownloaded()) "Loading" else "Downloading 0%"
                llm.warmUp(chat.toList(), { f ->
                    scope.launch(Dispatchers.Main) {
                        modelProgress = f
                        modelStatus = "Downloading ${(f * 100).toInt()}%"
                    }
                }, { scope.launch(Dispatchers.Main) { modelStatus = "Warming up" } })
                modelStatus = "Ready"
            } catch (t: Throwable) {
                Log.e(tag, "model warm-up failed", t)
                modelStatus = "Failed: ${t.message}"
            }
        }
    }

    // ---- 5. the wake word and the car screen -----------------------------------------------------------------------------------
    private suspend fun onWakeWord() {
        when {
            conversation.active -> {
                // in the conversation: wakes it from sleep or stops the agent
                wakePrefixPending = true
                conversation.wakeWord()
            }
            stage == Stage.WAKE -> enterCar()
        }
    }

    /** The first wake word (or the screen's button): the car screen, listening. */
    fun enterCar() = scope.launch {
        if (stage == Stage.CAR) return@launch
        stage = Stage.CAR
        heard = ""
        reply = ""
        resetTranscript()
        wakePrefixPending = false
        if (conversationSettings.wakeWord) {
            // wake word before each request: recognition stays paused and the conversation starts asleep (conversation.enter
            // arms the wake word); the wake word that opened this screen is not a request
            message = "Say “${VoiceConfig.WAKE_PHRASE}” before each request."
        } else {
            message = "Listening…"
            wakeword.pauseDetection(false)
            speech.unPauseSpeechRecognition(-1, 0)
        }
        conversation.enter()
        startWarmUp()
    }

    /** Back: the wake-word screen, listening for the wake word again. */
    fun leaveCar() = scope.launch {
        speechEpoch++
        inFlight = false
        working = false
        conversation.leave(rearmWakeWord = true)
        silenceJob?.cancel()
        speech.stopSpeaking()
        speech.pauseSpeechRecognition()
        stage = Stage.WAKE
        message = "Say “${VoiceConfig.WAKE_PHRASE}” to talk to the car agent."
    }

    fun resetChat() {
        chat.clear()
        heard = ""
        reply = ""
        lastTurn = TurnInfo()
        FollowUp.reset()
    }

    fun tapToTalk() = conversation.tapToTalk()

    // ---- settings ---------------------------------------------------------------------------------------------------------------
    fun changeConfidence(next: ConfidenceSettings) {
        confidence = next
        llm.confidence = next
        prefs.edit().putString("confidence", Json.stringify(next.toJson())).apply()
    }

    fun changeConversation(next: ConversationSettings) {
        val previous = conversationSettings
        conversationSettings = next
        conversation.update(next)
        runCatching { conversationFile.writeText(Json.stringify(next.toJson())) }
        Log.i(tag, "[Conversation] settings ${Json.stringify(next.toJson())}")
        // interrupt on / off: echo cancellation in the routing JSON the wake word, STT and TTS share
        if (previous.interrupt != next.interrupt) {
            wakeword.setAudioRoutingConfig(VoiceConfig.audioRoutingJson(routingConfigForConversation(VoiceConfig.audioRouting, next)))
        }
    }

    // ---- speech recognition -----------------------------------------------------------------------------------------------------
    private suspend fun collectSpeechEvents() {
        speech.events.collect { e ->
            when (e) {
                is SpeechController.Event.Partial -> withContext(Dispatchers.Main) { onSpeech(e.text, e.speakerIds, e.speakerId) }
                is SpeechController.Event.Final -> withContext(Dispatchers.Main) { onSpeech(e.text, e.speakerIds, e.speakerId) }
                is SpeechController.Event.Error -> Log.i(tag, "speech error ${e.code}: ${e.message}")
                else -> {}
            }
        }
    }

    private fun onSpeech(text: String, speakerIds: List<String>, speakerId: String?) {
        CurrentSpeaker.noteResult(speakerIds, speakerId)
        val current = text.trim()
        if (stage != Stage.CAR || current.isEmpty()) return
        // asleep (waiting for the wake word or a tap): nothing heard counts, even if the recognizer was still open
        if (conversation.phase == ConversationPhase.ASLEEP) return
        // a request is being answered: dropped, unless the user interrupted the reply (interrupt mode)
        if (inFlight && !heardWhileBusy(current)) return
        conversation.userSpeech()
        lastTranscript = mergeTranscript(lastTranscript, current)
        // shown without the wake phrase after the wake word (as the agent gets it)
        heard = if (wakePrefixPending && conversationSettings.wakeWord) dropWakePhrase(lastTranscript, VoiceConfig.WAKE_PHRASE) else lastTranscript
        // the words so far into the model's cache while the driver still speaks
        // the wake phrase pre-fetched in front of the request never reaches the model
        val forModel = if (wakePrefixPending && conversationSettings.wakeWord) dropWakePhrase(lastTranscript, VoiceConfig.WAKE_PHRASE) else lastTranscript
        if (forModel.isNotEmpty()) scope.launch { llm.prefill(chat.toList(), forModel) }
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(conversationSettings.endOfSpeechMs.toLong())
            val t = lastTranscript.trim()
            if (t.isNotEmpty() && !inFlight) answer(t)
        }
    }

    private fun heardWhileBusy(text: String): Boolean {
        if (!conversation.heardWhileBusy(text, spokenThisTurn)) return false
        endReplyNow("the user interrupted")
        return true
    }

    private fun resetTranscript() {
        silenceJob?.cancel()
        lastTranscript = ""
    }

    private suspend fun openMic(preFetchMs: Int, why: String) {
        Log.i(tag, "[Conversation] open the microphone ($why)")
        withContext(Dispatchers.Main) { resetTranscript() }
        speech.unPauseSpeechRecognition(-1, preFetchMs)
        if (!conversation.duplexOpen) withContext(Dispatchers.Main) { if (!inFlight) message = "Listening…" }
    }

    private suspend fun closeMic() {
        withContext(Dispatchers.Main) { resetTranscript() }
        speech.pauseSpeechRecognition()
    }

    /** Interrupt mode, the stop word or a tap: the reply ends now (a new speech epoch drops the rest of the request). */
    private fun endReplyNow(why: String) {
        if (!inFlight) return
        Log.i(tag, "[Conversation] reply stopped: $why")
        speechEpoch++
        inFlight = false
        scope.launch(Dispatchers.Main) {
            working = false
            message = "Stopped. Listening…"
        }
    }

    // ---- one request (the Flutter / React Native apps' local-model turn) --------------------------------------------------------
    fun sendText(text: String) = scope.launch { answer(text) }

    private suspend fun answer(rawText: String) {
        val language = AppLanguage.current
        val userText = if (wakePrefixPending && conversationSettings.wakeWord) dropWakePhrase(rawText, VoiceConfig.WAKE_PHRASE).also { if (it.isNotEmpty()) wakePrefixPending = false } else rawText.trim()
        if (userText.isEmpty() || inFlight) return // only the wake phrase so far: the next words complete the request
        wakePrefixPending = false
        inFlight = true
        val epoch = ++speechEpoch
        fun current() = epoch == speechEpoch
        spokenThisTurn = ""
        conversation.turnStarted()
        silenceJob?.cancel()
        lastTranscript = ""
        working = true
        ignored = false
        heard = userText
        reply = ""
        val history = chat.toList()
        chat += mapOf("role" to "user", "text" to userText)
        var spoken: Job? = null
        var shown = ""
        fun speakSentence(raw: String, composed: Boolean = false) {
            if (!current()) return
            // the model's bare "Done." is never a confirmation (the app says what it did) -> "didn't catch that"; a Basic language's
            // Portuguese sentence -> "say it again?" in the user's language
            val sentence = if (composed) raw else if (isBareDone(raw)) didNotCatch(language) else BasicLanguage.replyFix(raw, language) ?: raw
            spokenThisTurn += " $sentence"
            spoken = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { speech.speak(normalizeTextForSpeech(sentence), 0, speakerSpeed) }
            shown = if (shown.isEmpty()) sentence else "$shown $sentence"
            scope.launch(Dispatchers.Main) { if (current()) reply = shown }
        }
        suspend fun sayWhole(text: String) {
            chat += mapOf("role" to "model", "text" to text)
            conversation.replySpeaking()
            speakSentence(text, composed = true)
            spoken?.join()
        }
        try {
            speech.pauseSpeechRecognition()
            if (!current()) return
            // speech noise ("The.", "But.", "Alma Alma."): not sent to the model
            if (isNoiseFragment(userText) && BasicLanguage.normalizeYesNo(userText, language) == null) {
                sayWhole(didNotCatch(language))
                return
            }
            // what goes to the agent for these words (an answer to the agent's question goes with its request, a yes to a pending
            // offer), or the app's own short reply ("never mind" -> "Okay.")
            val s = Car.state
            val input = FollowUp.carTurnInput(userText, language, SpeechComposer.pendingOfferCalls(),
                musicOn = (s.media != null || s.radio != null) && !s.muted, state = s, lastActed = SpeechComposer.carTurnActions().isNotEmpty())
            if (input.reply != null) {
                FollowUp.noteCarSpoken(input.reply)
                sayWhole(input.reply)
                return
            }
            val agentText = input.text ?: userText
            if (!llm.isModelReady) {
                val st = modelStatus
                sayWhole(when {
                    st.startsWith("Failed") -> { startWarmUp(); "The on-device model, ${CarModel.DISPLAY_NAME}, failed to load, so I am trying again. Please ask me again in a moment." }
                    st.startsWith("Downloading") -> "The on-device model, ${CarModel.DISPLAY_NAME}, is still downloading. It is ${(modelProgress * 100).toInt()} percent done. Please ask me again when it is ready."
                    else -> "The on-device model, ${CarModel.DISPLAY_NAME}, is still loading. Please ask me again in a moment."
                })
                return
            }
            message = "Thinking…"
            var replyText = ""
            var pending = ""
            var held = ""
            var acked = false
            var ackSaid = ""
            var spoke = false
            if (agentText != userText) Log.i(tag, "[CAR] \"$userText\" -> agent: \"$agentText\"")
            CarTools.noteUserText(agentText)
            SpeechComposer.beginCarTurn()
            // a request that takes a moment gets a short "Got it." so the driver is not left in silence
            val ack = scope.launch {
                delay(CAR_ACK_AFTER_MS)
                if (!current() || spoke || held.isNotBlank()) return@launch
                val a = SpeechComposer.carAck(language) ?: return@launch
                acked = true
                ackSaid = a
                speakSentence(a)
            }
            val turn = try {
                llm.turn(history, agentText, onDelta = { delta ->
                    if (current()) {
                        replyText += delta
                        // once a car action ran in this request, its sentences are held and said together at the end
                        if (SpeechComposer.carTurnActions().isNotEmpty()) {
                            held += delta
                        } else {
                            val (done, rest) = splitCompletedSentences(pending + delta)
                            pending = rest
                            if (done.isNotEmpty()) spoke = true
                            done.forEach { speakSentence(it) }
                        }
                    }
                }, onEvent = { e ->
                    if (current() && e.type == "filler") {
                        spoke = true
                        speakSentence(e.text)
                    }
                })
            } finally {
                ack.cancel()
            }
            if (!current()) return
            lastTurn = TurnInfo.read(turn.json)
            if (turn.json["scope"].let { it is Map<*, *> && it["outcome"] == "ignore" } && replyText.isBlank()) {
                // background talk, not for the assistant: nothing said, the heard text stays on screen (marked)
                chat.removeAt(chat.size - 1)
                ignored = true
                message = "Not for me: ignored."
                return
            }
            if (pending.isNotBlank()) speakSentence(pending.trim())
            if (held.isNotBlank()) {
                val composed = SpeechComposer.composeCarSpeech(held, SpeechComposer.carTurnActions(), language, acked, Car.state.driving)
                if (composed != held.trim()) Log.i(tag, "[SPEECH] \"${held.trim()}\" -> \"$composed\"")
                val heldSet = splitCompletedSentences("${held.trim()} ").first.toSet()
                for (sentence in splitCompletedSentences("$composed ").first) speakSentence(sentence, composed = sentence !in heldSet)
            }
            val r = replyText.trim()
            // a question in the reply: the next short answer goes with this request
            FollowUp.noteCarReply(agentText, r, language)
            val said = if (ackSaid.isNotEmpty() && shown.startsWith(ackSaid)) shown.substring(ackSaid.length).trim() else shown.trim()
            FollowUp.noteCarSpoken(said.ifEmpty { r })
            Log.i(tag, "[LOCAL_LLM] \"$userText\" -> $r")
            chat += linkedMapOf<String, Any?>("role" to "model", "text" to r).also { if (turn.toolMessages.isNotEmpty()) it["toolMessages"] = turn.toolMessages }
            message = "Speaking…"
            conversation.replySpeaking()
            spoken?.join()
            if (current()) reply = shown.ifEmpty { r }
        } catch (t: Throwable) {
            if (!current()) return
            Log.e(tag, "turn failed", t)
            val text = "Local AI chat error: ${t.message}"
            reply = text
            sayWhole(text)
        } finally {
            if (current()) {
                inFlight = false
                working = false
                // the request ended: the conversation mode decides what's next (listen again, or back to the wake word)
                if (conversation.phase == ConversationPhase.BUSY) conversation.replyFinished(spokenThisTurn)
            }
        }
    }

    private fun didNotCatch(language: String) = didNotCatchTexts[language] ?: BasicLanguage.message(language, "didNotCatch") ?: didNotCatchTexts["en"]!!

    private companion object {
        const val CAR_ACK_AFTER_MS = 1200L
    }
}
