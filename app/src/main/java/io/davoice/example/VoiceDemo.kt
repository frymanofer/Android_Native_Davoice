package io.davoice.example

import android.content.Context
import android.util.Log
import io.davoice.example.chat.GeminiClient
import io.davoice.example.chat.LocalLlm
import io.davoice.example.chat.LocalModel
import io.davoice.example.chat.SpokenText
import io.davoice.example.voice.AssetInstaller
import io.davoice.example.voice.SpeakerVerificationController
import io.davoice.example.voice.SpeechController
import io.davoice.example.voice.TranscriptMerger
import io.davoice.example.voice.WakewordController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class Screen { Status, ChooseVoice, SpeakerVerificationPrompt, SpeakerVerificationStatus, ChooseMode, Combined, STTOnly, TypeToTTS, Chat }
enum class SvChoice { UseExisting, RedoOnboarding, Skip }
enum class SvPhase { Idle, Onboarding, Verifying }

data class ChatLine(val fromUser: Boolean, val text: String)

data class UiState(
    val screen: Screen = Screen.Status,
    val message: String = "Preparing voice demo...",
    /** What is being heard or said right now. */
    val sentence: String = "",
    /** Increments on every wake-word detection (the UI flashes). */
    val wakeWordCount: Int = 0,
    val narrating: Boolean = false,
    val voice: Voice = DemoConfig.DEFAULT_VOICE,
    val svHasSaved: Boolean = false,
    val svPhase: SvPhase = SvPhase.Idle,
    val svCollected: Int = 0,
    val svTarget: Int = DemoConfig.SV_ONBOARDING_SAMPLE_COUNT,
    val svScore: Float? = null,
    val svMatch: Boolean = false,
    val svCanContinue: Boolean = false,
    val mode: AppMode? = null,
    val lastSentSentence: String = "",
    val manualTtsSpeaking: Boolean = false,
    val chatStatus: String = "Waiting for your voice...",
    val chatLines: List<ChatLine> = emptyList(),
    val chatLoading: Boolean = false,
    val localModelName: String = LocalLlm.DEFAULT.displayName,
    val localModelStatus: String = "",
    val recordings: List<String> = emptyList(),
)

/**
 * The demo's flow, ported from the React Native example's App.tsx:
 *
 *  1. Load wake word + speech, introduce the demo (spoken).
 *  2. Pick a voice, then set up (or skip) speaker verification.
 *  3. Say the wake word -> pick one of five modes -> talk.
 *
 * All flow logic runs on [scope] (main thread); the controllers move native
 * calls to background threads themselves.
 */
class VoiceDemo(context: Context, private val scope: CoroutineScope) {

    private val tag = "VoiceDemo"
    private val assets = AssetInstaller(context)
    private val speech = SpeechController(context)
    private val wakeword = WakewordController(context)
    private val speakerVerification = SpeakerVerificationController(context, assets)
    private val gemini = GeminiClient()
    private val localLlm = LocalLlm(context)

    private val _state = MutableStateFlow(UiState(localModelName = localLlm.selectedModel.displayName))
    val state: StateFlow<UiState> = _state
    private fun ui(change: UiState.() -> UiState) = _state.update(change)

    // ---- Flow state ---------------------------------------------------------------
    private var started = false
    private var startupInProgress = true
    private var firstWakeWord = true
    private var creatingSpeakerSignature = false
    private var narrationActive = false
    private var narrationSpeechPending = false
    private var narrationInterrupted = false
    private var suppressNarrationWakewordStop = false
    private var skipNarration = false
    private var suppressPartialResults = false
    private var enrollmentJson: String? = null
    private var enrollmentPath: String? = null
    private var loadedModelPath: String? = null

    // Pending user choices (resolved by the UI).
    private var voiceChoice: CompletableDeferred<Voice>? = null
    private var svChoice: CompletableDeferred<SvChoice>? = null
    private var svContinue: CompletableDeferred<Unit>? = null
    private var modeChoice: CompletableDeferred<AppMode>? = null

