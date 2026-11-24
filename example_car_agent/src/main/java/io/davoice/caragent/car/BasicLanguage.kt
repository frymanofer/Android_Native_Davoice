package io.davoice.caragent.car

import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.numOf
import io.davoice.caragent.util.str

// Basic language support (port of the Flutter / React Native car apps' basic language). The on-device model and the agent core's
// word lists are tuned for English and Brazilian Portuguese only. In every other language ("Basic") the agent never acts on its own
// reading of the request: each car action is first ASKED in the user's language ("Soll ich das Fahrerfenster öffnen?") and runs only
// after a plain yes. Read-only lookups (fuel, ETA, places) answer directly. The sentences come from assets/car/basic_languages.json
// (the same file as the other apps).
//
// How the yes reaches the action: the car tool refuses with an "offer" (the call itself); the agent core runs an offered call when
// the next request is a plain yes. The core's yes / no words are English + Portuguese, so the app turns a plain "ja" / "sí" / "oui"
// into "yes" / "no" before the request goes to the agent (normalizeYesNo).

object BasicLanguage {
    private var langs: Map<String, Obj> = emptyMap()

    /** Loads assets/car/basic_languages.json (CarTools.load does it). */
    fun load(json: String) {
        langs = Json.obj(json)["languages"].asObj().mapValues { it.value.asObj() }
    }

    /** The languages the model and the agent core were tuned for: they act directly. */
    val tunedLanguages = setOf("en", "pt-BR")
    fun isBasic(language: String?): Boolean = (language ?: "en") !in tunedLanguages

    private fun entry(language: String): Obj = langs[language] ?: langs["en"] ?: emptyMap()
    private fun table(e: Obj, key: String): Map<String, String> = (e[key] as? Map<*, *>)?.entries?.associate { "${it.key}" to str(it.value) } ?: emptyMap()

    /** The app's own sentence in a Basic language (whichSeat, didNotCatch, notInDemo, noRemote, lowClarify, lowDecline, lowNoRemote). */
    fun message(language: String, key: String): String? = if (isBasic(language)) table(langs[language] ?: emptyMap(), "messages")[key] else null

    /** The agent pack's spoken prompts in a Basic language (see AgentPack). */
    fun packTexts(language: String): Obj? = if (!isBasic(language)) null else langs[language]?.get("pack") as? Obj

