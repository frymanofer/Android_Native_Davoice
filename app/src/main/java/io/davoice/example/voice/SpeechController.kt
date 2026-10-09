package io.davoice.example.voice

import android.content.Context
import com.davoice.stt.STT
import com.davoice.stt.STTDelegate
import com.davoice.tts.DaVoiceTTSInterface
import com.davoice.tts.LicenseManager
import io.davoice.example.DemoConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speech-to-text + text-to-speech, the native equivalent of the React Native
 * package's `Speech` object (react-native-davoice/speech):
 *
 *  - [initAll] starts the recognizer (com.davoice.stt.STT) and loads a TTS model
 *    (com.davoice.tts.DaVoiceTTSInterface). TTS and STT coordinate on their own:
 *    the recognizer is paused while TTS plays, so the app never hears itself.
 *  - [pauseRecognition] / [unpauseRecognition] are the "lite" pause the demo uses
 *    around each spoken reply and while prompts are on screen.
 *  - [speak] returns only after the audio has finished playing.
 */
class SpeechController(context: Context) : STTDelegate {

    sealed interface Event {
        data object Start : Event
        data object End : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int, val message: String) : Event
    }

    private val appContext = context.applicationContext
    private val tts = DaVoiceTTSInterface(appContext)
    @Volatile private var stt: STT? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    // speak() calls run one at a time; each waits for its own "finished" callback.
    private val speakQueue = Mutex()
    @Volatile private var pendingSpeak: CompletableDeferred<Unit>? = null

    /** STT and TTS share one process-wide license. */
    fun setLicense(licenseKey: String): Boolean =
        tts.setLicenseKey(licenseKey) && LicenseManager.setLicenseKey(licenseKey)

    /**
     * Starts recognition and loads [modelPath] for TTS. With [speakerEnrollmentJson]
     * (from speaker-verification onboarding) STT only transcribes the enrolled speaker.
     */
    suspend fun initAll(modelPath: String, speakerEnrollmentJson: String?) = withContext(Dispatchers.IO) {
        val recognizer = stt ?: STT(appContext, true).also {
            it.delegate = this@SpeechController
            stt = it
        }
        recognizer.startSpeech(DemoConfig.STT_LOCALE, speakerEnrollmentJson, null)

        tts.setOnFinishedSpeakingListenerJava { pendingSpeak?.complete(Unit) }
        val ready = tts.initTTS(DaVoiceTTSInterface.Config(modelPath, "en-US", "phonemes_dir"))
        check(ready) { "TTS init failed for $modelPath" }
    }

    /** Releases TTS and STT; call [initAll] again to use speech after this. */
    suspend fun destroyAll() = withContext(Dispatchers.IO) {
        pendingSpeak?.complete(Unit)
        runCatching { tts.destroy() }
        stt?.destroySpeech()
        stt = null
    }

    /** Bounded (~500 ms) inside the library; a no-op before [initAll]. */
    suspend fun pauseRecognition(): Boolean = withContext(Dispatchers.IO) {
        stt?.pauseSpeechRecognitionLite() ?: true
    }

    /**
     * `times = -1` releases every outstanding pause. Returns false if the
     * recognizer didn't confirm it restarted within [timeoutMs].
     */
    suspend fun unpauseRecognition(times: Int = -1, timeoutMs: Long = 1500): Boolean {
        val recognizer = stt ?: return true
        val work = background.async { recognizer.unPauseSpeechRecognitionLite(times) }
        return withTimeoutOrNull(timeoutMs) { work.await() } ?: false
    }

    fun isRecognizing(): Boolean = stt?.isRecognizing() == true

    suspend fun stopRecognition() = withContext(Dispatchers.IO) { stt?.stopSpeech() }

    suspend fun cancelRecognition() = withContext(Dispatchers.IO) { stt?.cancelSpeech() }

    /** For ttsv2 (multi-voice) models: 0 if [name] matched, -1 otherwise. */
    suspend fun changeVoice(name: String): Int = withContext(Dispatchers.IO) {
        runCatching { tts.changeVoice(name) }.getOrDefault(-1)
    }

    /**
     * Speaks [text] and suspends until playback ends. [speed] is a playback-speed
     * multiplier (1.0 = normal); the engine takes its inverse (a length scale).
     */
    suspend fun speak(text: String, speed: Float = 1f) = speakQueue.withLock {
        val finished = CompletableDeferred<Unit>()
        pendingSpeak = finished
        try {
            val lengthScale = if (speed.isFinite() && speed > 0f) 1f / speed else 1f
            withContext(Dispatchers.IO) { tts.speak(text, DemoConfig.SPEAKER_ID, lengthScale) }
            // Safety net only: never wait forever for a lost callback.
            withTimeoutOrNull(10 * 60 * 1000L) { finished.await() }
        } finally {
            if (pendingSpeak === finished) pendingSpeak = null
        }
    }

    /** Stops playback now; a pending [speak] returns right away. */
    suspend fun stopSpeaking() = withContext(Dispatchers.IO) {
        pendingSpeak?.complete(Unit)
        tts.stopSpeaking()
    }

    // ---- STTDelegate (called on library threads) --------------------------------

    override fun onSpeechStart() { _events.tryEmit(Event.Start) }

    override fun onSpeechEnd() { _events.tryEmit(Event.End) }

    override fun onSpeechError(code: Int, message: String) { _events.tryEmit(Event.Error(code, message)) }

    override fun onSpeechPartialResults(list: List<String>) {
        list.firstOrNull()?.let { _events.tryEmit(Event.Partial(it)) }
    }

    override fun onSpeechResults(best: String, all: List<String>) {
        (all.firstOrNull() ?: best).let { _events.tryEmit(Event.Final(it)) }
    }

    override fun onSpeechRecognized() {}
    override fun onSpeechVolumeChanged(value: Float) {}
    override fun onNewSpeechWAV(filePath: String) {}
}
