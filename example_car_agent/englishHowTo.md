# DaVoice: AI Car Voice Agent: how-to (native Android, Kotlin)

> **For** developers who run this example, change it, or add its voice features to their own Android app. Paths are relative to
> `example_car_agent/`. Gradle setup and every call: [`../docs/integration.md`](../docs/integration.md) and
> [`../docs/api.md`](../docs/api.md). Last update: 2026-11-22. The React Native and Flutter twins of this app have the same guide.

The app is a car voice assistant that runs entirely on the phone: **wake word → (optional) speaker verification → speech to text
(STT) → the DaVoice Automotive Agent (a 0.6B model on the phone) → an action on a simulated car → text to speech (TTS)**. Nothing
you say leaves the phone. The car is simulated.

- **Part A** — use the app and change it (more in `README.md`).
- **Part B** — add each capability to **your own** Android app, with code you can copy.

---

# Part A — Use and change this app

## A1. Run it

```bash
./gradlew :example_car_agent:installFullDebug          # or installLiteDebug (smaller, no ACL)
./gradlew :example_car_agent:testFullDebugUnitTest     # unit tests, including the conversation flows shared with the other apps
```

Language → voice → speaker verification (record, use the saved one, or skip) → say the wake word ("Hey Coach" in this build) →
the car screen. With the default settings every request starts with the wake word; the line at the bottom says what to do
("Say “Hey Coach” before each request" / "Listening… say your request"). Debug builds also show a text box.

## A2. Settings (the gear)

The same settings as the React Native and Flutter apps: take turns or interrupt, wake word or just talk, end of speech, the listen
window after the wake word, **follow-up only after a question**, pre-fetch, wake word as a stop word (default off), and the model's
Local confidence. Changes are a draft until **Save** (`ui/Settings.kt`, `ui/CarScreen.kt`).

## A3. Common changes

| To change | Where |
|---|---|
| The wake word | `WAKE_WORDS` and `WAKE_PHRASE` in `VoiceConfig.kt`; the model in `src/main/assets/` (next to `layer1.dm`, which the engine needs). Add its sound-alikes to `soundsLike` in `conversation/ConversationController.kt`. |
| Conversation defaults | `defaultConversationSettings` in `conversation/ConversationSettings.kt` |
| Words STT should prefer | `src/main/assets/words_flattened.txt` (one phrase per line, ending with a comma) |
| Speaker verification | `SV_*` in `VoiceConfig.kt`; sentences in `speaker/EnrollmentSentences.kt` |
| What the car does and says | `car/CarTools.kt` (keep `assets/car/carToolDefinitions.h3d.json` as is: the model was trained with it) |
| The license | `VoiceConfig.kt` (a DaVoice demo key; ask info@davoice.io for yours) |

## A4. Where the code is

| Part | Files |
|---|---|
| The flow, the turn | `CarAgentApp.kt` |
| Wake word, speech, speaker verification (copy these) | `voice/WakewordController.kt`, `voice/SpeechController.kt`, `voice/SpeakerVerificationController.kt`, `voice/AssetInstaller.kt` |
| **Conversation modes** (who listens when) | `conversation/` (plain Kotlin) |
| Screens | `ui/` |
| The agent, the car | `agent/`, `car/` |

---

# Part B — Add these capabilities to your own Android app

## B0. Setup (once)

1. **Gradle, AARs, permissions, license:** follow [`../docs/integration.md`](../docs/integration.md) sections 1–2. Ask for
   `RECORD_AUDIO` (and on Android 13+ `POST_NOTIFICATIONS`) at run time before starting anything.
2. **Model files** in `src/main/assets/`: the wake-word model (e.g. `hey_davoice_model_28_06042026.dm`) **and `layer1.dm`**, `sv.dm`
   for speaker verification, your TTS voice model, `words_flattened.txt` (hint words). The engines need real file paths for some
   of them: `voice/AssetInstaller.kt` copies assets to app storage once (`assets.path("sv.dm")`).
3. **Copy** `voice/` (the controllers have the same API as the React Native packages) and, for B5, `conversation/`.

### The rules that make it work

1. **Pauses are gentle; the microphone keeps running.** Use only `speech.pauseSpeechRecognition()` /
   `speech.unPauseSpeechRecognition(-1, preFetchMs)` and `wakeword.pauseDetection(false)` / `wakeword.unPauseDetection()`. Never
   `speech.stop()` / `speech.cancel()` to end a turn: the recognizer restarts by itself and keeps transcribing.
2. **One listener decides at a time.** STT listening → wake word paused (unless it is the stop word, B5). Wake word listening → STT
   paused.
3. **`pauseDetection(true)` only to hand the microphone to another recorder** (speaker-verification enrollment, voice-clone
   recording); `unPauseDetection()` afterwards.
4. **Start-up order:** wake word created, started → paused → `speech.initAll` → STT paused → wake word unpaused.
5. **Pre-fetch only after the wake word** (2000 ms), then **strip the wake phrase** (B5). After a reply, a tap or a stop word:
   pre-fetch 0.
