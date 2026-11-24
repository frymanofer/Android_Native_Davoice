package io.davoice.caragent.voice

import android.content.Context
import com.davoice.stt.STT
import com.davoice.stt.STTDelegate
import com.davoice.tts.DaVoiceTTSInterface
import com.davoice.tts.LicenseManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/** One enrolled speaker: [json] is the enrollment JSON itself or a path to it. */
data class SpeakerItem(val id: String, val json: String)

/** Result of [SpeechController.unPauseSpeechRecognition]. */
data class SpeechTransitionResult(val ok: Boolean, val reason: String, val elapsedMs: Long)

/** [SpeechController.createVoiceClone] result. Non-empty [warnings]: ask the user to record again. */
data class VoiceCloneResult(val path: String, val voice: String, val warnings: List<String>)

/** [SpeechController.getOnDeviceLanguages] result. Tags look like "pt-BR". */
data class OnDeviceLanguages(
    /** False below Android 13 (no way to ask); the lists are then empty. */
    val apiSupported: Boolean,
    /** On-device model present now: offline recognition works. */
    val installed: List<String>,
    /** On-device model downloading. */
    val pending: List<String>,
    /** Can be downloaded for on-device use. */
    val supportedOnDevice: List<String>,
    /** Recognized in the cloud by the system recognizer. */
    val online: List<String>,
    /** Languages this app switched to cloud recognition because their on-device model is missing. */
    val cloudFallback: List<String>,
    /** Set when the query failed or timed out. */
    val error: String?,
)

/** Sample format of a raw PCM buffer passed to [SpeechController.playBuffer]. */
enum class PcmFormat { I16, F32 }

/**
 * Speech-to-text + text-to-speech: the native twin of the React Native `Speech`
 * object (`react-native-davoice-tts/speech`). Method names, arguments and behaviour
 * match the React Native API, so code ports over line by line:
 *
 *  - [initAll] starts the recognizer (com.davoice.stt.STT) and loads a TTS model
 *    (com.davoice.tts.DaVoiceTTSInterface). TTS and STT coordinate on their own:
 *    the recognizer is paused while TTS plays, so the app never hears itself.
 *  - [speak] queues text right away and returns when everything queued so far has
 *    been spoken, so call it for each sentence as soon as you have it.
 *  - Events (the React Native `onSpeech*` / `onFinishedSpeaking` callbacks) arrive
 *    on [events].
 *
 * Every suspend function is safe to call from the main thread.
 */
class SpeechController(context: Context) : STTDelegate {

    /** The React Native event callbacks, as one stream. */
    sealed interface Event {
        data object Start : Event
        data object Recognized : Event
        data object End : Event
        /** Live text. [speakerIds]/[speakerId] are set only when speaker verification is on. */
        data class Partial(val text: String, val all: List<String> = listOf(text),
                           val speakerIds: List<String> = emptyList(), val speakerId: String? = null) : Event
        /** A finished sentence; same speaker fields as [Partial]. */
        data class Final(val text: String, val all: List<String> = listOf(text),
                         val speakerIds: List<String> = emptyList(), val speakerId: String? = null) : Event
        data class Error(val code: Int, val message: String) : Event
        data class VolumeChanged(val value: Float) : Event
        /** Remote capture ([initAllRemoteSTT]): a full utterance was saved as a WAV. */
        data class NewSpeechWAV(val path: String) : Event
        /** The speech queue is empty (React Native `onFinishedSpeaking`). */
        data object FinishedSpeaking : Event
    }

    private val appContext = context.applicationContext
    private val tts = DaVoiceTTSInterface(appContext)
    @Volatile private var stt: STT? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    // speak()/playWav(): only the sends are serialized; each caller then waits for the
    // queue-empty callback. stopSpeaking() bumps the epoch, which drops unsent texts.
    private val sendLock = Mutex()
    private val speakWaiters = ArrayList<CompletableDeferred<Unit>>()
    @Volatile private var ttsEpoch = 0

    init { wireFinishedListener() }

    // Set again before every init, as the React Native bridge does.
    private fun wireFinishedListener() {
        tts.setOnFinishedSpeakingListenerJava {
            releaseSpeakWaiters()
            _events.tryEmit(Event.FinishedSpeaking)
        }
    }

    // ================================================================================
    // License
    // ================================================================================

    /** STT and TTS share one process-wide license. */
    fun setLicense(licenseKey: String): Boolean =
        tts.setLicenseKey(licenseKey) && LicenseManager.setLicenseKey(licenseKey)