    // Speech session.
    private var transcript = ""
    private var lastProcessed = ""
    private var silenceTimer: Job? = null
    private var chatInFlight = false
    private var geminiHistory = mutableListOf<GeminiClient.Turn>()
    private var localHistory = mutableListOf<LocalLlm.Turn>()
    private var lastChatSubmitAt = 0L
    private var lastChatSubmittedText = ""
    private var chatBlockedUntil = 0L
    private var liveVerification: SpeakerVerificationController.Verification? = null

    private val wakeWordNames = DemoConfig.WAKE_WORDS.joinToString(" or ") { DemoConfig.displayName(it.assetName) }

    init {
        scope.launch { speech.events.collect { onSpeechEvent(it) } }
        wakeword.onDetected = { model -> scope.launch { onWakeWord(model) } }
    }

    // ================================================================================
    // Startup
    // ================================================================================

    /** Call once the microphone permission is granted. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                if (!bootstrap()) return@launch
                runStartupGuide()
            } catch (t: Throwable) {
                Log.e(tag, "Voice setup failed", t)
                ui { copy(message = "Voice setup failed: ${t.message}. Restart the app to retry.") }
            }
        }
    }

    /** Wake word + licenses + speech engine. Returns false if setup can't continue. */
    private suspend fun bootstrap(): Boolean {
        wakeword.setAudioRoutingConfig(DemoConfig.AUDIO_ROUTING_CONFIG_JSON)
        try {
            wakeword.create(DemoConfig.WAKE_WORDS)
        } catch (t: Throwable) {
            Log.e(tag, "Wake-word model load failed", t)
            ui { copy(message = "Could not load the wake-word model: ${t.message}") }
            return false
        }
        if (!wakeword.setLicense(DemoConfig.DAVOICE_LICENSE_KEY) || !speech.setLicense(DemoConfig.DAVOICE_LICENSE_KEY)) {
            ui { copy(message = "License not valid: Please contact info@davoice.io for a new license") }
            return false
        }
        wakeword.start()

        var speechReady = false
        try {
            suppressPartialResults = true
            ui { copy(message = "Initializing speech engine...") }
            withTimeout(SPEECH_INIT_TIMEOUT_MS) { initializeSpeech() }
            speechReady = true
        } catch (t: Throwable) {
            Log.e(tag, "Speech init failed", t)
            ui { copy(message = "Speech setup failed. Restart the app to retry.") }
        } finally {
            suppressPartialResults = false
            wakeword.resume()
        }
        return speechReady
    }

    /** Speech.initAll + the selected voice; recognition left paused. */
    private suspend fun initializeSpeech() {
        speech.pauseRecognition()
        val model = _state.value.voice.model()
        val path = installedPath(model.assetPath)
        speech.initAll(path, enrollmentJson)
        loadedModelPath = path
        applySelectedVoice()
        speech.pauseRecognition()
    }

    /** Model changed (voice or speaker enrollment): reload speech. Wake word keeps running. */
    private suspend fun reloadSpeech() {
        try {
            suppressPartialResults = true
            ui { copy(message = "Loading selected preferences...") }
            runCatching { speech.stopSpeaking() }
            speech.destroyAll()
            withTimeout(SPEECH_INIT_TIMEOUT_MS) { initializeSpeech() }
        } finally {
            suppressPartialResults = false
        }
    }

    /** Copies a bundled model out of the APK (first run: up to ~150 MB) off the main thread. */
    private suspend fun installedPath(assetPath: String) = withContext(Dispatchers.IO) { assets.path(assetPath) }

    /** ttsv2 models hold several voices; pick the selected one. */
    private suspend fun applySelectedVoice() {
        val voice = _state.value.voice
        if (!voice.model().usesTTS2) return
        if (speech.changeVoice(voice.displayName) != 0) Log.w(tag, "changeVoice(${voice.displayName}) failed")
    }

