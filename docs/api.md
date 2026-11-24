# API reference: React Native ↔ native Android

Every call in the React Native packages has a native Kotlin equivalent with the same name and the same arguments. The
example app wraps them in three controllers under `app/src/main/java/io/davoice/example/voice/` that you can copy as
is:

| React Native | Native controller | Library classes it calls |
|---|---|---|
| `Speech` from `react-native-davoice/speech` (also published as `react-native-davoice-tts`) | `SpeechController` | `com.davoice.stt.STT`, `com.davoice.tts.DaVoiceTTSInterface`, `com.davoice.tts.LicenseManager` |
| `KeyWordRNBridgeInstance` from `react-native-wakeword` | `WakewordController` | `com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection`, `WakewordAudioRoutingConfig` |
| Speaker verification from `react-native-wakeword` | `SpeakerVerificationController` | `com.davoice.keywordsdetection.keywordslibrary.SpeakerVerification` |

The React Native promises are `suspend` functions here. Each one is safe to call from the main thread. React Native
callbacks and events arrive as a Kotlin `Flow` or a callback property.

Both wake-word builds (`keyworddetection` for the **full** flavor and `keyworddetection-lowfp` for **lite**) have the
same API.

## Speech to text + text to speech: `SpeechController`

```kotlin
val speech = SpeechController(context)
speech.setLicense(key)
speech.initAll(locale = "en-US", model = "/path/to/model.dm")
speech.speak("Hello there!")                 // returns when everything queued so far has been spoken
```

### License and lifecycle

| React Native `Speech` | `SpeechController` | Notes |
|---|---|---|
| `setLicense(key)` | `setLicense(key)` | One process-wide license for STT and TTS |
| `isLicenseValid(key)` | `isLicenseValid(key)` | |
| `initAll({ locale, model, onboardingJsonPath?, speakerId?, speakers? })` | `initAll(locale, model, onboardingJsonPath, speakerId, speakers)` | `model` is a file path (copy it out of the APK first, see `AssetInstaller`) |
| `destroyAll()` | `destroyAll()` | |
| `initWithoutModel()` | `initWithoutModel(locale, …)` | STT only |
| `destroyWithoutModel()` / `destroyWihtouModel()` | `destroyWithoutModel()` | |
| `initTTS(model)` | `initTTS(model)` | TTS only; returns `false` if the model was refused |
| `initAllRemoteSTT(model)` | `initAllRemoteSTT(model)` | Microphone + VAD save each utterance as a WAV (`Event.NewSpeechWAV`) instead of transcribing |
| `initAllRemoteSTTAndTTS()` | `initAllRemoteSTTAndTTS()` | Same, plus TTS in playback-only mode (`playWav`, `playPCM`, `playBuffer`) |

### Speech to text

| React Native `Speech` | `SpeechController` | Notes |
|---|---|---|
| `start(locale, options?)` | `start(locale, onboardingJsonPath, speakerId, speakers)` | `speakers` replaces the speaker set, `onboardingJsonPath` adds one, neither keeps the current set |
| `startWithSVOnboardingJson(locale, jsonOrPath, speakerId?)` | `startWithSVOnboardingJson(locale, jsonOrPath, speakerId)` | STT transcribes only enrolled speakers |
| `startWithSVSpeakers(locale, speakers)` | `startWithSVSpeakers(locale, speakers)` | `speakers: List<SpeakerItem>` (`SpeakerItem(id, json)`, `json` = JSON or a path) |
| `addSpeaker(id, jsonOrPath)` | `addSpeaker(id, jsonOrPath)` | Returns the enrolled ids; works while listening |
| `setSpeakers(speakers)` | `setSpeakers(speakers)` | An empty list turns speaker verification off |
| `removeSpeaker(id)` | `removeSpeaker(id)` | |
| `getSpeakerIds()` | `getSpeakerIds()` | |
| `pauseSpeechRecognition(timeoutMs = 500)` | `pauseSpeechRecognition(timeoutMs = 500)` | The microphone stays open |
| `unPauseSpeechRecognition(times, preFetchMs = 0, timeoutMs = 500)` | `unPauseSpeechRecognition(times, preFetchMs, timeoutMs)` | `times = -1` releases every pause. Returns `SpeechTransitionResult(ok, reason, elapsedMs)` |
| `pauseMicrophone()` / `unPauseMicrophone()` | `pauseMicrophone()` / `unPauseMicrophone()` | |
| `stop()` / `cancel()` | `stop()` / `cancel()` | |
| `isAvailable()` | `isAvailable()` | `Boolean` instead of `0 \| 1` |
| `isRecognizing()` | `isRecognizing()` | |
| `getOnDeviceLanguages()` | `getOnDeviceLanguages()` | `OnDeviceLanguages`, same fields. A language without an on-device model falls back to the cloud automatically and is listed in `cloudFallback` |

