package io.davoice.caragent.car

import io.davoice.caragent.util.re

// What to play = the driver's own words when the model copied them wrong ("Play Pretty Woman" -> play_media {query: "Perma Woman"};
// "Can you play pretty woman on the radio please?" -> "woman"). Port of the Flutter / React Native car apps' media query; shared
// fixture media_query.json.

// the words before the title (the first one: a title may hold one, "Play Let Me Play")
private val playVerb = re("(?<!\\p{L})(?:play|put on|listen to|toca|toque|tocar|coloca|coloque|bota|poe|põe|quero ouvir|ouvir)(?!\\p{L})", ignoreCase = true)

// words after the title that are not part of it
private val trailing = re(
    "(?:\\s+(?:on the radio|on spotify|in the car|for me|please|now|right now|no radio|no rádio|no spotify|pra mim|para mim|por favor|agora))+\\s*$",
    ignoreCase = true,
)

// words a copy may leave out or add without changing what is played
private val small = setOf("a", "o", "as", "os", "the", "some", "um", "uma", "me", "de", "do", "da", "song", "musica", "cancao")

private val accents = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'ê' to 'e', 'è' to 'e', 'ë' to 'e', 'í' to 'i', 'ì' to 'i', 'î' to 'i',
    'ï' to 'i', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ò' to 'o', 'ö' to 'o', 'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
)

private fun norm(w: String): String = w.lowercase().map { accents[it] ?: it }.joinToString("").replace(Regex("[^a-z0-9]"), "")
private fun words(s: String): List<String> = s.split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }

/** The title the driver said after the play verb (their own spelling), or null. */
fun spokenTitle(userText: String): String? {
    val m = playVerb.find(userText) ?: return null
    var span = userText.substring(m.range.last + 1)
    span = Regex("[?.!]+\\s*$").find(span)?.let { span.removeRange(it.range) } ?: span
    span = trailing.find(span)?.let { span.removeRange(it.range) } ?: span
    span = span.replace(Regex("^[\\s,]+|[\\s,]+$"), "")
    return if (words(span).isEmpty()) null else span
}

private fun titleCase(s: String): String =
    if (s == s.lowercase()) Regex("(^|\\s)(\\p{L})").replace(s) { "${it.groupValues[1]}${it.groupValues[2].uppercase()}" } else s

/** The query to play: the model's, or the driver's words when the model's is a damaged copy of them. */
fun groundMediaQuery(query: String, userText: String): String {
    val span = spokenTitle(userText) ?: return query
    val q = words(query).filter { it !in small }
    val s = words(span).filter { it !in small }
    if (q.isEmpty() || s.isEmpty() || q.joinToString(" ") == s.joinToString(" ")) return query
    val exact = q.count { it in s }
    if (exact == 0) return query // not a copy of what was said
    // (a) words left out: every query word was said, some said words are missing ("woman" for "pretty woman")
    val leftOut = exact == q.size && s.size > q.size
    // (b) words misspelt: as many words, at least half exact, the others start with the same letter ("Perma Woman")
    var misspelt = q.size == s.size && exact * 2 >= q.size
    var i = 0
    while (misspelt && i < q.size) {
        if (q[i] !in s && q[i][0] != s[i][0]) misspelt = false
        i++
    }
    return if (leftOut || misspelt) titleCase(span) else query
}
