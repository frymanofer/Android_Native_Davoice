package io.davoice.example.voice

import android.content.Context
import android.util.Log
import com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection
import com.davoice.keywordsdetection.keywordslibrary.WakewordAudioRoutingConfig
import io.davoice.example.WakeWordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Voice-activity state from [WakewordController.getVoiceProps]. */
data class VoiceProps(val error: String, val voiceProbability: Double, val lastTimeHumanVoiceHeard: Double)

/** Current VAD settings ([WakewordController.getVADParams]). */
data class VADParams(val threshold: Float, val msWindow: Int)

/**
 * Result of [WakewordController.detectOnce]: [detected] when a model passed its threshold for its
 * required number of consecutive frames; [modelName] is that model (or the best-scoring one),
 * [maxScore] its highest score (0..1), [detectedAtMs] where in the clip it fired (-1 if not),
 * [processingMs] how long the check took, [error] non-null when the clip was not processed.
 */
data class OneShotResult(
    val detected: Boolean,
    val modelName: String?,
    val maxScore: Float,
    val detectedAtMs: Int,
    val processingMs: Long,
    val error: String?,
)

/**
 * Always-listening wake word (com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection):
 * the native twin of the React Native `KeyWordRNBridgeInstance` (react-native-wakeword).
 * Method names and arguments match the React Native API. One detector can run
 * several wake-word models at once.
 */
class WakewordController(context: Context) {

    private val appContext = context.applicationContext
    private var detector: KeyWordsDetection? = null
    private var models: List<WakeWordModel> = emptyList()
    private var vadParams = VADParams(DEFAULT_VAD_THRESHOLD, DEFAULT_VAD_MS_WINDOW)

    /**
     * Called on a library thread with the detected model's file name and, when speaker
     * verification is on, the enrolled speaker that opened the gate (React Native
     * `onKeywordDetectionEvent` with `phrase` / `speakerId`).
     */
    var onDetection: ((modelName: String, speakerId: String?) -> Unit)? = null

    /** Same as [onDetection] without the speaker. */
    var onDetected: ((modelName: String) -> Unit)? = null

    // ================================================================================
    // Create / license / destroy
    // ================================================================================

    /**
     * Shared routing JSON for wake word, STT and TTS (React Native
     * `setWakewordAudioRoutingConfig`; see DemoConfig.AUDIO_ROUTING_CONFIG_JSON).
     * Includes AEC (`"AEC": {"mode": "off" | "duringTTS" | "always"}`) and the stricter
     * wake-word threshold used while TTS plays. Set it before [create]; later calls
     * are applied to the running detector.
     */
    fun setAudioRoutingConfig(json: String) {
        WakewordAudioRoutingConfig.setFromJson(json)
        detector?.refreshWakewordAudioConfig()
    }

    /** Loads the models (from app assets, by file name): React Native `createInstanceMulti`. */
    suspend fun create(models: List<WakeWordModel>) = withContext(Dispatchers.IO) {
        this@WakewordController.models = models
        detector = KeyWordsDetection(
            appContext,
            models.map { it.assetName }.toTypedArray(),
            models.map { it.threshold }.toFloatArray(),
            models.map { it.bufferCount }.toIntArray(),
            models.map { it.msBetweenCallbacks }.toLongArray(),
        ).also { it.initialize(::onCallback) }
    }

    /** One model: React Native `createInstance(modelName, threshold, bufferCnt)`. */
    suspend fun createInstance(modelName: String, threshold: Float, bufferCnt: Int) =
        create(listOf(WakeWordModel(modelName, threshold, bufferCnt, DEFAULT_MS_BETWEEN_CALLBACKS)))

    /** Swaps the model(s) of the running detector. */
    suspend fun replaceKeywordDetectionModel(modelName: String, threshold: Float, bufferCnt: Int) =
        withContext(Dispatchers.IO) {
            models = listOf(WakeWordModel(modelName, threshold, bufferCnt, DEFAULT_MS_BETWEEN_CALLBACKS))
            requireDetector().replaceKeywordDetectionModel(appContext, modelName, threshold, bufferCnt)
        }

    fun setLicense(licenseKey: String): Boolean = detector?.setLicenseKey(licenseKey) == true

    /** React Native name for [setLicense]. */
    fun setKeywordDetectionLicense(licenseKey: String): Boolean = setLicense(licenseKey)

    /** Stops listening and drops the detector; call [create] again to use it. */
    suspend fun destroyInstance() = withContext(Dispatchers.IO) {
        runCatching { detector?.stopListening() }
        runCatching { detector?.stopExternalStreaming() }
        detector = null
    }

    // ================================================================================
    // Listening
    // ================================================================================

    /**
     * Demo helper: (re)starts listening and leaves detection paused, so the app
     * decides when it begins ([resume]). With [speakerEnrollmentPath] the wake word
     * only fires for the enrolled speaker.
     */
    suspend fun start(speakerEnrollmentPath: String? = null) = withContext(Dispatchers.IO) {
        val kwd = requireDetector()
        runCatching { kwd.stopListening() }
        Log.d(TAG, "startListening(sv=${!speakerEnrollmentPath.isNullOrEmpty()})")
        if (speakerEnrollmentPath.isNullOrEmpty()) kwd.startListening(defaultThreshold())
        else kwd.startListening(defaultThreshold(), speakerEnrollmentPath)
        kwd.pauseDetection(false)
        delay(100)
    }

