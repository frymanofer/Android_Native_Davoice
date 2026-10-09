package io.davoice.example

/**
 * Everything you would tune for your own app lives here. Values mirror the
 * React Native example (ReactNative_DaVoice/example) one to one.
 */
object DemoConfig {

    /**
     * Temporary DaVoice demo license, valid for every DaVoice library (wake
     * word, speaker verification, STT/TTS, local LLM). Contact info@davoice.io
     * for your own key.
     */
    const val DAVOICE_LICENSE_KEY =
        "MTc5MzQ4NDAwMDAwMA==-cpeHAmR/9wRKvv9rBJ+36JqMUxmXR3RIpi5lK67VsUQ="

    // ---- Wake word -----------------------------------------------------------

    /** Wake-word models in app/src/main/assets (looked up by file name); all run at once. */
    val WAKE_WORDS = listOf(
        WakeWordModel(
            assetName = "hey_coach_model_28_22012026b.dm",
            threshold = 0.999f,
            bufferCount = 3,
            msBetweenCallbacks = 1000L,
        ),
        WakeWordModel(
            assetName = "hey_lookdeep.dm",
            threshold = 0.9f,
            bufferCount = 2,
            msBetweenCallbacks = 1000L,
        ),
    )

    // The wake-word engine also needs its shared front-end model, "layer1.dm",
    // in app/src/main/assets (it is found there by name, not configured here).

    /** "hey_coach_model_28_22012026b.dm" -> "Hey Coach". */
    fun displayName(model: String): String =
        model.replace(Regex("(_model.*|_\\d+.*)(\\.(onnx|dm))$"), "")
            .replace(Regex("\\.(onnx|dm)$"), "")
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /**
     * Shared audio-routing JSON for wake word, STT and TTS. On Android the
     * wake-word library reads `wakeWordDuringTTS` (wake word stays armed while
     * TTS plays, at a stricter threshold) and the AEC keys; the per-port
     * entries are the iOS routing policy and are kept identical to the React
     * Native example so both platforms share one config.
     */
    val AUDIO_ROUTING_CONFIG_JSON = """
        {
          "WakewordAEC": { "regular": false, "duringTTS": false },
          "default": {
            "category": "playAndRecord", "mode": "default",
            "options": ["mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay", "defaultToSpeaker"],
            "preferredInput": "bluetoothHighQualityMic", "forceFallback": "none"
          },
          "byOutputPort": {
            "carAudio": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "allowBluetooth", "allowBluetoothA2DP", "allowAirPlay", "overrideMutedMicrophoneInterruption"],
              "preferredInput": "none"
            },
            "builtInReceiver": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "allowBluetoothA2DP", "allowAirPlay", "defaultToSpeaker"],
              "preferredInput": "builtInMic"
            },
            "builtInSpeaker": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "allowBluetoothA2DP", "allowAirPlay", "defaultToSpeaker"],
              "preferredInput": "builtInMic"
            },
            "bluetoothA2DP": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay"],
              "preferredInput": "bluetoothHighQualityMic", "forceFallback": "none"
            },
            "bluetoothHFP": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay"],
              "preferredInput": "bluetoothHighQualityMic", "forceFallback": "none"
            },
            "headphones": {
              "category": "playAndRecord", "mode": "default",
              "options": ["mixWithOthers", "allowBluetoothA2DP", "allowAirPlay"],
              "preferredInput": "builtInMic"
            }
          },
          "WhenPlayingAudio": { "onPlay": { "options": ["duckOthers"] }, "onFinishPlaying": { "notifyOthers": true } },
          "STTDuckingConfig": { "onUnpause": { "options": ["duckOthers"] }, "onPause": { "notifyOthers": true } },
          "wakeWordDuringTTS": { "threshold": 0.9, "buffer_cnt": 1 }
        }
    """.trimIndent()

    // ---- Speaker verification ---------------------------------------------------

    const val SV_MODEL_ASSET = "sv.dm"
    const val SV_ONBOARDING_SAMPLE_COUNT = 5
    const val SV_DECISION_THRESHOLD = 0.34f
    /** A match keeps showing as a match this long after the last positive result. */
    const val SV_MATCH_HOLD_MS = 750L
    const val SV_VERIFY_HOP_SECONDS = 0.25f
    const val SV_ENROLLMENT_FILE = "sv_enrollment.json"

    // ---- Speech (STT + TTS) ----------------------------------------------------------

    const val STT_LOCALE = "en-US"
    /** Silence after the last recognized word before the utterance is acted on. */
    const val SILENCE_TIMEOUT_MS = 2000L
    const val SPEAKER_ID = 0

    /**
     * false: one model per voice (reloaded when the voice changes).
     * true: the shared multi-voice ttsv2 model, switching voices with changeVoice().
     */
    const val USE_SHARED_TTS2_MODEL = false

    val DEFAULT_VOICE = Voice.Rich

    // ---- AI chat ------------------------------------------------------------------

    const val GEMINI_MODEL = "gemini-3.1-flash-lite-preview"
    const val GEMINI_SYSTEM_PROMPT =
        "You are a helpful voice assistant inside an Android demo app. Reply conversationally, " +
            "keep answers concise for spoken playback, and avoid markdown. For time-sensitive facts " +
            "like current leaders, dates, news, prices, or recent events, answer cautiously and say " +
            "when you may be unsure rather than asserting a stale fact."
    const val GEMINI_MAX_OUTPUT_TOKENS = 512
    const val GEMINI_THINKING_BUDGET = 256
    const val AI_CHAT_MIN_REQUEST_GAP_MS = 4000L
    const val AI_CHAT_RATE_LIMIT_BACKOFF_MS = 30_000L

    const val LOCAL_LLM_SYSTEM_PROMPT =
        "You are a helpful voice assistant. Keep all answers under four sentences. " +
            "Do not use markdown, bullet points, or numbered lists. " +
            "Do not begin a sentence with \"Certainly\" or \"Of course\". " +
            "Spell out abbreviations when reading aloud."
}

data class WakeWordModel(
    val assetName: String,
    val threshold: Float,
    val bufferCount: Int,
    val msBetweenCallbacks: Long,
)

/** The three demo voices and the model each one uses. */
enum class Voice(val displayName: String) {
    Rich("Rich"), Ariana("Ariana"), Hanna("Hanna");

    /** Model file in assets/models, speaking speed, and whether it is a ttsv2 (multi-voice) model. */
    data class Model(val assetPath: String, val speed: Float, val usesTTS2: Boolean)

    fun model(): Model =
        if (DemoConfig.USE_SHARED_TTS2_MODEL) {
            val speed = if (this == Rich) 0.95f else 1.0f
            Model("models/model_ex2_rich_hanna_ariana.dm", speed, usesTTS2 = true)
        } else when (this) {
            Rich -> Model("models/model_ex2_rich.dm", 0.95f, usesTTS2 = true)
            Ariana -> Model("models/model_ex_ariana_fast_davoice_phoneme.dm", 0.88f, usesTTS2 = false)
            Hanna -> Model("models/model_ex_hanna_light_davoice_ph.dm", 0.9f, usesTTS2 = false)
        }
}

/** The five things the demo lets you try after the wake word. */
enum class AppMode(val title: String) {
    LocalAIChat("Local AI Chat (on device)"),
    FullAIChat("AI Chat (Gemini)"),
    TypeToTTS("Type to TTS"),
    STTOnly("Speech to Text only"),
    Combined("Speech to Text + Text to Speech"),
}
