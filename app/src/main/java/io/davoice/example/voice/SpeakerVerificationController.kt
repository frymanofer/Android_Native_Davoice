package io.davoice.example.voice

import android.content.Context
import com.davoice.keywordsdetection.keywordslibrary.SpeakerVerification
import io.davoice.example.DemoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executors

/**
 * Speaker verification (com.davoice.keywordsdetection.keywordslibrary.SpeakerVerification):
 * enroll a speaker from the microphone, save the profile, and verify live.
 * The resulting enrollment is also handed to the wake word (fires only for that
 * speaker) and to STT (transcribes only that speaker).
 */
class SpeakerVerificationController(context: Context, private val assets: AssetInstaller) {

    private val appContext = context.applicationContext
    // All mic-controller calls on one thread, as the library expects.
    private val svThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private sealed interface Event {
        data class Progress(val collected: Int, val target: Int) : Event
        data class Done(val enrollmentJson: String?) : Event
        data class Verify(val score: Float, val isMatch: Boolean) : Event
        data class Failure(val message: String) : Event
    }

    val enrollmentFile: File get() = File(appContext.filesDir, DemoConfig.SV_ENROLLMENT_FILE)

    fun loadSavedEnrollment(): String? =
        enrollmentFile.takeIf { it.exists() }?.readText()?.takeIf { it.length >= 10 }

    fun saveEnrollment(json: String): String {
        enrollmentFile.writeText(json)
        return enrollmentFile.absolutePath
    }

    private fun config() = SpeakerVerification.SpeakerVerificationConfig().apply {
        modelPath = assets.path(DemoConfig.SV_MODEL_ASSET)
        decisionThreshold = DemoConfig.SV_DECISION_THRESHOLD
        tailSeconds = 2.0f
        maxTailSeconds = 3.0f
        frameSize = 1280
        sampleRate = 16000
        cmn = true
        expectedLayoutBDT = false
    }

    private fun newController(events: Channel<Event>) =
        SpeakerVerification.SpeakerVerificationMicController(appContext, config()).apply {
            delegate = object : SpeakerVerification.SpeakerVerificationNativeDelegate {
                override fun svOnboardingProgress(info: MutableMap<String, Any>?) {
                    events.trySend(Event.Progress(info.int("collected"), info.int("target")))
                }
                override fun svOnboardingDone(info: MutableMap<String, Any>?) {
                    val json = (info?.get("enrollmentJson") ?: info?.get("enrollment") ?: info?.get("json")) as? String
                    events.trySend(Event.Done(json))
                }
                override fun svVerifyResult(info: MutableMap<String, Any>?) {
                    val score = (info?.get("scoreBest") ?: info?.get("bestScore") ?: info?.get("score")) as? Number
                    events.trySend(Event.Verify(score?.toFloat() ?: Float.NaN, info?.get("isMatch") == true))
                }
                override fun svError(info: MutableMap<String, Any>?) {
                    events.trySend(Event.Failure(info?.get("error")?.toString() ?: info.toString()))
                }
            }
        }

    /**
     * Records [samples] voice samples and returns the enrollment JSON.
     * [onProgress] gets (collected, target) after each sample.
     */
    suspend fun enroll(
        samples: Int = DemoConfig.SV_ONBOARDING_SAMPLE_COUNT,
        onProgress: (Int, Int) -> Unit,
    ): String {
        val events = Channel<Event>(Channel.UNLIMITED)
        val ctrl = withContext(svThread) { newController(events) }
        try {
            withContext(svThread) { ctrl.beginOnboarding("davoice", samples, true) }
            var collected = 0
            var enrollmentJson: String? = null
            var done = false
            for (i in 1..samples) {
                if (done) break
                val before = collected
                withContext(svThread) { ctrl.getNextEmbeddingFromMic() }
                // Wait for this sample to land (or onboarding to complete).
                withTimeout(30_000) {
                    while (true) {
                        when (val e = events.receive()) {
                            is Event.Progress -> {
                                collected = e.collected
                                onProgress(e.collected, e.target)
                                if (e.collected > before) break
                            }
                            is Event.Done -> { enrollmentJson = e.enrollmentJson; done = true; break }
                            is Event.Failure -> error("Speaker verification error: ${e.message}")
                            is Event.Verify -> Unit
                        }
                    }
                }
            }
            if (!done) {
                withTimeout(60_000) {
                    while (true) {
                        when (val e = events.receive()) {
                            is Event.Done -> { enrollmentJson = e.enrollmentJson; break }
                            is Event.Failure -> error("Speaker verification error: ${e.message}")
                            else -> Unit
                        }
                    }
                }
            }
            val json = enrollmentJson?.takeIf { it.length >= 10 }
                ?: error("Onboarding finished without a valid enrollment")
            onProgress(samples, samples)
            return json
        } finally {
            withContext(svThread) { runCatching { ctrl.stop() } }
        }
    }

    /** A running live verification; call [stop] to release the microphone. */
    class Verification internal constructor(private val stopAction: suspend () -> Unit) {
        suspend fun stop() = stopAction()
    }

    /**
     * Verifies continuously from the mic. [onScore] gets (displayScore, isMatch)
     * every ~0.25 s on a background thread. A match keeps showing as a match for
     * [DemoConfig.SV_MATCH_HOLD_MS] so brief dips between words don't flicker.
     */
    suspend fun startLiveVerification(
        enrollmentJson: String,
        onScore: (Float, Boolean) -> Unit,
    ): Verification {
        val events = Channel<Event>(Channel.UNLIMITED)
        val ctrl = withContext(svThread) {
            newController(events).also { it.setEnrollmentJson(enrollmentJson) }
        }
        val listener = scope.launch {
            var holdUntil = 0L
            val recent = ArrayDeque<Pair<Long, Float>>()
            for (e in events) {
                if (e !is Event.Verify) continue
                val now = System.currentTimeMillis()
                val inHoldBefore = now < holdUntil
                if (e.isMatch && !inHoldBefore) recent.clear()
                if (e.score.isFinite() && (e.isMatch || inHoldBefore)) recent.addLast(now to e.score)
                while (recent.isNotEmpty() && now - recent.first().first > DemoConfig.SV_MATCH_HOLD_MS) recent.removeFirst()
                if (e.isMatch) holdUntil = now + DemoConfig.SV_MATCH_HOLD_MS
                val showAsMatch = e.isMatch || now < holdUntil
                val score = if (showAsMatch && recent.isNotEmpty()) recent.maxOf { it.second } else e.score
                onScore(displayScore(score), isMatch(score))
            }
        }
        withContext(svThread) {
            ctrl.startEndlessVerifyFromMic(DemoConfig.SV_VERIFY_HOP_SECONDS, false, true)
        }
        return Verification {
            listener.cancel()
            withContext(svThread) { runCatching { ctrl.stop() } }
        }
    }

    companion object {
        fun isMatch(score: Float) = score.isFinite() && score >= DemoConfig.SV_DECISION_THRESHOLD

        /** Same presentation as the React Native demo: matches read as 0.9xx. */
        fun displayScore(score: Float): Float {
            if (!isMatch(score)) return score
            val lastTwo = String.format(java.util.Locale.US, "%.3f", score).takeLast(2)
            return "0.9$lastTwo".toFloat()
        }

        private fun Map<String, Any>?.int(key: String) = (this?.get(key) as? Number)?.toInt() ?: 0
    }
}