    private suspend fun runStartupGuide() {
        val narrator = _state.value.voice
        val others = Voice.entries.filter { it != narrator }.joinToString(" or ") { it.displayName }
        speakNarration(listOf(
            "Hey there, My name is ${narrator.displayName}. In this application we will use my cloned voice in order to showcase our voice AI agent capabilities.",
            "Don't worry. I will be your personal guide to walk you through this demonstration step by step.",
            "First, please choose which voice you want to use. You can stay with me, ${narrator.displayName}, or switch to $others.",
        ))

        // 1) Voice
        val chosen = CompletableDeferred<Voice>().also { voiceChoice = it }
        ui { copy(screen = Screen.ChooseVoice) }
        val voice = chosen.await()
        voiceChoice = null
        ui { copy(screen = Screen.Status, voice = voice) }
        if (installedPath(voice.model().assetPath) != loadedModelPath) reloadSpeech() else applySelectedVoice()

        speakNarration(listOf(
            if (voice == narrator) "Awesome, Thanks for choosing to stay with me." else "Awesome, You chose ${voice.displayName}.",
            "The next phase, is setting speaker verification. You can create a new speaker signature, use a saved one, or skip this step.",
        ))
        runCatching { speech.pauseRecognition() }

        // 2) Speaker verification
        val svResult = runSpeakerVerificationSetup()
        if (svResult != SvChoice.Skip) {
            reloadSpeech() // STT now only transcribes the enrolled speaker
            wakeword.start(enrollmentPath) // wake word now only fires for the enrolled speaker
            wakeword.resume()
        }

        speakNarration(listOf(
            if (svResult == SvChoice.Skip) "Speaker verification skipped." else "Speaker verification is ready!",
            "Now please say the wake word $wakeWordNames to continue.",
        ), interruptOnWakeWord = false)
        startupInProgress = false
        ui { copy(message = "Say the wake word \"$wakeWordNames\" to continue.") }
    }

    private suspend fun runSpeakerVerificationSetup(): SvChoice {
        val saved = speakerVerification.loadSavedEnrollment()
        val choiceDeferred = CompletableDeferred<SvChoice>().also { svChoice = it }
        ui { copy(screen = Screen.SpeakerVerificationPrompt, svHasSaved = saved != null) }
        val choice = choiceDeferred.await()
        svChoice = null

        if (choice == SvChoice.Skip) {
            enrollmentJson = null
            enrollmentPath = null
            ui { copy(screen = Screen.Status) }
            return choice
        }

        ui { copy(screen = Screen.SpeakerVerificationStatus, svCanContinue = false, svCollected = 0, svScore = null) }
        var json = saved
        if (choice == SvChoice.RedoOnboarding || json == null) {
            ui { copy(svPhase = SvPhase.Onboarding, message = "Speaker verification: say a few sentences in your normal voice.") }
            // Wake word pauses while a new signature is recorded (both want the mic).
            creatingSpeakerSignature = true
            wakeword.pause(false)
            try {
                json = speakerVerification.enroll { collected, target ->
                    scope.launch { ui { copy(svCollected = collected, svTarget = target) } }
                }
                speakerVerification.saveEnrollment(json)
            } finally {
                wakeword.resume()
                creatingSpeakerSignature = false
            }
        }
        enrollmentJson = json
        enrollmentPath = speakerVerification.enrollmentFile.absolutePath

        ui { copy(svPhase = SvPhase.Verifying, message = "Verify Speaker Identification Now") }
        liveVerification = speakerVerification.startLiveVerification(json!!) { score, match ->
            scope.launch {
                ui {
                    copy(
                        svScore = score, svMatch = match, svCanContinue = true,
                        message = "Speaker Identification Match=${if (match) "✅" else "❌"}",
                    )
                }
            }
        }
        CompletableDeferred<Unit>().also { svContinue = it }.await()
        svContinue = null
        liveVerification?.stop()
        liveVerification = null
        ui { copy(screen = Screen.Status, svPhase = SvPhase.Idle, svCanContinue = false) }
        return choice
    }