    fun isLicenseValid(licenseKey: String): Boolean = LicenseManager.isLicenseValid(licenseKey)

    // ================================================================================
    // Init / destroy
    // ================================================================================

    /**
     * Starts recognition in [locale] and loads [model] (a .dm file path) for TTS.
     * [onboardingJsonPath] (enrollment JSON or a path to it) adds one speaker under
     * [speakerId]; [speakers] instead replaces the whole speaker set. With either,
     * STT only transcribes the enrolled speakers.
     */
    suspend fun initAll(
        locale: String,
        model: String,
        onboardingJsonPath: String? = null,
        speakerId: String = DEFAULT_SPEAKER,
        speakers: List<SpeakerItem>? = null,
    ) = withContext(Dispatchers.IO) {
        startRecognizer(locale, onboardingJsonPath, speakerId, speakers)
        check(initTTSNow(model)) { "TTS init failed for $model" }
    }

    /** Releases TTS and STT; call [initAll] again to use speech after this. */
    suspend fun destroyAll() = withContext(Dispatchers.IO) {
        cancelSpeakWaits()
        runCatching { tts.destroy() }
        stt?.destroySpeech()
        stt = null
    }

    /** STT only, no TTS model. */
    suspend fun initWithoutModel(
        locale: String,
        onboardingJsonPath: String? = null,
        speakerId: String = DEFAULT_SPEAKER,
        speakers: List<SpeakerItem>? = null,
    ) = withContext(Dispatchers.IO) { startRecognizer(locale, onboardingJsonPath, speakerId, speakers) }

    suspend fun destroyWithoutModel() = withContext(Dispatchers.IO) {
        stt?.destroySpeech()
        stt = null
    }

    /** TTS only: loads [model] (a .dm file path). Returns false if the engine refused it. */
    suspend fun initTTS(model: String): Boolean = withContext(Dispatchers.IO) { initTTSNow(model) }

    /**
     * Remote capture: the microphone + VAD save each utterance as a WAV and emit
     * [Event.NewSpeechWAV] instead of transcribing; also loads [model] for TTS.
     */
    suspend fun initAllRemoteSTT(model: String) = withContext(Dispatchers.IO) {
        recognizer().startRemoteSpeech()
        check(initTTSNow(model)) { "TTS init failed for $model" }
    }

    /** Remote capture (see [initAllRemoteSTT]) + TTS in playback-only mode ([playWav]/[playBuffer], no model). */
    suspend fun initAllRemoteSTTAndTTS() = withContext(Dispatchers.IO) {
        recognizer().startRemoteSpeech()
        wireFinishedListener()
        check(tts.initPlaybackOnly()) { "Playback-only TTS init failed" }
    }

    // ================================================================================
    // Speech to text
    // ================================================================================

    /** (Re)starts recognition; speaker arguments as in [initAll]. Without any, the current speaker set is kept. */
    suspend fun start(
        locale: String,
        onboardingJsonPath: String? = null,
        speakerId: String = DEFAULT_SPEAKER,
        speakers: List<SpeakerItem>? = null,
    ) = withContext(Dispatchers.IO) { startRecognizer(locale, onboardingJsonPath, speakerId, speakers) }

    /** Starts STT adding one speaker (JSON or path) under [speakerId]; same id or voiceprint replaces the older entry. */
    suspend fun startWithSVOnboardingJson(locale: String, onboardingJsonPath: String, speakerId: String = DEFAULT_SPEAKER) =
        start(locale, onboardingJsonPath, speakerId)

    /** Starts STT with [speakers], replacing the current set. */
    suspend fun startWithSVSpeakers(locale: String, speakers: List<SpeakerItem>) =
        withContext(Dispatchers.IO) { recognizer().startSpeech(locale, speakers.toNative()) }

    /** Adds (or replaces) one speaker. Returns the enrolled ids. */
    suspend fun addSpeaker(speakerId: String, jsonOrPath: String): List<String> = withContext(Dispatchers.IO) {
        recognizer().apply { addSpeaker(speakerId, jsonOrPath) }.getSpeakerIds()
    }

    /** Replaces the whole speaker set; an empty list turns speaker verification off. Returns the enrolled ids. */
    suspend fun setSpeakers(speakers: List<SpeakerItem>): List<String> = withContext(Dispatchers.IO) {
        recognizer().apply { setSpeakers(speakers.toNative()) }.getSpeakerIds()
    }

    /** Removes a speaker; removing the last one turns the gate off. False if unknown. */
    suspend fun removeSpeaker(speakerId: String): Boolean = withContext(Dispatchers.IO) {
        stt?.removeSpeaker(speakerId) ?: false
    }