    private fun fill(tpl: String, vars: Map<String, String>): String = Regex("\\{(\\w+)\\}").replace(tpl) { vars[it.groupValues[1]] ?: it.value }
    private fun capFirst(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    private fun num(n: Any?, language: String): String {
        val v = numOf(n) ?: return str(n)
        val r = Math.round(v * 10) / 10.0
        val s = if (r == Math.rint(r)) "${Math.round(r)}" else "$r"
        return if (language == "en") s else s.replace('.', ',')
    }

    private fun deg(t: Any?, unit: Any?, language: String) = "${num(t, language)} °${if (unit == "fahrenheit") "F" else "C"}"

    /** The action a call stands for, as a short phrase ("das Fahrerfenster öffnen"). Unknown calls get the generic phrase. */
    fun describeCall(name: String, args: Map<String, Any?>, language: String): String {
        val e = entry(language)
        val t = table(e, "t")
        fun tt(key: String, vars: Map<String, String> = emptyMap()) = fill(t[key] ?: t["generic"] ?: "", vars)
        fun pick(tbl: String, key: Any?, fallback: String): String {
            val m = table(e, tbl)
            return m[str(key)] ?: m[fallback] ?: (if (key == null) "" else str(key))
        }
        val a = args
        return when (name) {
            "set_window" -> {
                val pos = if (a["position"] in listOf("open", "close", "half", "crack")) a["position"] else "open"
                tt("window.$pos", mapOf("window" to pick("window", a["window"] ?: "all", "all")))
            }
            "open_door" -> tt("door.open", mapOf("door" to pick("door", a["door"] ?: "all", "all")))
            "close_doors" -> tt("door.close", mapOf("door" to pick("door", a["door"] ?: "all", "all")))
            "lock_car", "unlock_car" -> {
                val door = if (a["door"] != null && a["door"] != "all") pick("door", a["door"], "car") else table(e, "door")["car"] ?: ""
                tt(if (name == "lock_car") "lock" else "unlock", mapOf("door" to door))
            }
            "set_sunroof" -> tt("sunroof.${if (a["action"] in listOf("open", "tilt", "close")) a["action"] else "open"}")
            "set_trunk" -> tt("${if (a["which"] == "frunk") "frunk" else "trunk"}.${if (a["action"] == "close") "close" else "open"}")
            "set_temperature" -> {
                if (a["temperature"] != null) {
                    val temp = deg(a["temperature"], a["unit"], language)
                    if (a["zone"] != null && a["zone"] != "all") tt("temp.setZone", mapOf("t" to temp, "zone" to pick("zone", a["zone"], "all")))
                    else tt("temp.set", mapOf("t" to temp))
                } else {
                    val c = numOf(str(a["change"] ?: 1)) ?: 1.0
                    tt(if (c >= 0) "temp.up" else "temp.down", mapOf("n" to num(if (Math.abs(c) == 0.0) 1.0 else Math.abs(c), language)))
                }
            }
            "set_dual_zone_temperature" -> tt("dual", mapOf("d" to deg(a["driver"], a["unit"], language), "p" to deg(a["passenger"], a["unit"], language)))
            "comfort_feeling" -> tt(if (a["feeling"] == "hot") "feel.hot" else "feel.cold")
            "set_fan_speed" -> if (a["level"] != null) tt("fan.set", mapOf("n" to num(a["level"], language)))
            else tt(if ((numOf(str(a["change"] ?: 1)) ?: 1.0) >= 0) "fan.up" else "fan.down")
            "set_airflow" -> tt("air", mapOf("mode" to pick("air", a["mode"], "face")))
            "set_defrost" -> tt("defrost.${if (a["target"] == "rear") "rear" else "windshield"}.${if (a["on"] == false) "off" else "on"}")
            "set_seat_climate" -> {
                val vars = mapOf("feature" to pick("feature", a["feature"] ?: "heater", "heater"), "seat" to pick("seat", a["seat"] ?: "driver", "driver"))
                if (a["level"] == "off") tt("seat.off", vars) else tt("seat.set", vars + mapOf("level" to pick("level", a["level"] ?: "medium", "medium")))
            }
            "set_steering_wheel_heater" -> tt(if (a["on"] == false) "wheel.off" else "wheel.on")
            "play_media" -> tt("media", mapOf("q" to str(a["query"] ?: "")))
            "tune_radio" -> tt("radio", mapOf("band" to str(a["band"] ?: "fm").uppercase(), "f" to num(a["frequency"], language)))
            "set_audio_source" -> tt("source", mapOf("source" to pick("source", a["source"], "radio")))
            "set_volume" -> {
                val act = str(a["action"] ?: (if ((numOf(str(a["change"] ?: 0)) ?: 0.0) < 0) "down" else "up"))
                if (act == "set") tt("vol.set", mapOf("n" to num(a["level"], language)))
                else tt("vol.${if (act in listOf("up", "down", "mute", "unmute")) act else "up"}")
            }
            "navigate_to" -> {
                val dest = str(a["destination"] ?: "").trim()
                when (dest.lowercase()) {
                    "home" -> tt("nav.home")
                    "work" -> tt("nav.work")
                    else -> tt("nav", mapOf("dest" to dest))
                }
            }
            "set_route_avoid" -> tt("avoid", mapOf("avoid" to pick("avoid", a["avoid"], "traffic")))
            else -> tt("generic")
        }
    }

    /** One question for one or more calls: "Soll ich das Fahrerfenster öffnen und das Gebläse auf Stufe 3 stellen?" */
    fun confirmQuestion(calls: List<Obj>, language: String): String {
        val e = entry(language)
        val parts = calls.map { describeCall(str(it["name"]), it["arguments"].asObj(), language) }
        val and = str(e["and"] ?: "and")
        val body = when {
            parts.isEmpty() -> table(e, "t")["generic"] ?: ""
            parts.size == 1 -> parts.first()
            else -> "${parts.dropLast(1).joinToString(", ")} $and ${parts.last()}"
        }
        return fill(str(e["q"] ?: "{a}?"), mapOf("a" to if (e["capitalize"] == true) capFirst(body) else body))
    }

    fun done(language: String) = str(entry(language)["done"] ?: "")
    fun cannot(language: String) = str(entry(language)["cannot"] ?: "")
    fun driving(language: String) = str(entry(language)["driving"] ?: "")

    /** A read-only lookup answered in the Basic language, or null (then the tool's own sentence is used). */
    fun answer(name: String, args: Map<String, Any?>, language: String, destination: String?, fuelPercent: Int): String? {
        val e = entry(language)
        val ans = table(e, "answer")
        fun a(key: String, vars: Map<String, String> = emptyMap()) = ans[key]?.let { fill(it, vars) }
        return when (name) {
            "get_trip_eta" -> if (destination != null) a("eta", mapOf("min" to "24")) else a("eta.none")
            "find_along_route" -> a("find", mapOf("place" to (table(e, "place")[str(args["place_type"])] ?: str(args["place_type"] ?: ""))))
            "get_vehicle_status" -> {
                val item = str(args["item"] ?: "")
                a("status.$item", mapOf(
                    "km" to if (item == "fuel") "${Math.round(fuelPercent * 5.0)}" else "312",
                    "pct" to if (item == "fuel") "$fuelPercent" else "76",
                    "v" to num(12.4, language),
                ))
            }
            else -> null
        }
    }

    // ---- what waits for a yes -------------------------------------------------------------------------------------------------

    private fun stable(v: Any?): String = when (v) {
        is List<*> -> "[${v.joinToString(",") { stable(it) }}]"
        is Map<*, *> -> "{${v.keys.map { "$it" }.sorted().joinToString(",") { "${Json.stringify(it)}:${stable(v[it])}" }}}"
        else -> Json.stringify(v)
    }

    private fun keyOf(name: String, args: Map<String, Any?>) = "$name|${stable(args)}"

    private data class Pending(val key: String, val call: Obj, val turn: Int)

    private var turn = 0
    private val pending = mutableListOf<Pending>()

    /** Call before each request (after normalizeYesNo). Calls asked in the previous request stay confirmable for this one only. */
    fun beginTurn() {
        turn += 1
        pending.removeAll { it.turn < turn - 1 }
    }

    /** True (and consumed) when this call was asked in the previous request: the user's yes ran it. */
    fun takeConfirmed(name: String, args: Map<String, Any?>): Boolean {
        val k = keyOf(name, args)
        val i = pending.indexOfFirst { it.key == k && it.turn == turn - 1 }
        if (i < 0) return false
        pending.removeAt(i)
        return true
    }

    /** Remembers calls to ask about; returns every call asked in this request (the core keeps one offer per request). */
    fun addPending(calls: List<Obj>): List<Obj> {
        for (call in calls) {
            val k = keyOf(str(call["name"]), call["arguments"].asObj())
            if (pending.none { it.key == k && it.turn == turn }) pending += Pending(k, call, turn)
        }
        return pending.filter { it.turn == turn }.map { it.call }
    }

    fun clearPending() = pending.clear()

    private const val ACCENTS = "àa áa âa ãa äa åa āa ăa ąa çc ćc čc ďd èe ée êe ëe ēe ėe ęe ěe ìi íi îi ïi īi įi ñn ńn ňn òo óo ôo õo öo ōo őo " +
        "ùu úu ûu üu ūu ůu űu ųu ýy ÿy žz źz żz śs šs şs řr ťt ţt ľl ĺl άα έε ήη ίι ϊι ΐι όο ύυ ϋυ ΰυ ώω"
    private val accentMap: Map<Int, Int> = ACCENTS.split(' ').mapNotNull { pair ->
        val cps = pair.codePoints().toArray()
        if (cps.size == 2) cps[0] to cps[1] else null
    }.toMap()

    /** Lower case, no accents, letters and digits only. */
    fun fold(s: String): String {
        val b = StringBuilder()
        s.lowercase().codePoints().forEach { cp -> b.appendCodePoint(accentMap[cp] ?: cp) }
        return b.toString().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private val phraseRe = mutableMapOf<String, Regex>()
    private fun onlyPhrases(text: String, phrases: List<String>, cacheKey: String): Boolean {
        val r = phraseRe.getOrPut(cacheKey) {
            val alts = phrases.map { fold(it) }.toSet().filter { it.isNotEmpty() }.sortedByDescending { it.length }
            Regex("^((${alts.joinToString("|") { Regex.escape(it) }})( |$))+$")
        }
        return r.containsMatchIn(fold(text))
    }

    /** A plain yes / no to the agent's question in a Basic language -> "yes" / "no" (what the agent core understands), else null. */
    fun normalizeYesNo(text: String, language: String): String? {
        if (!isBasic(language) || pending.none { it.turn == turn }) return null
        val e = entry(language)
        val yes = e["yes"].asList().map { str(it) }
        val no = e["no"].asList().map { str(it) }
        if (onlyPhrases(text, yes, "$language|yes")) return "yes"
        if (onlyPhrases(text, no, "$language|no")) return "no"
        return null
    }

    // ---- English hints for the model ---------------------------------------------------------------------------------------------

    /** A hint pattern for the folded text: words are separated by single spaces, so \w = any non-space and \b = a space or the end. */
    private fun hintRegex(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\' && i + 1 < pattern.length && pattern[i + 1] == 'b') {
                val next = pattern.substring(i + 2)
                val end = next.isEmpty() || next.startsWith("|") || next.startsWith(")") || next.startsWith("(?!") || next.startsWith("(?=")
                out.append(if (end) "(?= |$)" else "(?:^| )")
                i += 2
            } else if (pattern[i] == '\\' && i + 1 < pattern.length && pattern[i + 1] == 'w') {
                out.append("[^ ]")
                i += 2
            } else {
                out.append(pattern[i])
                i++
            }
        }
        return Regex(out.toString())
    }

