package io.davoice.caragent.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// JSON as plain Kotlin values (Map<String, Any?>, List<Any?>, String, Number, Boolean, null), the way the React Native and
// Flutter apps handle the agent's and the car's JSON. Maps keep their key order.

typealias Obj = Map<String, Any?>

object Json {
    fun parse(text: String): Any? = unwrap(JSONTokener(text).nextValue())

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Obj = parse(text) as Obj

    fun stringify(value: Any?): String = buildString { write(this, value) }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = unwrap(v.opt(k)) } }
        is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
        else -> v
    }

    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is String -> quote(b, v)
            is Boolean -> b.append(v)
            is Int, is Long, is Short, is Byte -> b.append(v)
            is Number -> b.append(num(v.toDouble()))
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(',')
                    first = false
                    quote(b, k.toString())
                    b.append(':')
                    write(b, x)
                }
                b.append('}')
            }
            is Iterable<*> -> {
                b.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) b.append(',')
                    write(b, x)
                }
                b.append(']')
            }
            is Array<*> -> write(b, v.toList())
            else -> quote(b, v.toString())
        }
    }

    /** A number as JSON / JavaScript prints it: 22 (not 22.0), 22.5. */
    fun num(d: Double): String = if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                '\b' -> b.append("\\b")
                '\u000C' -> b.append("\\f")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
    }
}

/** A deep copy through JSON (what the Dart / TS code does with jsonDecode(jsonEncode(x))). */
fun deepCopy(v: Any?): Any? = Json.parse(Json.stringify(v))

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Obj = (this as? Map<String, Any?>) ?: emptyMap()

fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()

/** A value as a number, as Dart's `v is num ? v : num.tryParse(v)`. */
fun numOf(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

/** A value as the JS / Dart string interpolation prints it ("22", "22.5", "true", "null" -> ""). */
fun str(v: Any?): String = when (v) {
    null -> ""
    is Double -> Json.num(v)
    is Float -> Json.num(v.toDouble())
    else -> v.toString()
}