    /** Enrolled speaker ids, in insertion order. */
    fun getSpeakerIds(): List<String> = stt?.getSpeakerIds() ?: emptyList()

    /**
     * Pauses recognition ("lite" pause: the microphone stays open). Bounded inside the
     * library (~500 ms); false if it timed out. A no-op before init.
     */
    suspend fun pauseSpeechRecognition(timeoutMs: Long = 500): Boolean {
        val recognizer = stt ?: return true
        val limit = if (timeoutMs > 0) minOf(timeoutMs, 500L) else 500L
        val work = background.async { recognizer.pauseSpeechRecognitionLite() }
        return withTimeoutOrNull(limit) { work.await() } ?: false
    }

    /**
     * Releases [times] pauses (-1 releases every outstanding pause). [preFetchMs]
     * (0-2000) keeps that much audio from before the unpause. `ok` is false when the
     * recognizer did not confirm it restarted within [timeoutMs].
     */
    suspend fun unPauseSpeechRecognition(times: Int, preFetchMs: Int = 0, timeoutMs: Long = 500): SpeechTransitionResult {
        val startedAt = System.currentTimeMillis()
        val recognizer = stt ?: return SpeechTransitionResult(true, "", 0)
        val work = background.async {
            if (preFetchMs > 0) recognizer.unPauseSpeechRecognitionLite(times, preFetchMs.coerceIn(0, 2000))
            else recognizer.unPauseSpeechRecognitionLite(times)
        }
        val ok = withTimeoutOrNull(if (timeoutMs > 0) timeoutMs else 500L) { work.await() }
        val elapsed = System.currentTimeMillis() - startedAt
        return when (ok) {
            null -> SpeechTransitionResult(false, "timeout", elapsed)
            false -> SpeechTransitionResult(false, "native_reported_failure", elapsed)
            true -> SpeechTransitionResult(true, "", elapsed)
        }
    }

    /** Pauses the microphone itself (stronger than [pauseSpeechRecognition]). */
    suspend fun pauseMicrophone(): Boolean = withContext(Dispatchers.IO) { recognizer().pauseMicrophone() }

    suspend fun unPauseMicrophone(): Boolean = withContext(Dispatchers.IO) { recognizer().unPauseMicrophone() }

    suspend fun stop() = withContext(Dispatchers.IO) { stt?.stopSpeech() }

    suspend fun cancel() = withContext(Dispatchers.IO) { stt?.cancelSpeech() }

    /** Whether this phone has a speech recognizer. */
    suspend fun isAvailable(): Boolean = suspendCancellableCoroutine { cont ->
        STT.isSpeechAvailable(appContext) { ok -> if (cont.isActive) cont.resume(ok) }
    }

    /**
     * Which recognition languages work on-device. When a language has no on-device
     * model, recognition falls back to the cloud automatically (listed in cloudFallback).
     */
    suspend fun getOnDeviceLanguages(): OnDeviceLanguages = suspendCancellableCoroutine { cont ->
        STT.getOnDeviceLanguages(appContext) { info ->
            fun list(key: String) = (info[key] as? List<*>)?.map { it.toString() } ?: emptyList()
            val result = OnDeviceLanguages(
                apiSupported = info["apiSupported"] == true,
                installed = list("installed"),
                pending = list("pending"),
                supportedOnDevice = list("supportedOnDevice"),
                online = list("online"),
                cloudFallback = list("cloudFallback"),
                error = info["error"]?.toString(),
            )
            if (cont.isActive) cont.resume(result)
        }
    }

    fun isRecognizing(): Boolean = stt?.isRecognizing() == true

    // ================================================================================
    // Text to speech
    // ================================================================================

    /** Voices in the loaded .dm (the names [changeVoice] accepts; the first is the default). */
    fun getAllVoices(): List<String> = runCatching { tts.getAllVoices() }.getOrDefault(emptyList())

    /**
     * Style variants ("abilities") of a voice, e.g. "military_sergeant_hard". Use them
     * with tags in [speak] text: "Ok, [drill sergeant] give me 50 push-ups! [regular] Thanks."
     */
    fun getVoiceAbilities(name: String): List<String> =
        runCatching { tts.getVoiceAbilities(name) }.getOrDefault(emptyList())

    /**
     * Also look for voices (<name>.emd made by [createVoiceClone]) in the folder [path];
     * null or "" removes them. Returns the external voice names found.
     */
    suspend fun setExternalVoicesPath(path: String?): List<String> = withContext(Dispatchers.IO) {
        runCatching { tts.setExternalVoicesPath(path?.removePrefix("file://")) }.getOrDefault(emptyList())
    }

