# DaVoice: AI Car Voice Agent (native Android)

An on-device voice assistant for a car, in native Kotlin: **wake word → (optional) speaker verification → speech to text → the
DaVoice Automotive Agent (a fine-tuned 0.6B model running on the phone) → actions on a simulated car → text to speech.**

This is the native twin of the React Native and Flutter `example_car_agent`
([React Native DaVoice](https://github.com/frymanofer/ReactNative_DaVoice/tree/main/example_car_agent)). It has the same agent and
car tools, the same replies and follow-ups, and the same conversation settings. The car logic is checked against the same shared
test cases as the other two apps.

> The car is **simulated**. Nothing here talks to a real vehicle.

## What it does

1. **Language.** Choose one of 11 languages. Its voice (about 170 MB) downloads the first time; each file is sha256-checked.
2. **Voice.** Pick one of the language's voices. Tap a voice to hear it.
3. **Speaker verification** (optional). Record a voice signature by reading 5 sentences, use a saved one, or skip this step. With a
   signature, only your voice can open or unlock the doors, and the wake word answers only you.
4. **Say "Hey Coach".** The model (382 MB) downloads and warms up in the background meanwhile.
5. **Talk to the car.** For example: "Open the driver window", "I'm cold", "Take me home", "Lock the car", "Play Anitta",
   "Is the car locked?", "Undo that". The screen shows the car from above, what it heard, what ran, and the reply.

English and Brazilian Portuguese are tuned. In the other languages, the agent asks before every car action ("Soll ich das
Fahrerfenster öffnen?") and runs it only after a plain yes.

### Settings (the gear)

- **Conversation mode:**
  - **Turns.** *Take turns* (the microphone is closed while the agent answers) or *Interrupt* (talk over the agent to stop it).
    Interrupt uses echo cancellation (AEC) and is experimental on Android.
  - **Start each request with.** *Wake word* or *Just talk*.
  - **Wake-word timeouts.** End of speech, how long to listen after the wake word, the follow-up window after a reply, and
    pre-fetch (keeps the words said right after "Hey Coach"). You can also choose whether the wake word stops the agent while it talks.
  - **Just-talk timeouts.** End of speech, and when to stop listening (then say the wake word or tap the microphone).
- **Local confidence.** A threshold on the model's score for its action, and what happens below it: *Clarify*, *Remote* or
  *Decline*.

The settings are saved on the phone and apply at once.

## Build and run

From the repository root (see the main [README](../README.md) for Git LFS and the SDK):

```bash
./gradlew :example_car_agent:installFullDebug     # or :example_car_agent:installLiteDebug (no ACL, smaller)
./gradlew :example_car_agent:testFullDebugUnitTest
```

- **Device.** An arm64-v8a phone with Android 10 or newer.
- **Storage.** About 1.2 GB free: one voice (170 MB), the model (382 MB) and its pre-warmed start-up state (430 MB).
- **APK size.** About 60 MB; no voice or model is bundled.
- **Debug builds** show a text box and suggestion chips, so you can try the agent without speaking.

The app ships a temporary DaVoice demo license (`VoiceConfig.kt`). For your own key, contact info@davoice.io.

## Project layout

```
src/main/java/io/davoice/caragent/
  CarAgentApp.kt          the app flow: language, voice, speaker, wake word, and one request from the words to the spoken reply
  VoiceConfig.kt          license, wake word, speaker verification, the audio routing JSON
  MainActivity.kt         permissions + the screens
  car/                    the simulated car: state, tools (what each action does and says), undo, follow-ups, questions about
                          the car, the speech composer, media titles, the Basic-language flow
  agent/                  the DavoiceAgent client, the automotive pack, the model (download, warm-up, KV state), Local confidence
  conversation/           the conversation modes: settings, the controller (who owns the microphone), the interrupt rule
  voice/                  wake word, speaker verification and speech controllers (the same API as the React Native packages)
  tts/                    the language packs (voice downloads)
  speaker/                the enrollment sentences and the owner check for the doors
  ui/                     the Compose screens and the car drawing
src/main/assets/          the agent pack, the car's tool definitions and Basic-language sentences, the language-pack list,
                          the wake-word and speaker-verification models, the speech-recognition hint words
src/test/                 JUnit tests on the fixtures shared with the React Native and Flutter apps
```

The tool definitions (`assets/car/carToolDefinitions.h3d.json`) are the wording the model was trained with. Keep them identical
to the other apps.

## What is not in this version

- Voice cloning (the other apps' "clone a voice" screen).
- Remote updates to the language-pack list (the other apps verify a signed manifest).
- A remote assistant for general questions. As in the other apps' default, the agent says it isn't set up in this demo.