### Text to speech

| React Native `Speech` | `SpeechController` | Notes |
|---|---|---|
| `speak(text, speakerId = 0, speed = 1)` | `speak(text, speakerId = 0, speed = 1f)` | Queues at once and returns when the queue is empty. Call it for every sentence as soon as you have it: the next sentences are prepared while the current one plays |
| `stopSpeaking()` | `stopSpeaking()` | Stops now and drops what is queued; waiting `speak()` calls return |
| `getAllVoices()` | `getAllVoices()` | Voices in the loaded `.dm`; the first is the default |
| `changeVoice(name)` | `changeVoice(name)` | `0` on a match, `-1` otherwise |
| `getVoiceAbilities(name)` | `getVoiceAbilities(name)` | Style variants. Use them with tags in the text: `"Ok, [drill sergeant] give me 50 push-ups! [regular] Thanks."` |
| `createVoiceClone(inputAudio, outputPath, embedder, volume = 1)` | `createVoiceClone(inputAudio, outputPath, embedder, volume)` | Returns `VoiceCloneResult(path, voice, warnings)`. If `warnings` is not empty, ask the user to record again. `embedder` is the language's `embedder_<code>.dm` (a file path or an APK asset name) |
| `setExternalVoicesPath(path)` | `setExternalVoicesPath(path)` | Adds the `.emd` voices in that folder to `getAllVoices()`; `null` removes them |
| `playWav(path, markAsLast = true)` | `playWav(path, markAsLast)` | WAV or MP3. With `markAsLast` it returns when the queue is empty |
| `playPCM(Int16Array \| Float32Array, { sampleRate, channels, interleaved, markAsLast })` | `playPCM(ShortArray \| FloatArray, sampleRate, channels, interleaved, markAsLast)` | Audio from another TTS provider; mixed down to mono |
| `playBuffer({ base64, sampleRate, format, … })` | `playBuffer(bytes, PcmFormat.I16 \| F32, sampleRate, channels, interleaved, markAsLast)` | Raw little-endian bytes (no base64 needed) |

### Events

React Native's `onSpeech*` setters and `onFinishedSpeaking` are one stream:

```kotlin
scope.launch {
    speech.events.collect { e ->
        when (e) {
            is SpeechController.Event.Partial -> showLive(e.text)          // e.speakerIds / e.speakerId with speaker verification
            is SpeechController.Event.Final -> onSentence(e.text, e.speakerId)
            is SpeechController.Event.Error -> log(e.code, e.message)
            is SpeechController.Event.VolumeChanged -> meter(e.value)
            is SpeechController.Event.NewSpeechWAV -> upload(e.path)        // remote capture only
            SpeechController.Event.FinishedSpeaking -> { }                 // the speech queue is empty
            SpeechController.Event.Start, SpeechController.Event.Recognized, SpeechController.Event.End -> { }
        }
    }
}
```

| React Native | Event |
|---|---|
| `onSpeechStart`, `onSpeechRecognized`, `onSpeechEnd` | `Start`, `Recognized`, `End` |
| `onSpeechPartialResults({ value, speakerIds?, speakerId? })` | `Partial(text, all, speakerIds, speakerId)` |
| `onSpeechResults({ value, speakerIds?, speakerId? })` | `Final(text, all, speakerIds, speakerId)` |
| `onSpeechError({ error: { code, message } })` | `Error(code, message)` |
| `onSpeechVolumeChanged({ value })` | `VolumeChanged(value)` |
| `onNewSpeechWAV({ path })` | `NewSpeechWAV(path)` |
| `onFinishedSpeaking()` | `FinishedSpeaking` |

## Wake word: `WakewordController`

