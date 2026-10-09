package io.davoice.example.voice

/**
 * Android's recognizer restarts its hypothesis after short pauses, so partial
 * results don't always extend the previous one. This joins them into one
 * running transcript while keeping punctuation (port of the React Native
 * demo's mergeSmartKeepPunct).
 */
object TranscriptMerger {

    private val wordRegex = Regex("[A-Za-z0-9]+")

    private data class Span(val word: String, val end: Int)

    private fun wordSpans(s: String) = wordRegex.findAll(s).map { Span(it.value.lowercase(), it.range.last + 1) }.toList()

    private fun stripPunctuation(s: String) =
        s.lowercase().replace(Regex("[^A-Za-z0-9\\s]+"), "").replace(Regex("\\s+"), " ").trim()

    /** Largest k where the last k words of [a] equal the first k words of [b]. */
    private fun overlap(a: List<String>, b: List<String>): Int {
        for (k in minOf(a.size, b.size) downTo 1) {
            if ((0 until k).all { a[a.size - k + it] == b[it] }) return k
        }
        return 0
    }

    fun merge(previous: String, current: String, minOverlap: Int = 2): String {
        val prev = previous.trim()
        val curr = current.trim()
        if (prev.isEmpty()) return curr
        if (curr.isEmpty()) return prev

        if (curr.startsWith(prev)) return curr // normal growth
        if (prev.startsWith(curr)) return prev // regression: keep the longer one

        val normalizedPrev = stripPunctuation(prev)
        val normalizedCurr = stripPunctuation(curr)
        if (normalizedCurr.startsWith(normalizedPrev)) return curr

        val currSpans = wordSpans(curr)
        val k = overlap(wordSpans(prev).map { it.word }, currSpans.map { it.word })
        if (k >= minOverlap) {
            val tail = curr.substring(currSpans[k - 1].end)
            // Don't double a ?/! run that both sides end/start with.
            val prevRun = Regex("[?!]+$").find(prev)?.value
            val tailRun = Regex("^[?!]+").find(tail)?.value
            val left = if (prevRun != null && tailRun != null && prevRun[0] == tailRun[0]) {
                prev.dropLast(prevRun.length)
            } else prev
            val needSpace = left.isNotEmpty() && left.last().isLetterOrDigit() && tail.firstOrNull()?.isLetterOrDigit() == true
            return if (needSpace) "$left $tail" else left + tail
        }
        return if (normalizedCurr.length >= normalizedPrev.length) curr else prev
    }
}
