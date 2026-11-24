package io.davoice.caragent.agent

import io.davoice.caragent.car.BasicLanguage
import io.davoice.caragent.util.Obj

// Local confidence (agent core configure "confidence"): an application-developer choice, adjustable at runtime. The on-device model's
// tool call carries a score (the product of the probabilities of the tokens it wrote). With the filter ON, a call scoring below the
// threshold does not run, and the fallback decides what happens instead (clarify: the user is asked to say it again; remote: the
// request goes to the text-only remote assistant; decline: the user hears that nothing was changed). With the filter OFF the threshold
// is ignored. Either way the core's deterministic checks still apply. The score is model-derived, NOT a guaranteed probability of
// being right. Same behaviour as the React Native and Flutter apps.

val confidenceFallbacks = listOf("clarify", "remote", "decline")
val confidenceFallbackLabels = mapOf("clarify" to "Clarify", "remote" to "Remote", "decline" to "Decline")
val confidenceFallbackDetails = mapOf(
    "clarify" to "ask the driver to say it again",
    "remote" to "text-only answer, never a car action",
    "decline" to "say nothing was changed",
)
const val CONFIDENCE_STEP = 0.05

data class ConfidenceSettings(val enabled: Boolean = false, val threshold: Double = 0.9, val fallback: String = "clarify") {
    fun toJson(): Obj = mapOf("enabled" to enabled, "threshold" to threshold, "fallback" to fallback)

    companion object {
        val defaults = ConfidenceSettings()

        fun parse(raw: Any?): ConfidenceSettings {
            if (raw is Map<*, *>) {
                val t = raw["threshold"]
                val f = raw["fallback"]
                return ConfidenceSettings(
                    enabled = raw["enabled"] == true,
                    threshold = if (t is Number) snapThreshold(t.toDouble()) else defaults.threshold,
                    fallback = if (f in confidenceFallbacks) f as String else defaults.fallback,
                )
            }
            return defaults
        }
    }
}

/** Rounds to the 0.05 grid and clamps to 0..1 (no float noise: 0.9 stays 0.9). */
fun snapThreshold(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) return ConfidenceSettings.defaults.threshold
    val v = value.coerceIn(0.0, 1.0)
    return Math.round(v / CONFIDENCE_STEP) * 5 / 100.0
}

/** "0.90 / 90%" */
fun formatThreshold(value: Double): String {
    val v = snapThreshold(value)
    return "${String.format(java.util.Locale.US, "%.2f", v)} / ${Math.round(v * 100)}%"
}

private val confidenceMessages = mapOf(
    "en" to mapOf(
        "clarify" to "I'm not sure I understood that. Could you say it again in other words?",
        "decline" to "I'm not sure enough about that one, so I didn't change anything.",
        "noRemote" to "I'm not sure enough to do that here, and no remote assistant is set up, so I didn't change anything.",
    ),
    "pt-BR" to mapOf(
        "clarify" to "Não tenho certeza se entendi. Pode repetir de outro jeito?",
        "decline" to "Não tenho certeza suficiente sobre esse comando, então não mudei nada.",
        "noRemote" to "Não tenho certeza suficiente para fazer isso aqui e não há um assistente remoto configurado, então não mudei nada.",
    ),
)

/** What the agent core gets (configure {"confidence": ...}); the threshold is sent even when OFF, so switching ON restores it. */
fun toCoreConfidence(s: ConfidenceSettings, language: String): Obj = mapOf(
    "enabled" to s.enabled,
    "min" to snapThreshold(s.threshold),
    "fallback" to s.fallback,
    "messages" to (if (language.lowercase().startsWith("pt")) confidenceMessages["pt-BR"]!! else confidenceMessages["en"]!!).mapValues { (k, v) ->
        // Basic languages have their own sentences
        BasicLanguage.message(language, mapOf("clarify" to "lowClarify", "decline" to "lowDecline", "noRemote" to "lowNoRemote")[k]!!) ?: v
    },
)

val outcomeLabels = mapOf(
    "executed" to "Executed",
    "clarified" to "Clarified",
    "declined" to "Declined",
    "remote" to "Routed to remote",
    "ignored" to "Ignored",
    "answered" to "Answered",
)

/** What happened to a request and the model's score for its action (core result "outcome" / "confidence"). */
data class TurnInfo(val outcome: String? = null, val value: Double? = null, val low: Boolean = false, val unavailable: Boolean = false, val measured: Boolean = false) {
    /** "Model score 0.93 (93%)" / "Model score unavailable" / "" */
    val score: String
        get() {
            if (!measured) return ""
            val v = value ?: return if (unavailable) "Model score unavailable" else ""
            return "Model score ${String.format(java.util.Locale.US, "%.2f", v)} (${Math.round(v * 100)}%)"
        }

    companion object {
        fun read(json: Obj): TurnInfo {
            val o = json["outcome"]
            val c = json["confidence"]
            val outcome = if (o is String && o in outcomeLabels) o else null
            if (c is Map<*, *>) {
                val v = c["value"]
                return TurnInfo(outcome, (v as? Number)?.toDouble(), c["low"] == true, c["unavailable"] == true, measured = true)
            }
            return TurnInfo(outcome)
        }
    }
}
