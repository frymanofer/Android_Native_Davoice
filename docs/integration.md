# Integrating DaVoice into your own Android app

This page has the minimum code for each feature. The example app wraps each one in a controller under
`app/src/main/java/io/davoice/example/voice/` that you can copy as is.

## 1. Gradle

Copy `libs/` from this repo into your project root and declare it as a Maven repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("$rootDir/libs") }
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }          // tts and llm ship arm64-v8a only
    }
    androidResources { noCompress += listOf("dm", "onnx") }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"    // several AARs ship the C++ runtime
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.davoice:keyworddetection:1.0.0")   // wake word, speaker verification, ONNX Runtime
    implementation("com.davoice:tts:1.0.0")                // TTS + STT (needs keyworddetection for ONNX Runtime)
    implementation("com.davoice:llm:1.0.0")                // local LLM (llama.cpp)

    // The DaVoice .pom files declare no dependencies. Add these yourself:
    implementation("ai.picovoice:android-voice-processor:1.0.2")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
```

> **Using TTS/STT without the wake word?** Still add `keyworddetection`. The `tts` AAR is compiled against ONNX
> Runtime but does not bundle it; at runtime it uses the copy inside `keyworddetection`.

Permissions are merged from the AARs (`RECORD_AUDIO`, `INTERNET`, the foreground-service types). You still have to
request `RECORD_AUDIO` at runtime. On Android 13 and newer, also request `POST_NOTIFICATIONS` so the microphone
service's notification can show.

## 2. License

```kotlin
val key = "..."  // the demo key is in DemoConfig.kt; get yours from info@davoice.io
keyWordsDetection.setLicenseKey(key)              // wake word + speaker verification
com.davoice.tts.LicenseManager.setLicenseKey(key) // STT + TTS (process-wide)
tts.setLicenseKey(key)
localLlmEngine.setLicense(key)                    // local LLM
```

## 3. Wake word

```kotlin
import com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection

// Models are read from app assets by file name.
val kwd = KeyWordsDetection(
    context,
    arrayOf("hey_coach_model_28_22012026b.dm"),
    floatArrayOf(0.999f),   // threshold
    intArrayOf(3),          // buffer count
    longArrayOf(1000L),     // min ms between callbacks
)
kwd.initialize { detected, modelName -> if (detected) onWakeWord(modelName) }
kwd.setLicenseKey(key)
kwd.startListening(0.999f)

// Later:
kwd.pauseDetection(false)   // false = keep the mic open (fast resume)
kwd.unPauseDetection()
kwd.recordingWavArray       // WAV(s) of the audio that triggered the detection
kwd.stopListening()
```

## 4. Speaker verification

Speaker verification works in two steps: enroll once, then pass the enrollment to the engines.

```kotlin
import com.davoice.keywordsdetection.keywordslibrary.SpeakerVerification

val config = SpeakerVerification.SpeakerVerificationConfig().apply {
    modelPath = "/path/to/sv.dm"        // a real file: copy it out of assets first (AssetInstaller.kt)
    decisionThreshold = 0.34f
}
val mic = SpeakerVerification.SpeakerVerificationMicController(context, config)
mic.delegate = object : SpeakerVerification.SpeakerVerificationNativeDelegate {
    override fun svOnboardingProgress(info: MutableMap<String, Any>?) { /* info["collected"], info["target"] */ }
    override fun svOnboardingDone(info: MutableMap<String, Any>?) { save(info?.get("enrollmentJson") as String) }
    override fun svVerifyResult(info: MutableMap<String, Any>?) { /* info["scoreBest"], info["isMatch"] */ }
    override fun svError(info: MutableMap<String, Any>?) { }
}
mic.beginOnboarding("user", 5, true)
repeat(5) { mic.getNextEmbeddingFromMic() }   // one call per sample; see SpeakerVerificationController.enroll
```

Then use the enrollment like this:

```kotlin
kwd.startListening(0.999f, "/path/to/sv_enrollment.json")   // wake word fires only for that speaker
stt.startSpeech("en-US", enrollmentJsonText, null)          // STT transcribes only that speaker (JSON text, not a path)
```

## 5. Speech to text + text to speech

```kotlin
import com.davoice.stt.STT
import com.davoice.stt.STTDelegate
import com.davoice.tts.DaVoiceTTSInterface

val stt = STT(context, /* continuous = */ true)
stt.delegate = object : STTDelegate {
    override fun onSpeechPartialResults(list: List<String>) { /* live text */ }
    override fun onSpeechResults(best: String, all: List<String>) { }
    override fun onSpeechStart() {}
    override fun onSpeechEnd() {}
    override fun onSpeechError(code: Int, message: String) {}
    override fun onSpeechRecognized() {}
    override fun onSpeechVolumeChanged(value: Float) {}
    override fun onNewSpeechWAV(filePath: String) {}
}
stt.startSpeech("en-US", null, null)

val tts = DaVoiceTTSInterface(context)
tts.setOnFinishedSpeakingListenerJava { /* playback finished */ }
tts.initTTS(DaVoiceTTSInterface.Config("/path/to/model_ex2_rich.dm", "en-US", "phonemes_dir"))
tts.changeVoice("Rich")               // multi-voice (ttsv2) models only
tts.speak("Hello there!", 0, 1f / 0.95f)  // speaker id, length scale (1 / speed)
```

TTS and STT coordinate on their own: the recognizer ignores the app's own voice while TTS plays. Around a reply the
demo also pauses recognition explicitly:

```kotlin
stt.pauseSpeechRecognitionLite()          // bounded, ~500 ms
tts.speak(reply, 0, lengthScale)          // wait for the finished listener
stt.unPauseSpeechRecognitionLite(-1)      // -1 releases every outstanding pause
```

Wake word → STT handoff, as in `VoiceDemo.onWakeWord`: pause the wake word (mic open), wait about 1 s, then unpause
STT.

## 6. Audio routing (optional)

The wake word, STT and TTS share one JSON routing config. One of the things it controls is whether the wake word stays
armed while TTS plays, and with which stricter threshold. Set it before creating the wake word:

```kotlin
WakewordAudioRoutingConfig.setFromJson(json)   // see DemoConfig.AUDIO_ROUTING_CONFIG_JSON
```

## 7. Local LLM

```kotlin
import com.davoice.llm.LocalLLMEngine
import com.davoice.llm.LocalLLMLoadOptions

val llm = LocalLLMEngine()
llm.setLicense(key)
llm.loadModel("/path/to/model.gguf", LocalLLMLoadOptions(contextSize = 8192, useMmap = true))
llm.generateStream(prompt, { token, done -> /* append token; done = finished */ }, { error -> })
llm.cancel()
```

The prompt is the model's chat template, filled in by you. `LocalLlm.kt` builds it for ChatML (Qwen) and Gemma, cuts
the stream at the end-of-turn marker, and calls `prefill()` while the user is still talking so the reply starts faster.