    private val hintCache = mutableMapOf<String, List<Pair<Regex, String>>>()

    /** Basic language: the car words the user said, in English, added in brackets for the model ("Mach die Musik leiser" ->
     *  "Mach die Musik leiser (volume down, music)"). Tuned languages and sentences without a car word stay as they are. */
    fun withCarHints(text: String, language: String): String {
        if (!isBasic(language)) return text
        val rules = hintCache.getOrPut(language) {
            langs[language]?.get("hints").asList().map { r -> val l = r.asList(); hintRegex(str(l[0])) to str(l[1]) }
        }
        val folded = fold(text)
        val words = mutableListOf<String>()
        for ((r, en) in rules) if (r.containsMatchIn(folded) && en !in words) words += en
        return if (words.isEmpty()) text else "${text.trim()} (${words.joinToString(", ")})"
    }

    private val ptWords = Regex("(?:^|[^\\p{L}])(você|voce|não|desculpe|ouvir|isso|quer|obrigad[oa]|disponível)(?=$|[^\\p{L}])", RegexOption.IGNORE_CASE)

    /** Basic language: a sentence the model said in Portuguese becomes "Sorry, could you say it again?" in the user's language; null = keep. */
    fun replyFix(sentence: String, language: String): String? {
        if (!isBasic(language) || language.startsWith("pt")) return null
        if (!ptWords.containsMatchIn(sentence)) return null
        return message(language, "didNotCatch")
    }
}
