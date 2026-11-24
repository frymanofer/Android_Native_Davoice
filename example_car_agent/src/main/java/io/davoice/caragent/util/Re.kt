package io.davoice.caragent.util

// Regex helpers with Dart's semantics (the car code is a port of the Flutter app's Dart): replacements are literal text (Kotlin's
// String.replace(Regex, String) reads "$1" in the replacement), and a group that did not take part is null.

fun re(pattern: String, ignoreCase: Boolean = false, dotAll: Boolean = false, multiLine: Boolean = false): Regex {
    val o = mutableSetOf<RegexOption>()
    if (ignoreCase) o += RegexOption.IGNORE_CASE
    if (dotAll) o += RegexOption.DOT_MATCHES_ALL
    if (multiLine) o += RegexOption.MULTILINE
    return Regex(pattern, o)
}

fun Regex.has(s: String): Boolean = containsMatchIn(s)

fun MatchResult.g(i: Int): String? = groups[i]?.value

fun MatchResult.gs(i: Int): String = groups[i]?.value ?: ""

/** Dart's replaceFirst(RegExp, String): the first match replaced by [lit] as it is. */
fun String.replaceFirstLit(r: Regex, lit: String): String = r.find(this)?.let { replaceRange(it.range, lit) } ?: this

/** Dart's replaceFirstMapped. */
fun String.replaceFirstMapped(r: Regex, f: (MatchResult) -> String): String = r.find(this)?.let { replaceRange(it.range, f(it)) } ?: this

/** Dart's replaceAll(RegExp, String). */
fun String.replaceAllLit(r: Regex, lit: String): String = r.replace(this) { lit }

/** Dart's replaceAllMapped. */
fun String.replaceAllMapped(r: Regex, f: (MatchResult) -> String): String = r.replace(this) { f(it) }

fun cap(t: String): String = if (t.isEmpty()) t else t.substring(0, 1).uppercase() + t.substring(1)
