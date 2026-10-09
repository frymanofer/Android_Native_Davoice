package io.davoice.example.chat

/** Text clean-up for replies that are read aloud. */
object SpokenText {

    /** The demo voices don't say the brand name. */
    fun applyTtsPolicy(text: String): String =
        text.replace(Regex("\\bDaVoice's\\b"), "the voice").replace(Regex("\\bDaVoice\\b"), "The Voice")

    /** Turns line breaks into sentence breaks and tidies punctuation/spacing. */
    fun normalize(text: String): String {
        val newlines = Regex("\\s*\\n+\\s*")
        val builder = StringBuilder()
        var last = 0
        for (match in newlines.findAll(text)) {
            builder.append(text, last, match.range.first)
            val before = text.substring(0, match.range.first).trimEnd()
            val after = text.substring(match.range.last + 1).trimStart()
            val boundary = before.lastOrNull()?.let { it in ".!?,:;" } == true || after.firstOrNull()?.let { it in ".!?,:;" } == true
            builder.append(if (boundary) " " else ". ")
            last = match.range.last + 1
        }
        builder.append(text.substring(last))
        return builder.toString()
            .replace(Regex("\\s+([.!?,:;])"), "$1")
            .replace(Regex("([.!?,:;]){2,}"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /**
     * Splits streamed text into finished sentences (ending in . ! or ?) and the
     * unfinished remainder, so speech can start on the first sentence.
     */
    fun splitCompletedSentences(text: String): Pair<List<String>, String> {
        val completed = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] !in ".!?") { i++; continue }
            var boundary = i + 1
            while (boundary < text.length && text[boundary] in "\"')]") boundary++
            while (boundary < text.length && text[boundary].isWhitespace()) boundary++
            text.substring(start, boundary).trim().takeIf { it.isNotEmpty() }?.let(completed::add)
            start = boundary
            i = boundary
        }
        return completed to text.substring(start)
    }
}