```kotlin
val wakeword = WakewordController(context)
wakeword.setAudioRoutingConfig(json)                       // optional, before create()
wakeword.create(listOf(WakeWordModel("hey_coach.dm", 0.999f, 3, 1000L)))
wakeword.setKeywordDetectionLicense(key)
wakeword.onDetection = { model, speakerId -> onWakeWord(model) }
wakeword.startKeywordDetection(0.999f)
```

| React Native | `WakewordController` | Notes |
|---|---|---|
| `createKeyWordRNBridgeInstance(id)` + `createInstanceMulti(models, thresholds, bufferCnts, msBetweenCallbacks)` | `create(List<WakeWordModel>)` | One detector runs several models. Models are read from app assets by file name |
| `createInstance(model, threshold, bufferCnt)` | `createInstance(model, threshold, bufferCnt)` | |
| `replaceKeywordDetectionModel(model, threshold, bufferCnt)` | `replaceKeywordDetectionModel(…)` | |
| `setKeywordDetectionLicense(key)` / `setKeywordLicense(key)` | `setKeywordDetectionLicense(key)` | |
| `startKeywordDetection(threshold, svJsonOrPath?)` | `startKeywordDetection(threshold, svJsonOrPath)` | With an enrollment the wake word fires only for that speaker. React Native's `setActive`, `duckOthers`, `mixWithOthers` and `defaultToSpeaker` arguments are iOS audio-session flags |
| `startKeywordDetectionWithSpeakerId(threshold, jsonOrPath, speakerId)` | same | |
| `startKeywordDetectionWithSpeakers(threshold, speakers)` | same | `List<SpeakerItem>` |
| `addSpeaker` / `setSpeakers` / `removeSpeaker` / `getSpeakerIds` | same | Live, while listening |
| `stopKeywordDetection()` | `stopKeywordDetection()` | |
| `pauseDetection(stopMic = false)` / `unPauseDetection()` | same | `stopMic = false` keeps the microphone open for a fast resume |
| `setTTSActive(active)` | `setTTSActive(active)` | |
| `destroyInstance()` | `destroyInstance()` | |
| `onKeywordDetectionEvent(cb)` (`{ phrase, speakerId? }`) | `onDetection = { modelName, speakerId -> }` | Called on a library thread. `getLastDetectedSpeakerId()` gives the same speaker |
| `setDetectionAudioRecording(enabled)` / `isDetectionAudioRecordingEnabled()` | same | Saving the audio that triggered each detection; on by default |
| `getRecordingWav()` / `getRecordingWavArray()` | same | |
| `getVoiceProps()` | `getVoiceProps()` | `VoiceProps(error, voiceProbability, lastTimeHumanVoiceHeard)` |
| `startVADDetection()` / `stopVADDetection()` | same | |
| `setVADParams(threshold, msWindow)` / `getVADParams()` | same | |
| `setWakewordAudioRoutingConfig(config)` | `setAudioRoutingConfig(json)` | The JSON string of the same config. One config drives wake word, STT and TTS, including `AEC.mode` (`off`, `duringTTS`, `always`) and `wakeWordDuringTTS` |
| `removeAllRNBridgeListeners()` | set `onDetection` / `onDetected` to `null` | |
| — | `startForegroundService()` / `stopForegroundService()` | Keeps the microphone alive in the background |

### Your own audio (no microphone)

For apps that already capture the microphone: pass the audio you have, and the wake word never opens the
microphone.

- **Audio format:** 16 kHz, mono, 16-bit PCM (`ShortArray`), in chunks of any size.
- **Native only:** React Native has no equivalent.

```kotlin
wakeword.create(listOf(WakeWordModel("hey_coach.dm", 0.9f, 2, 1000L)))
wakeword.setKeywordDetectionLicense(key)
wakeword.warmUpExternalAudio()                       // optional, once (~0.3 s)

// One-shot: one ~2 s clip that may contain the wake word
val r = wakeword.detectOnce(clip)                    // suspend; onDetection is not called
if (r.error == null && r.detected) onWakeWord(r.modelName)

// Streaming
wakeword.onDetection = { model, _ -> onWakeWord(model) }
wakeword.startKeywordDetectionExternal(useVadGate = true)
// in your audio loop (worker thread):  wakeword.pushAudio(buffer, samplesRead)
wakeword.stopKeywordDetectionExternal()
```