    /**
     * Starts detection. [speakerVerificationEnrollmentJsonOrPath] (enrollment JSON or a
     * path) adds one speaker under "default": the wake word then fires only for them.
     */
    suspend fun startKeywordDetection(threshold: Float = defaultThreshold(),
                                      speakerVerificationEnrollmentJsonOrPath: String? = null) =
        withContext(Dispatchers.IO) {
            val kwd = requireDetector()
            if (speakerVerificationEnrollmentJsonOrPath.isNullOrBlank()) kwd.startListening(threshold)
            else kwd.startListening(threshold, speakerVerificationEnrollmentJsonOrPath)
        }

    /** Starts detection adding one speaker under [speakerId]. */
    suspend fun startKeywordDetectionWithSpeakerId(threshold: Float, speakerVerificationEnrollmentJsonOrPath: String,
                                                   speakerId: String = SpeechController.DEFAULT_SPEAKER) =
        withContext(Dispatchers.IO) {
            requireDetector().startListening(threshold, speakerVerificationEnrollmentJsonOrPath,
                speakerId.ifBlank { SpeechController.DEFAULT_SPEAKER })
        }

    /** Starts detection with [speakers], replacing the current set. */
    suspend fun startKeywordDetectionWithSpeakers(threshold: Float, speakers: List<SpeakerItem>) =
        withContext(Dispatchers.IO) { requireDetector().startListeningWithSpeakers(threshold, speakers.toNative()) }

    /** Pauses detection; with [stopMic] false the microphone stays open (faster resume). */
    suspend fun pause(stopMic: Boolean = false) = withContext(Dispatchers.IO) {
        Log.d(TAG, "pauseDetection(stopMic=$stopMic)")
        detector?.pauseDetection(stopMic)
    }

    suspend fun resume() = withContext(Dispatchers.IO) {
        Log.d(TAG, "unPauseDetection()")
        runCatching { detector?.unPauseDetection() }.onFailure { Log.e(TAG, "unPauseDetection failed", it) }
    }

    /** React Native names for [pause] / [resume]. */
    suspend fun pauseDetection(stopMic: Boolean = false) = pause(stopMic)
    suspend fun unPauseDetection() = resume()

    suspend fun stop() = withContext(Dispatchers.IO) {
        Log.d(TAG, "stopListening()")
        detector?.stopListening()
    }

    /** React Native name for [stop]. */
    suspend fun stopKeywordDetection() = stop()

    /** Tells the detector TTS audio is playing (it then uses the routing config's wakeWordDuringTTS settings). */
    fun setTTSActive(active: Boolean) { detector?.setTTSActive(active) }

    /** Keeps the microphone alive in the background with a foreground-service notification. */
    fun startForegroundService() { detector?.startForegroundService() }

    fun stopForegroundService() { detector?.stopForegroundService() }

    // ================================================================================
    // Speakers (live, while listening)
    // ================================================================================

    /** Adds (or replaces) one speaker. Returns the enrolled ids. */
    suspend fun addSpeaker(speakerId: String, jsonOrPath: String): List<String> = withContext(Dispatchers.IO) {
        requireDetector().apply { addSpeaker(speakerId, jsonOrPath) }.speakerIds
    }

    /** Replaces the whole speaker set; an empty list turns speaker verification off. Returns the enrolled ids. */
    suspend fun setSpeakers(speakers: List<SpeakerItem>): List<String> = withContext(Dispatchers.IO) {
        requireDetector().apply { setSpeakers(speakers.toNative()) }.speakerIds
    }

    /** Removes a speaker; removing the last one turns the gate off. False if unknown. */
    suspend fun removeSpeaker(speakerId: String): Boolean = withContext(Dispatchers.IO) {
        detector?.removeSpeaker(speakerId) ?: false
    }

    fun getSpeakerIds(): List<String> = detector?.speakerIds ?: emptyList()

    /** The speaker that opened the gate on the last detection (null without speaker verification). */
    fun getLastDetectedSpeakerId(): String? = detector?.lastDetectedSpeakerId

    // ================================================================================
    // Your own audio (no microphone)
    // ================================================================================
    // For apps that capture the microphone themselves: pass 16 kHz, mono, 16-bit PCM
    // (ShortArray), chunks of any size. These calls never open the microphone and cannot run
    // while microphone detection (start / startKeywordDetection) is running. Native only: the
    // React Native packages have no twin.

    /** Optional one-time warm-up (~0.3 s); otherwise the first own-audio call does it. */
    suspend fun warmUpExternalAudio(): Boolean = withContext(Dispatchers.Default) {
        requireDetector().warmUpExternalAudio()
    }

