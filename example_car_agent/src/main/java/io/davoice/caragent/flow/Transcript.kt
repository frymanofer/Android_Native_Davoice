package io.davoice.caragent.flow

import io.davoice.caragent.util.re
import io.davoice.caragent.util.replaceAllLit
import io.davoice.caragent.util.replaceAllMapped

// Speech text helpers (ports of the Flutter / React Native apps' transcript, fragments and sentence splitting).

private val fillers = setOf(
    "the", "a", "an", "and", "but", "or", "so", "then", "um", "uh", "uhm", "hmm", "mm", "oh", "ah", "eh", "boy", "well", "like", "just",
    "o", "os", "as", "e", "é", "mas", "ou", "então", "entao", "tipo", "né", "ne", "hã", "ha", "hum", "ahn", "ué", "ue", "peraí", "perai", "bom",
)

private fun fragmentWords(text: String): List<String> =
    text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s']"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

/** True for a transcript that is only noise: filler words, or one word said twice ("Alma Alma."). */
fun isNoiseFragment(text: String): Boolean {
    val words = fragmentWords(text)
    if (words.isEmpty()) return true
    if (words.size > 2 || words.any { Regex("\\d").containsMatchIn(it) }) return false
    if (words.all { it in fillers }) return true
    return words.size == 2 && words[0] == words[1]
}

/** A very short request (two words or fewer, no number): too little to be a real "can't do that". */
fun isTooShortForDecline(text: String): Boolean {
    val words = fragmentWords(text)
    return words.size <= 2 && words.none { Regex("\\d").containsMatchIn(it) }
}

private val bareDone = re("^\\s*(done|all set|okay,? done|ok,? done|pronto|prontinho|feito|ok,? feito|tudo certo)\\s*[.!]?\\s*$", ignoreCase = true)

/** A bare "Done." (en / pt): in the car demo the app's own sentence confirms every real action, so "Done." alone means nothing ran. */
fun isBareDone(sentence: String): Boolean = bareDone.containsMatchIn(sentence)

/** Partial / final speech results merged into one running transcript (the same overlap and punctuation rules as the other apps). */
fun mergeTranscript(previous: String, current: String): String {
    val prev = previous.trim()
    val curr = current.trim()
    if (prev.isEmpty()) return curr
    if (curr.isEmpty()) return prev
    if (curr.startsWith(prev)) return curr
    if (prev.startsWith(curr)) return prev
    fun normalize(t: String) = t.lowercase().replace(Regex("[^A-Za-z0-9\\s]+"), "").replace(Regex("\\s+"), " ").trim()
    val np = normalize(prev)
    val nc = normalize(curr)
    if (nc.startsWith(np)) return curr
    val words = Regex("[A-Za-z0-9]+")
    val pw = words.findAll(prev).map { it.value.lowercase() }.toList()
    val spans = words.findAll(curr).toList()
    val cw = spans.map { it.value.lowercase() }
    for (k in minOf(pw.size, cw.size) downTo 2) {
        if ((0 until k).all { pw[pw.size - k + it] == cw[it] }) {
            val tail = curr.substring(spans[k - 1].range.last + 1)
            var left = prev
            val last = Regex("[?!]+$").find(prev)
            val first = Regex("^[?!]+").find(tail)
            if (last != null && first != null && last.value[0] == first.value[0]) left = prev.substring(0, last.range.first)
            val space = Regex("[A-Za-z0-9]$").containsMatchIn(left) && Regex("^[A-Za-z0-9]").containsMatchIn(tail)
            return "$left${if (space) " " else ""}$tail"
        }
    }
    return if (nc.length >= np.length) curr else prev
}

/** Model output as it is read aloud: no markdown, sentence boundaries kept. */
fun normalizeTextForSpeech(input: String): String {
    val text = input
        .replaceAllLit(re("^\\s*(?:[-*•]|\\d+[.)])\\s+", multiLine = true), "")
        .replaceAllMapped(Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")) { it.groups[1]?.value ?: it.groups[2]?.value ?: "" }
        .replaceAllMapped(re("(^|\\s)[*_](\\S(?:[^*_\\n]*\\S)?)[*_](?=\\s|[.,!?:;]|$)", multiLine = true)) { "${it.groups[1]?.value ?: ""}${it.groups[2]?.value ?: ""}" }
        .replaceAllLit(re("^#+\\s*", multiLine = true), "")
    return text
        .replaceAllMapped(Regex("\\s*\\n+\\s*")) { m ->
            val before = text.substring(0, m.range.first).trimEnd()
            val after = text.substring(m.range.last + 1).trimStart()
            if (Regex("[.!?,:;]$").containsMatchIn(before) || Regex("^[.!?,:;]").containsMatchIn(after)) " " else ". "
        }
        .replaceAllMapped(Regex("\\s+([.!?,:;])")) { it.groupValues[1] }
        .replaceAllMapped(Regex("([.!?,:;]){2,}")) { it.groupValues[1] }
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

/** Finished sentences and the unfinished rest, so speech starts on the first sentence while the model still writes. */
fun splitCompletedSentences(text: String): Pair<List<String>, String> {
    val completed = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] !in ".!?") {
            index++
            continue
        }
        var boundary = index + 1
        while (boundary < text.length && text[boundary] in "\"')]") boundary++
        while (boundary < text.length && text[boundary].isWhitespace()) boundary++
        val sentence = text.substring(start, boundary).trim()
        if (sentence.isNotEmpty()) completed += sentence
        start = boundary
        index = boundary
    }
    return completed to text.substring(start)
}