    /**
     * Voice cloning: a recording of one speaker (wav, mp3, m4a, ogg, flac, ...) becomes a
     * packed .emd at [outputPath] (".emd" added when missing; a folder ending in "/" uses
     * the input's name). [embedder] is the language's embedder_<code>.dm (a file path or
     * an APK asset name). [volume]: 1 keeps the loudness, 1.2 is 20% louder.
     */
    suspend fun createVoiceClone(inputAudio: String, outputPath: String, embedder: String,
                                 volume: Double = 1.0): VoiceCloneResult = withContext(Dispatchers.IO) {
        val result = tts.createVoiceClone(inputAudio.removePrefix("file://"), outputPath.removePrefix("file://"),
            embedder.removePrefix("file://"), volume)
        VoiceCloneResult(
            path = result["path"].toString(),
            voice = result["voice"].toString(),
            warnings = (result["warnings"] as? List<*>)?.map { it.toString() } ?: emptyList(),
        )
    }

    /** 0 if [name] matched (case-insensitive), -1 otherwise. Audio already generated keeps its voice. */
    suspend fun changeVoice(name: String): Int = withContext(Dispatchers.IO) {
        runCatching { tts.changeVoice(name) }.getOrDefault(-1)
    }

    /**
     * Queues [text] right away and returns when everything queued so far has been
     * spoken. The queue prepares the next sentences while the current one plays.
     * [speed] is a playback-speed multiplier (1.0 = normal).
     */
    suspend fun speak(text: String, speakerId: Int = 0, speed: Float = 1f) {
        val lengthScale = if (speed.isFinite() && speed > 0f) 1f / speed else 1f
        queueAndWait { tts.speak(text, speakerId, lengthScale) }
    }

    /** Stops playback now and drops what is queued; pending [speak] calls return right away. */
    suspend fun stopSpeaking() = withContext(Dispatchers.IO) {
        cancelSpeakWaits()
        tts.stopSpeaking()
    }

    /**
     * Queues a WAV or MP3 file. With [markAsLast] it returns once the queue is empty;
     * without, it returns at once (the file does not end the queue).
     */
    suspend fun playWav(path: String, markAsLast: Boolean = true) {
        val file = File(path.removePrefix("file://"))
        require(file.isFile) { "WAV file does not exist at path: ${file.absolutePath}" }
        if (markAsLast) queueAndWait { tts.playWav(file, true) }
        else withContext(Dispatchers.IO) { tts.playWav(file, false) }
    }

    /** Queues 16-bit PCM (e.g. from another TTS provider); see [playWav] for [markAsLast]. */
    suspend fun playPCM(data: ShortArray, sampleRate: Int, channels: Int = 1, interleaved: Boolean = true,
                        markAsLast: Boolean = true) =
        playMono(PcmMix.toMono(data, channels, interleaved), sampleRate, markAsLast)

    /** Queues float PCM in -1..1; see [playWav] for [markAsLast]. */
    suspend fun playPCM(data: FloatArray, sampleRate: Int, channels: Int = 1, interleaved: Boolean = true,
                        markAsLast: Boolean = true) =
        playMono(PcmMix.toMono(data, channels, interleaved), sampleRate, markAsLast)