    // ================================================================================
    // Narration (the spoken guide)
    // ================================================================================

    private suspend fun speakNarration(lines: List<String>, interruptOnWakeWord: Boolean = true) {
        narrationActive = true
        suppressNarrationWakewordStop = !interruptOnWakeWord
        narrationInterrupted = false
        ui { copy(narrating = true) }
        try {
            runCatching { speech.pauseRecognition() }
            applySelectedVoice()
            for (line in lines) {
                if (skipNarration || narrationInterrupted) break
                ui { copy(message = line) }
                narrationSpeechPending = true
                try {
                    speakText(line)
                } finally {
                    narrationSpeechPending = false
                }
            }
        } finally {
            delay(500) // let wake-word detections of the playback tail settle
            narrationActive = false
            suppressNarrationWakewordStop = false
            ui { copy(narrating = false) }
        }
    }

    private suspend fun stopCurrentNarration() {
        if (!narrationSpeechPending) return
        narrationInterrupted = true
        runCatching { speech.stopSpeaking() }
    }

    fun skipNarration() {
        if (skipNarration) return
        skipNarration = true
        scope.launch { stopCurrentNarration() }
    }

    private suspend fun speakText(text: String) =
        speech.speak(SpokenText.applyTtsPolicy(text), _state.value.voice.model().speed)

    // ================================================================================
    // Wake word
    // ================================================================================

    private suspend fun onWakeWord(model: String) {
        Log.d(tag, "onWakeWord($model) startup=$startupInProgress narration=$narrationActive enrolling=$creatingSpeakerSignature first=$firstWakeWord")
        if (creatingSpeakerSignature) return
        if (startupInProgress || narrationActive) {
            // During the guide, saying the wake word only skips the current line.
            if (!suppressNarrationWakewordStop) stopCurrentNarration()
            return
        }
        val isFirst = firstWakeWord
        firstWakeWord = false

        // Pause (mic stays open) and keep the audio that triggered the detection.
        wakeword.pause(false)
        val recordings = wakeword.lastRecordings()
        delay(1000)

        val name = DemoConfig.displayName(model)
        ui {
            copy(
                message = "WakeWord '$name' DETECTED", wakeWordCount = wakeWordCount + 1,
                recordings = recordings.ifEmpty { this.recordings },
            )
        }

        val mode = if (isFirst) {
            ui { copy(mode = null, sentence = "") }
            runCatching { speech.pauseRecognition() }
            speakNarration(listOf("$name detected."))
            ui { copy(message = "WakeWord '$name' DETECTED") }
            speakNarration(listOf(
                "Now you can test the voice capabilities in five different ways. You can select a Local AI Chat that runs fully on this device, a full AI Chat using remote Gemini, or simply test Speech to Text and Text to Speech individually or a combination of both.",
            ))
            val chosen = CompletableDeferred<AppMode>().also { modeChoice = it }
            ui { copy(screen = Screen.ChooseMode) }
            chosen.await().also { modeChoice = null }
        } else {
            _state.value.mode ?: AppMode.Combined
        }
        enterMode(mode, withIntro = true)
    }

    // ================================================================================
    // Modes
    // ================================================================================

    private suspend fun enterMode(mode: AppMode, withIntro: Boolean) {
        ui { copy(mode = mode) }
        when (mode) {
            AppMode.LocalAIChat, AppMode.FullAIChat -> { enterChat(mode); return }
            else -> Unit
        }
        if (withIntro) {
            // Reset the recognizer between sessions (mirrors the RN demo's intro step).
            runCatching { speech.pauseRecognition() }
            resetTranscript()
            speech.unpauseRecognition(-1)
            delay(500)
            ui { copy(sentence = "") }
        }
        resetTranscript()
        when (mode) {
            AppMode.TypeToTTS -> ui { copy(screen = Screen.TypeToTTS, message = "Type to TTS") }
            AppMode.STTOnly -> {
                ui { copy(screen = Screen.STTOnly, message = "Speak; each sentence appears below.", lastSentSentence = "") }
                speech.unpauseRecognition(-1)
            }
            else -> {
                ui { copy(screen = Screen.Combined, message = "Speak; the selected voice repeats what you said.") }
                speech.unpauseRecognition(-1)
            }
        }
    }