6. **Ignore speech that is not the user's turn**: while waiting for the wake word, thinking, or speaking (unless interrupting).
7. **Speaker verification is switched on and off live** (`addSpeaker` / `setSpeakers(emptyList())`), no restart.

## B1. Wake word

```kotlin
val wakeword = WakewordController(context)
wakeword.create(listOf(VoiceConfig.WakeWordModel("hey_davoice_model_28_06042026.dm", threshold = 0.999f, bufferCount = 3,
    msBetweenCallbacks = 1000L)))
wakeword.onDetection = { model, speakerId -> onWakeWord(model) }       // on a detector thread
check(wakeword.setKeywordDetectionLicense(LICENSE)) { "wake word license" }
wakeword.startKeywordDetection(0.999f)
```

- Pause / resume: `wakeword.pauseDetection(false)` / `wakeword.unPauseDetection()` (suspend functions).
- Sensitivity: threshold (higher = fewer false triggers) and buffer count; this app uses 0.999 / 3.
- Shut down: `wakeword.stopKeywordDetection(); wakeword.destroyInstance()`.
- Background listening: `wakeword.startForegroundService()` while the app is in front (and `stopForegroundService()` when done).

## B2. Speaker-verification onboarding

`voice/SpeakerVerificationController.kt` does the whole recording: `enroll(samples) { collected, target -> }` returns the
enrollment JSON (each sample may take up to 60 s; it always closes its recorder), and `saveEnrollment(json)` writes it and returns
the path. Around it, STT paused and the wake word hands over the microphone:

```kotlin
val sv = SpeakerVerificationController(context, AssetInstaller(context))
speech.pauseSpeechRecognition()
wakeword.pauseDetection(true)                    // release the microphone for the recorder
val enrollmentPath = try {
    sv.saveEnrollment(sv.enroll(5) { collected, _ -> showProgress(collected) })   // show sentence `collected` meanwhile
} catch (t: Throwable) {
    null                                         // show the choice again with a notice; never "restart the app"
} finally {
    wakeword.unPauseDetection()
}
```

Next start: `sv.loadSavedEnrollment()` returns the saved JSON (offer "Use my saved voice signature").

## B3. STT and TTS

```kotlin
val speech = SpeechController(context)
check(speech.setLicense(LICENSE)) { "speech license" }
wakeword.pauseDetection(false)
speech.initAll(locale = "en-US", model = assets.path("models/<your voice model>.dm"))
speech.pauseSpeechRecognition()                  // STT starts paused; the wake word decides when it opens
wakeword.unPauseDetection()

scope.launch {
    speech.events.collect { e ->
        when (e) {
            is SpeechController.Event.Partial -> onHeard(e.text, e.speakerIds, e.speakerId)
            is SpeechController.Event.Final -> onHeard(e.text, e.speakerIds, e.speakerId)
            SpeechController.Event.FinishedSpeaking -> onReplySpoken()     // everything queued was spoken
            else -> Unit
        }
    }
}
```

- **Listen:** `speech.unPauseSpeechRecognition(-1, preFetchMs)`; when the user stopped talking: `speech.pauseSpeechRecognition()`.
- **End of speech** is yours: restart a timer on every result; when it runs out (2 s) the request is complete.
- **Results come in segments:** merge them (`mergeTranscript` in `flow/Transcript.kt`).
- **Speak:** `speech.changeVoice("Rich")` (one of `speech.getAllVoices()`), `speech.speak(text, 0, 1f)` (sentences queue up),
  `speech.stopSpeaking()`.

## B4. Speaker verification with STT and the wake word

```kotlin
wakeword.addSpeaker("owner", enrollmentPath)    // the wake word answers only this voice
speech.addSpeaker("owner", enrollmentPath)      // STT results carry speakerIds / speakerId
// off:
wakeword.setSpeakers(emptyList())
speech.setSpeakers(emptyList())
```

Collect `speakerIds` / `speakerId` per request and check them before a sensitive action (`speaker/CurrentSpeaker.kt`).

## B5. A complete voice-to-voice flow

**Copy** `conversation/` (`ConversationController.kt`, `ConversationSettings.kt`): plain Kotlin, the same logic and test cases as
the React Native and Flutter apps (`src/test/resources/fixtures/conversation_*.json`).

| Phase | STT | Wake word | Leaves the phase when |
|---|---|---|---|
| **ASLEEP** (wake-word mode) | paused | listening | the wake word → LISTENING (pre-fetch 2 s) |
| **LISTENING** | listening | paused | silence after speech → BUSY; nothing said → ASLEEP |
| **BUSY** | paused (interrupt mode: open under the voice) | paused (stop word: listening while it speaks) | the reply was spoken → a question: LISTENING; otherwise ASLEEP |