| React Native | `WakewordController` | Notes |
|---|---|---|
| — | `warmUpExternalAudio()` | One-time warm-up; otherwise the first call does it |
| — | `detectOnce(pcm, length)` → `OneShotResult` | `detected`, `modelName`, `maxScore`, `detectedAtMs`, `processingMs`, `error`. Each call is independent. About 110–180 ms per 2 s clip on a Galaxy S20 FE |
| — | `startKeywordDetectionExternal(useVadGate = true)` / `stopKeywordDetectionExternal()` | Detections arrive on `onDetection`, on the thread that calls `pushAudio`. Can't run while microphone detection runs |
| — | `pushAudio(pcm, length)` | Blocking: it runs the models on the calling thread, so call it from your audio worker thread |
| — | `setExternalVadGate(speechThreshold, hangoverMs, preRollMs)` / `isExternalVadGateActive()` | Defaults 0.4 / 2500 / 1000, as the microphone mode. With the gate on, the models run only during speech, and the second before the speech onset is processed first |

## Speaker verification: `SpeakerVerificationController`

| React Native | Native | Notes |
|---|---|---|
| `createSpeakerVerificationMicController(id)` + `create(configJson)` | `SpeakerVerification.SpeakerVerificationMicController(context, config)` | Same method names: `beginOnboarding`, `getNextEmbeddingFromMic`, `finalizeOnboardingNow`, `setEnrollmentJson`, `startVerifyFromMic`, `startEndlessVerifyFromMic`, `stop` |
| `onSpeakerVerificationOnboardingProgress` / `…OnboardingDone` / `…VerifyResult` / `…Error` | `SpeakerVerificationNativeDelegate`: `svOnboardingProgress`, `svOnboardingDone`, `svVerifyResult`, `svError` | Same keys in the info map (`collected`, `target`, `enrollmentJson`, `scoreBest`, `isMatch`) |
| `createSpeakerVerificationInstance(id)` + `create(model, enrollment)` + `verifyWavStreaming(wav)` | `SpeakerVerificationController.verifyWavStreaming(enrollmentJsonOrPath, wavPath)` | 16 kHz mono 16-bit WAV. Returns `WavVerifyResult(scoreBest, scoreMean, scoreWorst, isMatch, embeddingDim, usedSeconds)` |
| — | `SpeakerVerificationController.enroll(samples) { collected, target -> }` | The demo's enrollment loop. Returns the enrollment JSON |
| — | `SpeakerVerificationController.startLiveVerification(json) { score, isMatch -> }` | The demo's live score display |

The enrollment JSON goes to the wake word (`startKeywordDetection(threshold, json)` or `addSpeaker`) and to STT
(`initAll(…, onboardingJsonPath = json)` or `addSpeaker`).

## Not on Android

| React Native | Why |
|---|---|
| `Speech.setAudioRoutingConfig` / `clearAudioRoutingConfig` | iOS only: on Android, STT and TTS read the wake word's routing config (`WakewordController.setAudioRoutingConfig`) |
| `Speech.setAECEnabled` | Use `"AEC": { "mode": … }` in the routing config |
| `enableDucking`, `disableDucking`, `restartListeningAfterDucking`, `initAudioSessAndDuckManage`, `disableDuckingAndCleanup` | iOS audio session; they do nothing on Android |
| `startSilentVADDetection` / `stopSilentVADDetection`, `onVADDetectionEvent` | iOS only |
| `hasIOSMicPermissions`, `requestIOSMicPermissions`, `…SpeechRecognitionPermissions` | Request `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on Android 13+) with the normal Android runtime-permission API |
| `sttHealthSnapshot()`, `sttListenerDiag()` | iOS / JavaScript diagnostics |
| `useModel()` | React hook; `WakewordController` covers it |

## Only in the native library

These have no React Native equivalent yet and can be called on the library objects directly:

- `STT`: `setEndOfUtteranceSilenceMs(complete, possiblyComplete)`, `preferOffline`, `segmentResultsEnabled`.
- `KeyWordsDetection`, wake word on your own audio:
  - `detectOnce`, `startExternalStreaming` / `pushAudio` / `stopExternalStreaming`, `warmUpExternalAudio`,
    `setExternalVadGate`, `isExternalVadGateActive`. `WakewordController` wraps these (see above).
  - `setExternalOneShotTailPadMs(ms)` (silence appended after a one-shot clip, default 1000 ms), which is
    called on the library object directly.
- `DaVoiceTTSInterface`: `setNumberFormatStyle(…)`, `setUseEuropeanNumberFormat(…)`.
