package io.davoice.caragent.flow

// The app's languages (the same list, order and texts as the React Native and Flutter example_car_agent).

/** One language of the language screen: STT locale, the agent's reply instruction, and whether it is offered. */
data class AppLanguageInfo(val id: String, val label: String, val sttLocale: String, val llmReplyInstruction: String, val hidden: Boolean = false)

val appLanguageInfos = listOf(
    // Order = voice quality: English; Spanish, Italian, German, Dutch; Brazilian Portuguese; Greek, Swedish, Danish; French, European
    // Portuguese. Hebrew is hidden.
    AppLanguageInfo("en", "English", "en-US", "The user speaks English. Always reply in English."),
    AppLanguageInfo("es", "Español (Spanish)", "es-ES", "The user speaks Spanish. Always reply in Spanish."),
    AppLanguageInfo("it", "Italiano (Italian)", "it-IT", "The user speaks Italian. Always reply in Italian."),
    AppLanguageInfo("de", "Deutsch (German)", "de-DE", "The user speaks German. Always reply in German."),
    AppLanguageInfo("nl", "Nederlands (Dutch)", "nl-NL", "The user speaks Dutch. Always reply in Dutch."),
    AppLanguageInfo("pt-BR", "Português do Brasil (Brazilian Portuguese)", "pt-BR", "Important: always reply in Brazilian Portuguese (pt-BR). Sempre responda em português do Brasil."),
    AppLanguageInfo("el", "Ελληνικά (Greek)", "el-GR", "The user speaks Greek. Always reply in Greek. Απάντα πάντα στα ελληνικά."),
    AppLanguageInfo("sv", "Svenska (Swedish)", "sv-SE", "The user speaks Swedish. Always reply in Swedish. Svara alltid på svenska."),
    AppLanguageInfo("da", "Dansk (Danish)", "da-DK", "The user speaks Danish. Always reply in Danish. Svar altid på dansk."),
    AppLanguageInfo("fr", "Français (French)", "fr-FR", "The user speaks French. Always reply in French."),
    AppLanguageInfo("pt-PT", "Português de Portugal (European Portuguese)", "pt-PT", "Important: always reply in European Portuguese (pt-PT), never Brazilian Portuguese. Responde sempre em português de Portugal."),
    AppLanguageInfo("he", "עברית (Hebrew)", "he-IL", "The user speaks Hebrew. Always reply in Hebrew. ענה תמיד בעברית.", hidden = true),
)

val appLanguages = appLanguageInfos.map { it.id }

/** A TTS v1 model's own voice (named after the language): no voice conversion, so much faster. The language's default voice. */
val originalTtsVoices = mapOf("da" to "Mads", "sv" to "Erik", "el" to "Eleni")

object AppLanguage {
    /** The app's language (an id of [appLanguageInfos]). */
    @Volatile var current = "en"
        private set

    fun set(language: String) {
        current = if (language in appLanguages) language else "en"
    }

    /** Portuguese (Brazil or Portugal). */
    val isPortuguese get() = current == "pt-BR" || current == "pt-PT"
}

fun appLanguageInfo(language: String): AppLanguageInfo = appLanguageInfos.firstOrNull { it.id == language } ?: appLanguageInfos.first()

/** The system-prompt line that sets the agent's reply language. */
fun llmReplyInstructionFor(language: String): String = appLanguageInfo(language).llmReplyInstruction
