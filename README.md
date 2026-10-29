# Android Native On-Device Voice AI by DaVoice

[License](LICENSE) · [Model and Voice Asset Notice](MODEL_NOTICES.md)

### Wake word, speaker verification, speech to text, text to speech and a local LLM. Native Kotlin, no React Native.

By [DaVoice.io](https://davoice.io)

[![Twitter URL](https://img.shields.io/twitter/url?style=social&url=https%3A%2F%2Ftwitter.com%2FDaVoiceAI)](https://twitter.com/DaVoiceAI)

This is a complete **native Android** example app for the DaVoice voice libraries. It is the Kotlin twin of the
[React Native example](https://github.com/frymanofer/ReactNative_DaVoice/tree/main/example). The flow, models and
settings are the same, but the app calls the native AARs directly.

Everything below runs **on the device**:

- <a href="https://davoice.io/wake-word" target="_blank" rel="noopener noreferrer">Wake word detection</a> (always listening, low battery)
- <a href="https://davoice.io/speaker-recognition" target="_blank" rel="noopener noreferrer">Speaker verification / speaker identification</a>. The wake word and STT can be locked to one enrolled speaker.
- Real-time speech to text (STT)
- Text to speech (TTS) with three cloned voices
- A local LLM (llama.cpp, GGUF models downloaded on first use)
- Coordinated pause and resume between wake word, STT and TTS, so the app never hears itself

The demo also has an optional cloud chat mode with Google Gemini. It shows the same on-device voice pipeline driving a
remote LLM.

More questions? Contact us at info@DaVoice.io

## What the demo shows

1. **Intro.** The selected voice introduces the demo. Tap *Skip Narration* or say the wake word to skip ahead.
2. **Choose a voice:** Rich, Ariana or Hanna.
3. **Speaker verification** (optional):
   - create a new speaker signature (5 short voice samples);
   - use a saved signature;
   - or skip this step.

   Live verification shows a match score while you talk. After that, the wake word and STT respond only to the
   enrolled speaker.
4. **Say a wake word: "Hey Coach" or "Hey Lookdeep".**
5. **Choose a mode:**

   | Mode | What happens |
   |---|---|
   | Local AI Chat (on device) | You talk and a local LLM answers out loud. It starts speaking at the first finished sentence. |
   | AI Chat (Gemini) | The same, with Google Gemini as the LLM (needs an API key, see below). |
   | Type to TTS | Type text and hear it in the selected voice. |
   | Speech to Text only | Live transcription. Each sentence is shown when you pause. |
   | Speech to Text + Text to Speech | The selected voice repeats what you said. |

## Requirements

- An **arm64-v8a** Android device or emulator (an Apple-silicon Mac emulator works). Android 10 (API 29) or newer.
- Android Studio (Ladybug or newer) or JDK 17 and the Android SDK.
- [Git LFS](https://git-lfs.com). The voice models are stored with LFS.
- About 500 MB free on the device. The APK carries the voice models (see [Models](#models)). The local LLM downloads
  1 to 4.5 GB the first time you open Local AI Chat.

## Build and run

```bash
git lfs install
git clone https://github.com/frymanofer/Android_Native_Davoice.git
cd Android_Native_Davoice
git lfs pull

cp local.properties.example local.properties   # set sdk.dir (Android Studio does this for you)
./gradlew :app:installFullDebug                 # or :app:installLiteDebug (see Footprint)
```

Or open the folder in Android Studio, pick the **full** or **lite** build variant, and press Run.

### Footprint: full vs lite

The app has two product flavors that differ only in the wake-word engine:

| Flavor | Wake-word engine | Size | Notes |
|---|---|---|---|
| **full** | ACL (Arm Compute Library), hardware-accelerated | larger | Lowest CPU/battery per inference. |
| **lite** | No ACL — CPU/XNNPACK only | **~24 MB smaller** native libs | Slightly more CPU per inference; installs as a separate app (`…​.lite`). |

```bash
./gradlew :app:assembleFullDebug     # full (ACL) APK
./gradlew :app:assembleLiteDebug     # lite (low-footprint) APK
./gradlew :app:installFullDebug      # or installLiteDebug
```

The flavors pull different wake-word AARs: `full` → `com.davoice:keyworddetection:1.0.0` (ACL), `lite` →
`com.davoice:keyworddetection-lowfp:1.0.1` (no ACL). Both are in `libs/`. The ACL choice is a build-time toggle in
the library itself — the low-footprint AAR never calls the ACL execution provider. TTS and the local LLM are the same
in both flavors. The two apps have different application IDs, so you can install both side by side.

> Regenerating the AARs: in the private `AndroidLibraryCode` repo, `build.sh` builds the regular (ACL) wake-word AAR
> and `build_lowfp.sh` builds the low-footprint one; `cp_lib.sh` copies both into this repo's `libs/`.

### License key

The app ships a **temporary DaVoice demo license**
([DemoConfig.kt](app/src/main/java/io/davoice/example/DemoConfig.kt), `DAVOICE_LICENSE_KEY`). It covers every
DaVoice library in this repo. If it has expired, or when you need a production key, contact info@davoice.io.

### Gemini (optional)

The *AI Chat (Gemini)* mode needs your own key from [Google AI Studio](https://aistudio.google.com/apikey). Put it in
`local.properties` (git-ignored) and rebuild:

```properties
gemini.apiKey=YOUR_KEY
```

Without a key every other mode works. The Gemini mode then tells you that it needs a key.

## Project layout

```
libs/com/davoice/            DaVoice AARs as a local Maven repo (keyworddetection, tts, llm)
app/src/main/assets/         wake word + speaker verification models; TTS voices in models/
app/src/main/java/io/davoice/example/
  DemoConfig.kt              every tunable: license, wake words, thresholds, voices, audio routing JSON, prompts
  VoiceDemo.kt               the whole demo flow (startup, wake word, modes, chat turns)
  MainActivity.kt            renders VoiceDemo's state; no logic
  voice/
    WakewordController.kt    wake word: create, start/pause/resume, recordings
    SpeakerVerificationController.kt   enrollment from the mic, live verification
    SpeechController.kt      STT + TTS: initAll, pause/unpause recognition, speak-and-wait
    TranscriptMerger.kt      merges partial STT results into one running transcript
    AssetInstaller.kt        copies bundled models to app storage (the engines open files by path)
  chat/
    LocalLlm.kt              local LLM: model download, load, prefill, streaming generation
    GeminiClient.kt          Gemini REST client (cloud mode only)
    SpokenText.kt            clean-up for text that is read aloud, sentence splitting for streaming TTS
```

## Reuse map: which file you need for which feature

| You want | Copy | AARs |
|---|---|---|
| Wake word only | `WakewordController.kt`, a `.dm` wake-word model + `layer1.dm` | keyworddetection |
| Speaker verification | `SpeakerVerificationController.kt`, `AssetInstaller.kt`, `sv.dm` | keyworddetection |
| STT and/or TTS | `SpeechController.kt`, `AssetInstaller.kt`, a voice model; `TranscriptMerger.kt` for continuous dictation | tts **+ keyworddetection** (it provides ONNX Runtime) |
| Wake word → STT → TTS handoff | the above, plus the pause/resume sequence in `VoiceDemo.onWakeWord` / `enterMode` | keyworddetection, tts |
| Local LLM | `LocalLlm.kt`, `SpokenText.kt` for speaking streamed replies | llm |

Minimal code per feature, and the Gradle setup: [docs/integration.md](docs/integration.md).

## Models

| File | What |
|---|---|
| `assets/hey_coach_model_28_22012026b.dm` | "Hey Coach" wake word |
| `assets/hey_lookdeep.dm` | "Hey Lookdeep" wake word |
| `assets/layer1.dm` | Shared wake-word front end (required by every wake-word model) |
| `assets/sv.dm` | Speaker verification |
| `assets/models/model_ex2_rich.dm` | Rich (multi-voice ttsv2 model) |
| `assets/models/model_ex_ariana_fast_davoice_phoneme.dm` | Ariana |
| `assets/models/model_ex_hanna_light_davoice_ph.dm` | Hanna |
| `assets/models/model_ex2_rich_hanna_ariana.dm` | All three voices in one model (`DemoConfig.USE_SHARED_TTS2_MODEL = true`) |

Shipping your own app? Bundle only the voices you use. Each one is 70 to 150 MB.

## Custom wake words and voices

For a custom wake word ("Hey <your brand>"), contact info@davoice.io with your phrase. You will get a `.dm` file. Drop
it in `app/src/main/assets/` and add it to `DemoConfig.WAKE_WORDS`. Several wake words can run at once.

For a custom or cloned TTS voice, contact info@davoice.io.

## Related

- [React Native DaVoice](https://github.com/frymanofer/ReactNative_DaVoice): the same demo in React Native.
- [Android Native Wake Word](https://github.com/frymanofer/Android_Native_Wake_Word): wake word only.

## License

The example code is MIT (see [LICENSE](LICENSE)). The DaVoice libraries and models need a DaVoice license key.
