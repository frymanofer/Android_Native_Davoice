package io.davoice.caragent

import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj

/** What you would tune for your own app: license, wake word, speaker verification and the audio routing (the same values as the
 *  React Native and Flutter example_car_agent). */
object VoiceConfig {
    /** Temporary DaVoice demo license, valid for every DaVoice library (wake word, speaker verification, STT/TTS, local LLM).
     *  Contact info@davoice.io for your own key. */
    const val DAVOICE_LICENSE_KEY = "DV2.eyJ2IjoyLCJraWQiOiJrMSIsImlkIjoiMzI4NWRhMmIxNDU2IiwiY3VzdCI6IkRhVm9pY2UgZGVtbyBhcHAgQW5kcm9pZF9OYXRpdmVfRGF2b2ljZS9leGFtcGxlX2Nhcl9hZ2VudCAoZXhhY3QgYXBwIGlkcyBvbmx5KSIsImlhdCI6MTc5NTcxMTA3NjE2MSwiZXhwIjoxODMwMjk3NjAwMDAwLCJwcm9kIjpbIioiXSwicGxhdCI6WyJhbmRyb2lkIl0sImFwcHMiOnsiYW5kcm9pZCI6WyJpby5kYXZvaWNlLmNhcmFnZW50Il19LCJtb2RlbHMiOlsidHRzLyoiLCJ3dy8qIl19.p_HToxoaWAPN1ZpJ1c4_JuMvWesS0lk9tdTfLf2LHQPHcwMWZKde_7ShkyWyKOOo818ySz4taSQ7tXe1_17kSQ"

    data class WakeWordModel(val assetName: String, val threshold: Float, val bufferCount: Int, val msBetweenCallbacks: Long)

    /** "Hey DaVoice", as the React Native car agent (src/main/assets; the engine also needs its shared front end, layer1.dm, there). */
    val WAKE_WORDS = listOf(WakeWordModel("hey_davoice_model_28_06042026.dm", threshold = 0.999f, bufferCount = 3, msBetweenCallbacks = 1000L))
    const val WAKE_PHRASE = "Hey DaVoice"

    // Speaker verification
    const val SV_MODEL_ASSET = "sv.dm"
    const val SV_ONBOARDING_SAMPLE_COUNT = 5
    const val SV_DECISION_THRESHOLD = 0.34f
    const val SV_MATCH_HOLD_MS = 750L
    const val SV_VERIFY_HOP_SECONDS = 0.25f
    const val SV_ENROLLMENT_FILE = "sv_enrollment.json"
    /** The owner's id in the speaker set (doors only for this voice when speaker verification is on). */
    const val SV_SPEAKER_ID = "owner"

    /** The shared audio-routing JSON for wake word, STT and TTS. On Android the wake-word library reads `wakeWordDuringTTS` and the
     *  AEC keys (the conversation settings add AEC for the interrupt mode); the per-port entries are the iOS policy, kept identical to
     *  the other apps so all platforms share one config. */
    val audioRouting: Obj = mapOf(
        "WakewordAEC" to mapOf("regular" to false, "duringTTS" to false),
        "default" to route(listOf("mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay", "defaultToSpeaker"), "bluetoothHighQualityMic", "none"),
        "byOutputPort" to mapOf(
            "carAudio" to route(listOf("mixWithOthers", "allowBluetooth", "allowBluetoothA2DP", "allowAirPlay", "overrideMutedMicrophoneInterruption"), "none"),
            "builtInReceiver" to route(listOf("mixWithOthers", "allowBluetoothA2DP", "allowAirPlay", "defaultToSpeaker"), "builtInMic"),
            "builtInSpeaker" to route(listOf("mixWithOthers", "allowBluetoothA2DP", "allowAirPlay", "defaultToSpeaker"), "builtInMic"),
            "bluetoothA2DP" to route(listOf("mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay"), "bluetoothHighQualityMic", "none"),
            "bluetoothHFP" to route(listOf("mixWithOthers", "bluetoothHighQualityRecording", "allowAirPlay"), "bluetoothHighQualityMic", "none"),
            "headphones" to route(listOf("mixWithOthers", "allowBluetoothA2DP", "allowAirPlay"), "builtInMic"),
        ),
        "WhenPlayingAudio" to mapOf("onPlay" to mapOf("options" to listOf("duckOthers")), "onFinishPlaying" to mapOf("notifyOthers" to true)),
        "STTDuckingConfig" to mapOf("onUnpause" to mapOf("options" to listOf("duckOthers")), "onPause" to mapOf("notifyOthers" to true)),
        "wakeWordDuringTTS" to mapOf("threshold" to 0.9, "buffer_cnt" to 1),
    )

    private fun route(options: List<String>, preferredInput: String, forceFallback: String? = null): Obj =
        linkedMapOf<String, Any?>("category" to "playAndRecord", "mode" to "default", "options" to options, "preferredInput" to preferredInput)
            .also { if (forceFallback != null) it["forceFallback"] = forceFallback }

    fun audioRoutingJson(routing: Obj = audioRouting) = Json.stringify(routing)

    /** Silence after the last word before the request is sent (the conversation settings override it per mode). */
    const val SILENCE_TIMEOUT_MS = 2000L
}