    private suspend fun enterChat(mode: AppMode) {
        val local = mode == AppMode.LocalAIChat
        if (local) warmUpLocalModel()
        resetChat()
        ui {
            copy(
                screen = Screen.Chat,
                message = if (local) "Local AI Chat is active. Start speaking." else "AI Chat (Gemini) is active. Start speaking.",
            )
        }
        restartMicrophone()
    }

    /** Back button in any mode. */
    fun backToModes() {
        scope.launch {
            silenceTimer?.cancel()
            resetTranscript()
            resetChat()
            runCatching { speech.stopSpeaking() }
            ui { copy(screen = Screen.ChooseMode, sentence = "", message = "Choose what you want to test next.", manualTtsSpeaking = false) }
            runCatching { speech.pauseRecognition() }
        }
    }

    // ================================================================================
    // Speech events: running transcript + silence timeout
    // ================================================================================

    private fun onSpeechEvent(event: SpeechController.Event) {
        val text = when (event) {
            is SpeechController.Event.Partial -> event.text
            is SpeechController.Event.Final -> event.text
            is SpeechController.Event.Error -> { Log.d(tag, "STT error ${event.code}: ${event.message}"); return }
            else -> return
        }
        if (suppressPartialResults || !speechResultsAllowed() || chatInFlight) return
        if (text.isBlank()) return

        val merged = TranscriptMerger.merge(transcript, text)
        restartSilenceTimer()
        if (merged == transcript) return
        transcript = merged
        ui { copy(sentence = merged) }
        if (_state.value.screen == Screen.Chat && _state.value.mode == AppMode.LocalAIChat) {
            localLlm.prefill(localHistory, merged)
        }
    }

    private fun speechResultsAllowed() = _state.value.screen in setOf(Screen.Combined, Screen.STTOnly, Screen.Chat)

    private fun restartSilenceTimer() {
        silenceTimer?.cancel()
        silenceTimer = scope.launch {
            delay(DemoConfig.SILENCE_TIMEOUT_MS)
            onUtterance(transcript.trim())
        }
    }

    private suspend fun onUtterance(text: String) {
        if (text.isEmpty() || chatInFlight) return
        when (_state.value.screen) {
            Screen.Chat -> if (text != lastProcessed) processChatTurn(text)
            Screen.STTOnly -> {
                ui { copy(lastSentSentence = text, sentence = "") }
                resetTranscript()
            }
            Screen.Combined -> {
                ui { copy(sentence = "Speaking now: $text") }
                runCatching { speech.pauseRecognition() }
                speakText(text)
                resetTranscript()
                speech.unpauseRecognition(-1)
                delay(300)
                ui { copy(sentence = "") }
            }
            else -> Unit
        }
    }

    private fun resetTranscript() {
        transcript = ""
        lastProcessed = ""
    }

    // ================================================================================
    // Type to TTS
    // ================================================================================

