package io.davoice.caragent.flow

import io.davoice.caragent.car.BasicLanguage

// When a pause ends the driver's request (2026-11-25 iPhone log: "Look at", "Please", "Yeah, it's" were sent after the
// end-of-speech silence while the driver was still talking). Words that cannot end a request ("Set the", "Open", "Can you",
// "Please") get a longer pause before they are sent. The same rule as the React Native car agent (src/stt/endOfSpeech.ts).

/** Extra wait when the words so far cannot end a request (ms). */
const val UNFINISHED_EXTRA_MS = 2000

private class EndWords(val last: Set<String>, val lastTwo: Set<String>, val alone: Set<String>)

private val EN = EndWords(
    setOf("the", "a", "an", "to", "at", "of", "on", "in", "into", "onto", "for", "with", "from", "by", "about", "and", "or", "but",
        "my", "your", "our", "their", "his", "her", "its", "is", "are", "was", "can", "could", "would", "will", "should", "please", "than"),
    setOf("can you", "could you", "would you", "will you", "it s", "that s", "there s", "i want", "i d like", "i would like",
        "set the", "turn the", "turn on", "turn off", "turn up", "turn down"),
    setOf("open", "close", "shut", "set", "turn", "put", "make", "play", "lock", "unlock", "raise", "lower", "increase", "decrease",
        "navigate", "take", "find", "switch", "tune", "change", "hey", "so", "um", "uh", "and"),
)

private val PT = EndWords(
    setOf("o", "a", "os", "as", "de", "do", "da", "dos", "das", "no", "na", "nos", "nas", "em", "para", "pra", "pro", "por", "com",
        "e", "ou", "mas", "um", "uma", "meu", "minha", "seu", "sua", "que", "ao", "pelo", "pela"),
    setOf("voce pode", "vc pode", "por favor", "eu quero", "eu queria", "liga o", "liga a"),
    setOf("abre", "fecha", "coloca", "poe", "bota", "aumenta", "diminui", "abaixa", "baixa", "sobe", "toca", "muda", "troca",
        "tranca", "destranca", "navega", "leva", "ei", "entao", "e"),
)

/** The words so far cannot end a request ("Set the", "Can you", "Please"). Languages other than English / Portuguese: never. */
fun looksUnfinished(text: String, language: String): Boolean {
    val words = BasicLanguage.fold(text).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val set = when {
        language == "en" -> EN
        language.startsWith("pt") -> PT
        else -> return false
    }
    val last = words.last()
    if (words.size == 1 && (last in set.alone || (set === EN && last == "please"))) return true
    if (words.size >= 2 && "${words[words.size - 2]} $last" in set.lastTwo) {
        // "por favor" ends many requests ("Fecha a janela, por favor"): unfinished only when it is all that was said
        return !(set === PT && last == "favor" && words.size > 2)
    }
    if (last == "please") return false   // "yes please" / "close it please"
    return last in set.last
}

/** The silence that ends this request: the end-of-speech setting, longer when the words so far cannot end a request. */
fun silenceFor(text: String, baseMs: Int, language: String): Int =
    if (looksUnfinished(text, language)) baseMs + UNFINISHED_EXTRA_MS else baseMs