```kotlin
const val WAKE_PHRASE = "Hey DaVoice"
var settings = defaultConversationSettings
@Volatile var transcript = ""
@Volatile var spoken = ""
@Volatile var fromWakeWord = false
val timers = Executors.newSingleThreadScheduledExecutor()
var silence: ScheduledFuture<*>? = null

val conversation = ConversationController(
    ConversationCommands(
        openMic = { preFetchMs, _ -> transcript = ""; runBlocking { speech.unPauseSpeechRecognition(-1, preFetchMs) } },
        closeMic = { _ -> silence?.cancel(false); transcript = ""; runBlocking { speech.pauseSpeechRecognition() } },  // gentle
        armWakeWord = { _ -> runBlocking { wakeword.unPauseDetection() } },
        disarmWakeWord = { _ -> runBlocking { wakeword.pauseDetection(false) } },
        stopReply = { _ -> cancelYourAgent(); runBlocking { speech.stopSpeaking() } },
        onPhase = { phase -> showLine(conversationHint(phase, WAKE_PHRASE, settings.wakeWord)) },
        timer = { ms, fn -> val f = timers.schedule(fn, ms, TimeUnit.MILLISECONDS); Cancellable { f.cancel(false) } },
    ),
    settings,
)

suspend fun startConversation() {               // the conversation screen opens
    if (!settings.wakeWord) speech.unPauseSpeechRecognition(-1, 0)
    conversation.enter()                         // wake-word mode: ASLEEP (the wake word that opened the screen is not a request)
}

fun onWakeWord(model: String) { fromWakeWord = true; conversation.wakeWord() }

fun onHeard(text: String, speakerIds: List<String>, speakerId: String?) {
    val t = text.trim()
    if (t.isEmpty()) return
    if (conversation.phase == ConversationPhase.ASLEEP || conversation.phase == ConversationPhase.OFF) return   // rule 6
    if (conversation.phase == ConversationPhase.BUSY && !conversation.heardWhileBusy(t, spoken)) return
    conversation.userSpeech()
    transcript = mergeTranscript(transcript, t)
    silence?.cancel(false)
    silence = timers.schedule({ scope.launch { endOfRequest() } }, settings.endOfSpeechMs.toLong(), TimeUnit.MILLISECONDS)
}

suspend fun endOfRequest() {
    var text = transcript.trim()
    if (fromWakeWord && settings.wakeWord) {
        text = dropWakePhrase(text, WAKE_PHRASE)
        if (text.isEmpty()) return               // only the wake phrase so far: wait for the request
    }
    fromWakeWord = false
    transcript = ""
    speech.pauseSpeechRecognition()
    conversation.turnStarted()
    spoken = ""
    val reply = yourAgent(text)                  // your model, server or rules
    conversation.replySpeaking()
    spoken = reply
    speech.speak(reply)
}

fun onReplySpoken() = conversation.replyFinished(spoken)   // a question → LISTENING; otherwise → ASLEEP

fun applySettings(next: ConversationSettings) { settings = next; conversation.update(next) }   // Save in the settings
```

- **The wake phrase** (`dropWakePhrase`): "Hey DaVoice, open the window" → "open the window"; also "Hey the voice …", "The voice,
  …", "DaVoice, …", the phrase twice; only the phrase → `""` (wait). For your wake phrase, add its sound-alikes to `soundsLike`.
  Strip it also from text you show while the user talks.
- **After the reply:** wake-word mode reopens STT only after a question (`isQuestion`: ends with "?"); just talk after every reply.
- **Stop word** (`wakeWordMode.stopWord`): the wake word listens while the agent speaks and stops it. **Interrupt**: STT stays open
  under the voice and words the agent is not saying stop it (`resolveBargeIn`); without echo cancellation it is experimental on
  Android.
- **Leaving the screen:** `conversation.leave()`. **A tap on the line:** `conversation.tapToTalk()`.

### Checklist

- [ ] Start-up order as in B3; only gentle pauses.
- [ ] Wake-word mode starts asleep; the screen says "Say “…” before each request".
- [ ] Pre-fetch only after the wake word; the wake phrase is stripped.
- [ ] Nothing heard while asleep or busy reaches your agent (unless interrupting).
- [ ] After a reply: a question → listening; otherwise → the wake word.
- [ ] Enrollment hands over the microphone and always closes its recorder; speaker verification is switched on live.

### Troubleshooting

| Symptom | Cause |
|---|---|
| It answers without the wake word | STT opened when the screen opened, a result while asleep was not ignored, or `stop()`/`cancel()` was used instead of a pause. |
| The request starts with the wake phrase | The turn was not marked as opened by the wake word, or the phrase's sound-alikes are missing. |
| The agent answers its own voice | Interrupting without echo cancellation, or pre-fetch > 0 after a reply. |
| Voice signature: no sample | The wake word still holds the microphone (`pauseDetection(true)`), or a noisy room. |
| The wake word never fires | `layer1.dm` missing from the assets, or the license not set before `startKeywordDetection`. |