    fun speakTyped(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.manualTtsSpeaking) return
        scope.launch {
            ui { copy(manualTtsSpeaking = true, sentence = "Speaking now: $trimmed") }
            try {
                speakText(trimmed)
            } finally {
                ui { copy(manualTtsSpeaking = false, sentence = "") }
            }
        }
    }

    // ================================================================================
    // AI chat (Gemini or on-device)
    // ================================================================================

    private fun resetChat() {
        geminiHistory.clear()
        localHistory.clear()
        chatInFlight = false
        lastChatSubmitAt = 0
        lastChatSubmittedText = ""
        chatBlockedUntil = 0
        ui { copy(chatStatus = "Waiting for your voice...", chatLines = emptyList(), chatLoading = false) }
    }

    /** Reopens the mic after a reply (or on entering chat). */
    private suspend fun restartMicrophone() {
        ui { copy(chatStatus = "Starting microphone...", sentence = "Starting microphone...") }
        suppressPartialResults = true
        resetTranscript()
        try {
            speech.unpauseRecognition(-1)
            delay(300)
            ui { copy(chatStatus = "Listening...", sentence = "Listening...") }
        } catch (t: Throwable) {
            ui { copy(chatStatus = "Microphone did not reopen. Please tap Back and try again.") }
        } finally {
            scope.launch { delay(400); suppressPartialResults = false }
        }
    }

    private suspend fun processChatTurn(userText: String) {
        val local = _state.value.mode == AppMode.LocalAIChat
        val now = System.currentTimeMillis()
        if (!local) {
            if (now < chatBlockedUntil) {
                ui { copy(chatStatus = "Gemini is cooling down after rate limiting. Please try again in ${(chatBlockedUntil - now + 999) / 1000}s.") }
                return
            }
            if (now - lastChatSubmitAt < DemoConfig.AI_CHAT_MIN_REQUEST_GAP_MS) {
                ui { copy(chatStatus = "Waiting before the next Gemini request.") }
                return
            }
        }
        if (userText == lastChatSubmittedText && now - lastChatSubmitAt < 15_000) {
            ui { copy(chatStatus = "Same transcript received again. Waiting for a new phrase.") }
            return
        }

        chatInFlight = true
        lastChatSubmitAt = now
        lastChatSubmittedText = userText
        lastProcessed = userText
        val label = if (local) "Local AI" else "Gemini"
        ui {
            copy(
                chatLines = chatLines + ChatLine(true, userText) + ChatLine(false, ""),
                chatLoading = true,
                chatStatus = if (local) "Generating local AI reply..." else "Sending transcript to Gemini...",
            )
        }
        try {
            runCatching { speech.pauseRecognition() }
            if (local && !localLlm.isReady()) {
                // Don't hold the turn (and the mic) while a multi-GB model downloads.
                val status = _state.value.localModelStatus
                val name = localLlm.selectedModel.displayName
                val progress = Regex("^Downloading (\\d+)%").find(status)?.groupValues?.get(1)
                val spoken = when {
                    status.startsWith("Failed") ->
                        "The on-device model, $name, failed to load, so I am trying again. Please ask me again in a moment."
                    progress != null ->
                        "The on-device model, $name, is still downloading. It is $progress percent done. Please ask me again when it is ready."
                    else -> "The on-device model, $name, is still loading. Please ask me again in a moment."
                }
                warmUpLocalModel() // restarts it if an earlier attempt failed; the download resumes
                ui { copy(chatLines = chatLines.dropLast(1) + ChatLine(false, spoken), sentence = "$label: $spoken", chatStatus = "Speaking demo status update...") }
                speakText(SpokenText.normalize(spoken))
            } else if (local) {
                // Speak each sentence as soon as it's complete, while the rest generates.
                val sentences = Channel<String>(Channel.UNLIMITED)
                val speaker = scope.launch {
                    for (sentence in sentences) {
                        ui { copy(sentence = "$label: $sentence", chatStatus = "Speaking $label reply...") }
                        speakText(SpokenText.normalize(sentence))
                    }
                }
                var pending = ""
                val reply = localLlm.generate(localHistory, userText) { delta ->
                    scope.launch {
                        pending += delta
                        val (completed, rest) = SpokenText.splitCompletedSentences(pending)
                        pending = rest
                        completed.forEach { sentences.trySend(it) }
                        ui { copy(chatLines = chatLines.dropLast(1) + ChatLine(false, (chatLines.lastOrNull()?.text ?: "") + delta), chatStatus = "Streaming local AI reply...") }
                    }
                }
                delay(50) // let the last delta callbacks land
                SpokenText.normalize(pending).takeIf { it.isNotEmpty() }?.let { sentences.trySend(it) }
                sentences.close()
                speaker.join()
                localHistory += LocalLlm.Turn(true, userText)
                localHistory += LocalLlm.Turn(false, reply)
                ui { copy(chatLines = chatLines.dropLast(1) + ChatLine(false, reply)) }
            } else {
                val reply = gemini.reply(geminiHistory + GeminiClient.Turn(true, userText))
                geminiHistory += GeminiClient.Turn(true, userText)
                geminiHistory += GeminiClient.Turn(false, reply)
                ui { copy(chatLines = chatLines.dropLast(1) + ChatLine(false, reply), sentence = "$label: $reply", chatStatus = "Speaking $label reply...") }
                speakText(SpokenText.normalize(reply))
            }
        } catch (t: Throwable) {
            val message = t.message ?: t.toString()
            Log.w(tag, "$label error: $message")
            val spoken = if (local) "Local AI chat error: $message" else GeminiClient.friendlyError(message)
            if (!local && GeminiClient.isRateLimit(message)) chatBlockedUntil = System.currentTimeMillis() + DemoConfig.AI_CHAT_RATE_LIMIT_BACKOFF_MS
            ui { copy(chatLines = chatLines.dropLast(1) + ChatLine(false, spoken), sentence = "$label: $spoken", chatStatus = "Speaking demo status update...") }
            runCatching { speakText(SpokenText.normalize(spoken)) }
        } finally {
            chatInFlight = false
            lastProcessed = ""
            ui { copy(chatLoading = false) }
            if (_state.value.screen == Screen.Chat) restartMicrophone()
        }
    }

    private var warmUpJob: Job? = null

    private fun warmUpLocalModel() {
        if (warmUpJob?.isActive == true) return
        ui { copy(localModelStatus = "Loading...", localModelName = localLlm.selectedModel.displayName) }
        warmUpJob = scope.launch {
            try {
                val model = localLlm.ensureLoaded { fraction ->
                    scope.launch { ui { copy(localModelStatus = "Downloading ${(fraction * 100).toInt()}%") } }
                }
                localLlm.prefill(localHistory, "")
                localLlm.deleteUnusedModelFiles()
                ui { copy(localModelName = model.displayName, localModelStatus = "Ready") }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(tag, "Local model warm-up failed", t)
                ui { copy(localModelStatus = "Failed: ${t.message}") }
            }
        }
    }

    fun localModels() = LocalLlm.MODELS
    fun isLocalModelDownloaded(model: LocalModel) = localLlm.isDownloaded(model)
    fun selectedLocalModel() = localLlm.selectedModel

    fun selectLocalModel(model: LocalModel) {
        if (chatInFlight) return
        localLlm.selectedModel = model
        warmUpJob?.cancel()
        warmUpLocalModel()
    }

    // ================================================================================
    // UI choices
    // ================================================================================

    fun chooseVoice(voice: Voice) { voiceChoice?.complete(voice) }

    fun chooseSpeakerVerification(choice: SvChoice) { svChoice?.complete(choice) }

    fun continueAfterVerification() { svContinue?.complete(Unit) }

    fun chooseMode(mode: AppMode) {
        val pending = modeChoice
        if (pending != null) pending.complete(mode)
        else scope.launch { enterMode(mode, withIntro = false) } // from the Back menu
    }

    /** Releases the mic and engines. Runs on its own scope: [scope] may already be cancelled. */
    fun shutdown() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { liveVerification?.stop() }
            runCatching { wakeword.stop() }
            runCatching { speech.destroyAll() }
        }
    }

    private companion object {
        const val SPEECH_INIT_TIMEOUT_MS = 120_000L
    }
}
