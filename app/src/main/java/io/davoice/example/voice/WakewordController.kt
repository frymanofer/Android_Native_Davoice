package io.davoice.example.voice

import android.content.Context
import android.util.Log
import com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection
import com.davoice.keywordsdetection.keywordslibrary.WakewordAudioRoutingConfig
import io.davoice.example.WakeWordModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Always-listening wake word (com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection).
 * One detector can run several wake-word models at once.
 */
class WakewordController(context: Context) {

    private val appContext = context.applicationContext
    private var detector: KeyWordsDetection? = null
    private var models: List<WakeWordModel> = emptyList()

    /** Called on a library thread with the detected model's file name. */
    var onDetected: ((modelName: String) -> Unit)? = null

    /** Shared routing JSON (see DemoConfig.AUDIO_ROUTING_CONFIG_JSON). Set before [create]. */
    fun setAudioRoutingConfig(json: String) {
        WakewordAudioRoutingConfig.setFromJson(json)
        detector?.refreshWakewordAudioConfig()
    }

    /** Loads the models (from app assets, by file name). */
    suspend fun create(models: List<WakeWordModel>) = withContext(Dispatchers.IO) {
        this@WakewordController.models = models
        detector = KeyWordsDetection(
            appContext,
            models.map { it.assetName }.toTypedArray(),
            models.map { it.threshold }.toFloatArray(),
            models.map { it.bufferCount }.toIntArray(),
            models.map { it.msBetweenCallbacks }.toLongArray(),
        ).also { kwd ->
            kwd.initialize { detected, modelName ->
                Log.d(TAG, "callback detected=$detected model=$modelName")
                if (detected) onDetected?.invoke(modelName)
            }
        }
    }

    fun setLicense(licenseKey: String): Boolean = detector?.setLicenseKey(licenseKey) == true

    /**
     * (Re)starts listening. With [speakerEnrollmentPath] the wake word only
     * fires for the enrolled speaker. Starts paused so the app decides when
     * detection begins; call [resume].
     */
    suspend fun start(speakerEnrollmentPath: String? = null) = withContext(Dispatchers.IO) {
        val kwd = requireNotNull(detector) { "create() first" }
        runCatching { kwd.stopListening() }
        val threshold = models.first().threshold
        Log.d(TAG, "startListening(sv=${!speakerEnrollmentPath.isNullOrEmpty()})")
        if (speakerEnrollmentPath.isNullOrEmpty()) {
            kwd.startListening(threshold)
        } else {
            kwd.startListening(threshold, speakerEnrollmentPath)
        }
        kwd.pauseDetection(false)
        delay(100)
    }

    /** Pauses detection; with [stopMic] false the microphone stays open (faster resume). */
    suspend fun pause(stopMic: Boolean = false) = withContext(Dispatchers.IO) {
        Log.d(TAG, "pauseDetection(stopMic=$stopMic)")
        detector?.pauseDetection(stopMic)
    }

    suspend fun resume() = withContext(Dispatchers.IO) {
        Log.d(TAG, "unPauseDetection()")
        runCatching { detector?.unPauseDetection() }.onFailure { Log.e(TAG, "unPauseDetection failed", it) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        Log.d(TAG, "stopListening()")
        detector?.stopListening()
    }

    /** WAV files holding the audio that triggered the last detection. */
    fun lastRecordings(): List<String> {
        val kwd = detector ?: return emptyList()
        val many = runCatching { kwd.recordingWavArray?.toList() }.getOrNull().orEmpty()
        val one = runCatching { kwd.recordingWav }.getOrNull()
        return (many.ifEmpty { listOfNotNull(one) }).filter { it.isNotEmpty() }
    }
}

private const val TAG = "DemoWakeword"