    /** Queues raw little-endian PCM bytes; see [playWav] for [markAsLast]. */
    suspend fun playBuffer(bytes: ByteArray, format: PcmFormat, sampleRate: Int, channels: Int = 1,
                           interleaved: Boolean = true, markAsLast: Boolean = true) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (format) {
            PcmFormat.I16 -> {
                require(bytes.size % 2 == 0) { "i16 payload length not multiple of 2" }
                playPCM(ShortArray(bytes.size / 2) { buffer.getShort() }, sampleRate, channels, interleaved, markAsLast)
            }
            PcmFormat.F32 -> {
                require(bytes.size % 4 == 0) { "f32 payload length not multiple of 4" }
                playPCM(FloatArray(bytes.size / 4) { buffer.getFloat() }, sampleRate, channels, interleaved, markAsLast)
            }
        }
    }

    // ================================================================================
    // Internals
    // ================================================================================

    private fun recognizer(): STT = stt ?: synchronized(this) {
        stt ?: STT(appContext, true).also {
            it.delegate = this
            stt = it
        }
    }

    // Same rule as the React Native bridge: `speakers` replaces the set, else
    // `onboardingJsonPath` (+ speakerId) adds one speaker, else the current set is kept.
    private fun startRecognizer(locale: String, onboardingJsonPath: String?, speakerId: String,
                                speakers: List<SpeakerItem>?) {
        if (!speakers.isNullOrEmpty()) {
            recognizer().startSpeech(locale, speakers.toNative())
        } else {
            recognizer().startSpeech(locale, onboardingJsonPath?.takeIf { it.isNotBlank() }, null,
                speakerId.ifBlank { DEFAULT_SPEAKER })
        }
    }

    private fun initTTSNow(model: String): Boolean {
        wireFinishedListener()
        return tts.initTTS(DaVoiceTTSInterface.Config(model.removePrefix("file://"), "en-US", "phonemes_dir"))
    }

    private suspend fun playMono(mono: FloatArray, sampleRate: Int, markAsLast: Boolean) {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        if (markAsLast) queueAndWait { tts.playBuffer(mono, sampleRate, true) }
        else withContext(Dispatchers.IO) { tts.playBuffer(mono, sampleRate, false) }
    }

    /** Sends one item to the native queue (in call order), then waits for the queue to drain. */
    private suspend fun queueAndWait(send: () -> Unit) {
        val epoch = ttsEpoch
        val spoken = CompletableDeferred<Unit>()
        sendLock.withLock {
            if (epoch != ttsEpoch) return
            withContext(Dispatchers.IO) { send() }
            if (epoch != ttsEpoch) return
            synchronized(speakWaiters) { speakWaiters += spoken }
        }
        // Safety net only: never wait forever for a lost callback.
        withTimeoutOrNull(SPEAK_WAIT_LIMIT_MS) { spoken.await() }
    }

    private fun releaseSpeakWaiters() {
        val waiters = synchronized(speakWaiters) { speakWaiters.toList().also { speakWaiters.clear() } }
        waiters.forEach { it.complete(Unit) }
    }

    private fun cancelSpeakWaits() {
        ttsEpoch++
        releaseSpeakWaiters()
    }

    private fun List<SpeakerItem>.toNative(): List<Map<String, String>> = map { mapOf("id" to it.id, "json" to it.json) }

    // ---- STTDelegate (called on library threads) --------------------------------

    override fun onSpeechStart() { _events.tryEmit(Event.Start) }

    override fun onSpeechRecognized() { _events.tryEmit(Event.Recognized) }

    override fun onSpeechEnd() { _events.tryEmit(Event.End) }

    override fun onSpeechError(code: Int, message: String) { _events.tryEmit(Event.Error(code, message)) }

    override fun onSpeechPartialResults(list: List<String>) {
        list.firstOrNull()?.let { _events.tryEmit(Event.Partial(it, list)) }
    }

    override fun onSpeechPartialResults(list: List<String>, speakerIds: List<String>, speakerId: String?) {
        list.firstOrNull()?.let { _events.tryEmit(Event.Partial(it, list, speakerIds, speakerId)) }
    }

    override fun onSpeechResults(best: String, all: List<String>) {
        _events.tryEmit(Event.Final(all.firstOrNull() ?: best, all))
    }

    override fun onSpeechResults(best: String, all: List<String>, speakerIds: List<String>, speakerId: String?) {
        _events.tryEmit(Event.Final(all.firstOrNull() ?: best, all, speakerIds, speakerId))
    }

    override fun onSpeechVolumeChanged(value: Float) { _events.tryEmit(Event.VolumeChanged(value)) }

    override fun onNewSpeechWAV(filePath: String) { _events.tryEmit(Event.NewSpeechWAV(filePath)) }

    companion object {
        const val DEFAULT_SPEAKER = "default"
        private const val SPEAK_WAIT_LIMIT_MS = 10 * 60 * 1000L
    }
}

/** Mixes interleaved or planar multi-channel PCM down to mono float (-1..1). */
internal object PcmMix {
    fun toMono(src: ShortArray, channels: Int, interleaved: Boolean): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = src.size / ch
        return FloatArray(frames) { f ->
            var acc = 0
            for (c in 0 until ch) acc += src[if (interleaved) f * ch + c else c * frames + f]
            acc / ch.toFloat() / 32768f
        }
    }

    fun toMono(src: FloatArray, channels: Int, interleaved: Boolean): FloatArray {
        val ch = channels.coerceAtLeast(1)
        if (ch == 1) return src
        val frames = src.size / ch
        return FloatArray(frames) { f ->
            var acc = 0f
            for (c in 0 until ch) acc += src[if (interleaved) f * ch + c else c * frames + f]
            acc / ch
        }
    }
}