    /**
     * Checks one clip, typically ~2 s that may contain the wake word. [onDetection] is not
     * called; read the result. Every call is independent.
     */
    suspend fun detectOnce(pcm: ShortArray, length: Int = pcm.size): OneShotResult = withContext(Dispatchers.Default) {
        val r = requireDetector().detectOnce(pcm, length)
        OneShotResult(r.detected, r.modelName, r.maxScore, r.detectedAtMs, r.processingMs, r.error)
    }

    /**
     * Starts detection on the audio you pass to [pushAudio]. Detections arrive on [onDetection],
     * on the thread that called pushAudio. With [useVadGate] the wake word models run only while
     * voice activity is detected (much less CPU in silence); the second of audio before the
     * speech onset is processed first, so the start of the wake word is not lost.
     * Returns false if it could not start (no license, microphone detection running, ...).
     */
    suspend fun startKeywordDetectionExternal(useVadGate: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        requireDetector().startExternalStreaming(useVadGate)
    }

    /**
     * Passes captured audio. Blocking: it runs the models on the calling thread, so call it from
     * your audio worker thread (not the UI thread). Ignored when not started.
     */
    fun pushAudio(pcm: ShortArray, length: Int = pcm.size) {
        detector?.pushAudio(pcm, length)
    }

    suspend fun stopKeywordDetectionExternal() = withContext(Dispatchers.IO) {
        detector?.stopExternalStreaming()
    }

    /**
     * VAD gate settings for [startKeywordDetectionExternal] (same defaults as the microphone mode).
     * Lower [speechThreshold] if your input level is low.
     */
    fun setExternalVadGate(speechThreshold: Float = 0.4f, hangoverMs: Int = 2500, preRollMs: Int = 1000) {
        requireDetector().setExternalVadGate(speechThreshold, hangoverMs, preRollMs)
    }

    /** True while own-audio detection runs with a working VAD gate. */
    fun isExternalVadGateActive(): Boolean = detector?.isExternalVadGateActive == true

    // ================================================================================
    // Detection audio
    // ================================================================================

    /** Saving the audio that triggered each detection (on by default). Off: no new files; saved ones are kept. */
    fun setDetectionAudioRecording(enabled: Boolean) { requireDetector().setDetectionAudioRecording(enabled) }

    fun isDetectionAudioRecordingEnabled(): Boolean = detector?.isDetectionAudioRecordingEnabled == true

    fun getRecordingWav(): String = detector?.recordingWav.orEmpty()

    fun getRecordingWavArray(): List<String> = detector?.recordingWavArray?.toList().orEmpty()

    /** WAV files holding the audio that triggered the last detection. */
    fun lastRecordings(): List<String> {
        val many = runCatching { getRecordingWavArray() }.getOrNull().orEmpty()
        val one = runCatching { getRecordingWav() }.getOrNull()
        return (many.ifEmpty { listOfNotNull(one) }).filter { it.isNotEmpty() }
    }

    // ================================================================================
    // Voice activity detection
    // ================================================================================

    suspend fun getVoiceProps(): VoiceProps = withContext(Dispatchers.IO) {
        val props = requireDetector().voiceProps
        VoiceProps(
            error = props["error"]?.toString().orEmpty(),
            voiceProbability = (props["voiceProbability"] as? Number)?.toDouble() ?: 0.0,
            lastTimeHumanVoiceHeard = (props["lastTimeHumanVoiceHeard"] as? Number)?.toDouble() ?: 0.0,
        )
    }

    /** Starts VAD with the current [setVADParams] values. */
    suspend fun startVADDetection(): Boolean = withContext(Dispatchers.IO) {
        val kwd = requireDetector()
        kwd.setVADParams(vadParams.threshold, vadParams.msWindow)
        kwd.startVADListening()
    }

    suspend fun stopVADDetection() = withContext(Dispatchers.IO) { requireDetector().stopVADListening() }

    fun setVADParams(threshold: Float, msWindow: Int) {
        vadParams = VADParams(threshold, msWindow)
        detector?.setVADParams(threshold, msWindow)
    }

    fun getVADParams(): VADParams = vadParams

    // ================================================================================
    // Internals
    // ================================================================================

    private fun onCallback(detected: Boolean, modelName: String) {
        Log.d(TAG, "callback detected=$detected model=$modelName")
        if (!detected) return
        val speakerId = runCatching { detector?.lastDetectedSpeakerId }.getOrNull()
        onDetection?.invoke(modelName, speakerId)
        onDetected?.invoke(modelName)
    }

    private fun requireDetector(): KeyWordsDetection = requireNotNull(detector) { "create() first" }

    private fun defaultThreshold(): Float = models.firstOrNull()?.threshold ?: DEFAULT_THRESHOLD

    private fun List<SpeakerItem>.toNative(): List<Map<String, String>> = map { mapOf("id" to it.id, "json" to it.json) }

    private companion object {
        const val TAG = "DemoWakeword"
        const val DEFAULT_THRESHOLD = 0.999f
        const val DEFAULT_MS_BETWEEN_CALLBACKS = 1000L
        // Same defaults as the React Native bridge.
        const val DEFAULT_VAD_THRESHOLD = 0.45f
        const val DEFAULT_VAD_MS_WINDOW = 1000
    }
}
