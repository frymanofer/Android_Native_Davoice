package io.davoice.caragent.car

import io.davoice.caragent.util.g
import io.davoice.caragent.util.gs
import io.davoice.caragent.util.numOf
import io.davoice.caragent.util.re
import io.davoice.caragent.util.str

// What the driver HEARS when one request did several car actions (the same as the Flutter / React Native car apps' speech composer):
// the car tools' own sentences of actions that ran in a row become one natural sentence:
//   "I closed all the doors. I locked the car."  ->  "All set. I closed all the doors and locked the car. Everything is secure."
//   "Fechei todas as portas. Tranquei o carro."  ->  "Pronto, já fiz isso para você. Fechei todas as portas e tranquei o carro. Agora o carro está todo seguro."
// Nothing here decides an action. Only sentences of actions that really ran (recorded by the tool runner in this turn) are merged,
// word for word; anything else (a question, a refusal, an undo, a status answer) is said as it was. The history the model sees keeps
// the original sentences: only speech and the screen change.

data class CarTurnAction(
    val tool: String,
    val say: String,
    /** false: refused / failed / an undo: never merged */
    val merge: Boolean,
    /** the call's arguments (picks the friendly closing line) */
    val args: Map<String, Any?> = emptyMap(),
    /** 'did' = changed the car; 'already' = it was already so; 'answer' = a lookup. Only 'did' gets the friendly words. */
    val kind: String? = null,
    /** the calls a plain "yes" to this reply runs (the result's offer, handled by the agent core), by name */
    val offer: List<String>? = null,
) {
    fun withSay(s: String) = copy(say = s)
}

object SpeechComposer {
    private val turn = mutableListOf<CarTurnAction>()
    private var variant = 0
    private var ackVariant = 0

    /** Call before each request. */
    fun beginCarTurn() {
        turn.clear()
        BasicLanguage.beginTurn()
    }

    /** Called by the car tool runner with the sentence it returns. */
    fun noteCarSay(tool: String, say: String, merge: Boolean, args: Map<String, Any?> = emptyMap(), kind: String? = null, offer: List<String>? = null) {
        if (say.trim().isNotEmpty()) turn += CarTurnAction(tool, say.trim(), merge, args, kind, offer)
    }

    fun carTurnActions(): List<CarTurnAction> = turn.toList()

    /** Before beginCarTurn: the calls the previous request's offer runs on a plain "yes" (the core keeps it for one request), or null. */
    fun pendingOfferCalls(): List<String>? = turn.asReversed().firstOrNull { !it.offer.isNullOrEmpty() }?.offer

    /** For tests: start the openers from the first one. */
    fun resetVariant() {
        variant = 0
        ackVariant = 0
    }

    private val openers = mapOf("en" to listOf("All set.", "Done.", "You got it."), "pt" to listOf("Pronto, já fiz isso para você.", "Certo, já resolvi isso para você.", "Tudo certo, já cuidei disso para você."))
    private val secure = mapOf("en" to "Everything is secure.", "pt" to "Agora o carro está todo seguro.")
    private val closingTools = setOf("close_doors", "set_window", "set_sunroof", "set_trunk")
    private val lookupTools = setOf("get_vehicle_status", "get_trip_eta", "find_along_route")

    // A friendlier voice: the same facts, word for word, with a warm opener and, every other time, a short closing line that fits
    // the action. Only for actions that really ran, tuned languages only.
    private val friendlyOpeners = mapOf(
        "en" to listOf("Sure, you got it.", "You got it!", "Consider it done!", "Alright, all set!", "There you go!"),
        "pt" to listOf("Claro, já fiz isso para você.", "Pronto, já fiz isso para você.", "Certo, já resolvi isso para você.", "Tudo certo, já cuidei disso para você.", "Feito, já está tudo certinho agora."),
    )

    /** What the car says while a request takes a moment. */
    private val acks = mapOf("en" to listOf("Got it.", "On it.", "One moment."), "pt" to listOf("Entendido, só um instante, por favor.", "Só um instante, já vou fazer isso.", "Pode deixar, só um instante, por favor."))

    /** A short acknowledgement for a request that takes a moment, or null (Basic languages keep their own flow). */
    fun carAck(language: String): String? {
        val lang = when (language) {
            "pt-BR" -> "pt"
            "en" -> "en"
            else -> return null
        }
        val list = acks[lang]!!
        return list[ackVariant++ % list.size]
    }

    /** The closing line for an action that ran, or null. */
    private fun friendlyTail(a: CarTurnAction, lang: String, driving: Boolean = false): String? {
        val g = a.args
        fun t(en: String, pt: String) = if (lang == "pt") pt else en
        fun n(v: Any?): Double = numOf(v) ?: numOf(str(v ?: 0)) ?: 0.0
        return when (a.tool) {
            "unlock_car" -> if (driving) null else t("Come on in!", "Pode entrar, o carro já está aberto.")
            "lock_car" -> t("You're good to go.", "Pode ficar tranquilo, está tudo seguro.")
            "set_window" -> if (g["position"] == "close") null else t("Enjoy the fresh air.", "Aproveite o vento fresco da estrada.")
            "set_sunroof" -> if (g["action"] == "close") null else t("Enjoy the view!", "Aproveite a vista lá de cima.")
            "comfort_feeling" -> if (g["feeling"] == "hot") t("It'll cool down in a minute.", "O carro vai refrescar em um minutinho.") else t("It'll feel cozier in a minute.", "Já já o carro fica mais quentinho.")
            "set_temperature" -> {
                if (g["temperature"] != null) return null
                val c = n(g["change"])
                if (c > 0) t("It'll warm up soon.", "O carro vai esquentar em um minutinho.") else if (c < 0) t("It'll cool down soon.", "O carro vai refrescar em um minutinho.") else null
            }
            "set_seat_climate" -> if (g["level"] == "off") null else if (g["feature"] == "ventilation") t("That should cool you down.", "A ventilação do banco vai refrescar você.") else t("Nice and cozy.", "O assento vai ficar quentinho rapidinho.")
            "set_steering_wheel_heater" -> if (g["on"] == false) null else t("Warm hands coming up.", "O volante vai ficar quentinho rapidinho.")
            "navigate_to" -> t("Let's go!", "Vamos lá, tenha uma ótima viagem!")
            "play_media", "tune_radio" -> t("Enjoy the music!", "Aproveite a música durante a viagem.")
            "set_defrost" -> if (g["on"] == false) null else t("You'll see clearly in a moment.", "O vidro vai desembaçar em um minutinho.")
            else -> null
        }
    }

    private fun did(a: CarTurnAction) = (a.kind ?: if (a.tool in lookupTools) "answer" else "did") == "did"

    // ---- how a tool sentence is SAID: the tools' sentences stay as they are in the history (the model was trained with them); only
    // the spoken wording is smoother. Same facts, same numbers; tuned languages.

    /** "airport" -> "the airport" (a common noun without its article); names, "home" and "my office" stay as they are. */
    private fun enPlace(d: String): String {
        val p = d.replace('_', ' ').trim()
        return if (Regex("^[a-z]").containsMatchIn(p) && !Regex("^(the|a|an|my|your|our|his|her|their|work|downtown)\\b").containsMatchIn(p) &&
            !Regex("'s\\b").containsMatchIn(p)
        ) "the $p" else p
    }

    /** "aeroporto" -> (to: "o aeroporto", at: "no aeroporto"); "casa" -> (to: "casa", at: "em casa"); "São Paulo" stays. */
    private fun ptPlace(d: String): Pair<String, String> {
        val p = d.replace('_', ' ').trim()
        if (re("^casa$", ignoreCase = true).containsMatchIn(p)) return "casa" to "em casa"
        val art = re("^(o|a|os|as) (.+)$", ignoreCase = true).find(p)
        if (art != null) {
            val into = mapOf("o" to "no", "a" to "na", "os" to "nos", "as" to "nas")
            return p to "${into[art.gs(1).lowercase()]} ${art.gs(2)}"
        }
        if (Regex("^[a-zà-ü]").containsMatchIn(p) && !re("^(um|uma|meu|minha|meus|minhas|seu|sua|nosso|nossa)\\b", ignoreCase = true).containsMatchIn(p)) {
            val fem = re("(a|ção|dade|gem)$", ignoreCase = true).containsMatchIn(p.split(' ').first())
            return "${if (fem) "a" else "o"} $p" to "${if (fem) "na" else "no"} $p"
        }
        return p to "em $p"
    }

    /** The sentence as the driver hears it (or the tool's own sentence when nothing reads better). */
    fun spokenSay(a: CarTurnAction, language: String): String {
        val s = a.say
        // an undo ("Undone. <the tool's sentence>"): said as putting it back, the sentence said like any other
        val undo = re("^(Undone|Desfeito)\\. (.+)$", dotAll = true).find(s)
        if (undo != null && (language == "en" || language == "pt-BR")) {
            return "${if (language == "en") "Okay, I put it back." else "Pronto, voltei tudo como estava antes."} ${spokenSay(a.withSay(undo.gs(2)), language)}"
        }
        val change = numOf(str(a.args["change"] ?: 0)) ?: 0.0
        if (a.tool == "find_along_route" && (a.offer?.contains("navigate_to") == true) && (language == "en" || language == "pt-BR")) {
            return "$s ${if (language == "en") "Want me to take you there?" else "Quer que eu te leve até lá?"}"
        }
        val sub: List<Pair<Regex, (MatchResult) -> String>> = when (language) {
            "en" -> listOf(
                Regex("^I set the whole cabin to (.+)$") to { m -> "I set the temperature to ${m.gs(1)}" },
                Regex("^I raised the whole cabin to (.+)$") to { m -> "I turned the temperature up to ${m.gs(1)}" },
                Regex("^I lowered the whole cabin to (.+)$") to { m -> "I turned the temperature down to ${m.gs(1)}" },
                Regex("^The whole cabin is already at (.+)$") to { m -> "The temperature is already at ${m.gs(1)}" },
                Regex("^I set the fan to level (\\d+)\\.$") to { m ->
                    if (change > 0) "I turned the fan up to level ${m.gs(1)}." else if (change < 0) "I turned the fan down to level ${m.gs(1)}." else "I set the fan to level ${m.gs(1)}."
                },
                Regex("^I set the (heater|ventilation) in (the .+? seat) to (\\w+)\\.$") to { m -> "I set ${m.gs(2)} ${m.gs(1)} to ${m.gs(3)}." },
                Regex("^I turned off the (heater|ventilation) in (the .+? seat)\\.$") to { m -> "I turned off ${m.gs(2)} ${m.gs(1)}." },
                Regex("^The (heater|ventilation) in (the .+? seat) is already (.+)$") to { m ->
                    "${m.gs(2).substring(0, 1).uppercase()}${m.gs(2).substring(1)} ${m.gs(1)} is already ${m.gs(3)}"
                },
                Regex("^I turned the (rear defogger|windshield defrost) (on|off)\\.$") to { m -> "I turned ${m.gs(2)} the ${m.gs(1)}." },
                Regex("^Navigating to (.+?): (.*)$") to { m -> if (m.gs(1) == "home") "Navigating home: ${m.gs(2)}" else "Navigating to ${enPlace(m.gs(1))}: ${m.gs(2)}" },
                Regex("^You'll get to (.+?) in about (.*)$") to { m ->
                    if (m.gs(1) == "home") "You'll get home in about ${m.gs(2)}" else "You'll get to ${enPlace(m.gs(1))} in about ${m.gs(2)}"
                },
                Regex("^(.*) Close them first\\?$") to { m -> "${m.gs(1)} Want me to close them first?" },
                Regex("^(.*) Close it first\\?$") to { m -> "${m.gs(1)} Want me to close it first?" },
                Regex("^I didn't change it: (.+) must be between (\\S+) and (\\S+)\\.$") to { m ->
                    val w = m.gs(1)
                    "Sorry, ${if (Regex("^(am|fm)$").matches(w)) w.uppercase() else if (w == "volume") "the volume" else w} only goes from ${m.gs(2)} to ${m.gs(3)}."
                },
                Regex("^I didn't change the temperature: both values must be between (\\S+) and (\\S+)\\.$") to { m ->
                    "Sorry, the temperature only goes from ${m.gs(1)} to ${m.gs(2)}."
                },
                Regex("^I can't do that while the car is moving\\.$") to { _ -> "Sorry, I can't do that while we're moving." },
            )
            "pt-BR" -> listOf(
                Regex("^Coloquei a cabine toda em (.+)$") to { m -> "Coloquei a temperatura em ${m.gs(1)}" },
                Regex("^Subi a cabine toda para (.+)$") to { m -> "Subi a temperatura para ${m.gs(1)}" },
                Regex("^Baixei a cabine toda para (.+)$") to { m -> "Baixei a temperatura para ${m.gs(1)}" },
                Regex("^A cabine toda já está em (.+)$") to { m -> "A temperatura já está em ${m.gs(1)}" },
                Regex("^Coloquei a ventilação no nível (\\d+)\\.$") to { m ->
                    if (change > 0) "Aumentei a ventilação para o nível ${m.gs(1)}." else if (change < 0) "Baixei a ventilação para o nível ${m.gs(1)}." else "Coloquei a ventilação no nível ${m.gs(1)}."
                },
                Regex("^Coloquei (o aquecimento|a ventilação) do banco (.+)$") to { m -> "Liguei ${m.gs(1)} do banco ${m.gs(2)}" },
                Regex("^Indo para (.+?): (.*)$") to { m -> "Indo para ${ptPlace(m.gs(1)).first}: ${m.gs(2)}" },
                Regex("^Você chega em (.+?) em cerca de (.*)$") to { m -> "Você chega ${ptPlace(m.gs(1)).second} em cerca de ${m.gs(2)}" },
                Regex("^Não mudei: (.+) precisa ficar entre (\\S+) e (\\S+)\\.$") to { m ->
                    val w = m.gs(1)
                    "Desculpe, ${if (Regex("^(am|fm)$").matches(w)) w.uppercase() else w} só vai de ${m.gs(2)} a ${m.gs(3)}."
                },
                Regex("^Não mudei a temperatura: os dois valores precisam ficar entre (\\S+) e (\\S+)\\.$") to { m ->
                    "Desculpe, a temperatura só vai de ${m.gs(1)} a ${m.gs(2)}."
                },
                Regex("^Não posso fazer isso com o carro em movimento\\.$") to { _ -> "Desculpe, não dá pra fazer isso com o carro em movimento." },
            )
            else -> return s
        }
        for ((r, to) in sub) {
            val m = r.find(s)
            if (m != null) return to(m)
        }
        return s
    }

    // pt-BR wording (2026-11-22, temporary until the pt-BR voice model is fixed; as the RN example): the speech library speaks one
    // sentence at a time and the pt-BR model drops or garbles short sentences said alone ("Claro!", "Tranquei o carro."). Every pt sentence
    // in this file was synthesized 5 times with the app's model and voice and understood 5/5 by speech-to-text; keep new ones at about six
    // words and test them. A short tool sentence said ON ITS OWN is said longer; a merged sentence stays as it is. "vidros" is said as
    // "janelas" here (the pt-BR model's "vidros" is heard as "vídeos"). Same list as the RN example's PT_LONGER.
    private val ptLonger: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^Tranquei o carro\\.$") to { _ -> "Tranquei todas as portas do carro." },
        Regex("^Destranquei o carro\\.$") to { _ -> "Destranquei todas as portas do carro." },
        Regex("^Abri todas as portas\\.$") to { _ -> "Abri todas as portas do carro." },
        Regex("^Fechei todas as portas\\.$") to { _ -> "Fechei todas as portas para você." },
        Regex("^Abri todos os vidros\\.$") to { _ -> "Abri todas as janelas do carro para você." },
        Regex("^Fechei todos os vidros\\.$") to { _ -> "Fechei todas as janelas do carro." },
        Regex("^Abri o teto solar\\.$") to { _ -> "Deixei o teto solar aberto para você." },
        Regex("^Fechei o teto solar\\.$") to { _ -> "Fechei o teto solar para você." },
        Regex("^Inclinei o teto solar\\.$") to { _ -> "Deixei o teto solar inclinado, como você pediu." },
        Regex("^Tocando (.+)\\.$") to { m -> "Agora tocando ${m.gs(1)} para você." },
        Regex("^Sintonizei a rádio (\\S+)\\.$") to { m -> "Sintonizei a rádio ${m.gs(1)} para você." },
        Regex("^(Liguei|Desliguei) o desembaçador traseiro\\.$") to { m -> "${m.gs(1)} o desembaçador traseiro para você." },
    )

    private fun ptLongerSentence(sentence: String): String {
        for ((r, to) in ptLonger) {
            val m = r.find(sentence)
            if (m != null) return to(m)
        }
        return sentence
    }

    /** "A. B! C" -> ["A.", "B!", "C"]: a sentence ends at a word ending in . ! or ? ("2,1 km." and "87.5" stay whole). */
    private fun sentencesOf(text: String): List<String> {
        val out = mutableListOf<String>()
        var cur = mutableListOf<String>()
        for (w in text.split(Regex("\\s+"))) {
            if (w.isEmpty()) continue
            cur += w
            if (Regex("[.!?]$").containsMatchIn(w)) {
                out += cur.joinToString(" ")
                cur = mutableListOf()
            }
        }
        if (cur.isNotEmpty()) out += cur.joinToString(" ")
        return out
    }

    /** One action that ran, said with a warm opener (none when the car already said "Got it.") and, every other time, its closing line. */
    private fun friendlyOne(a: CarTurnAction, lang: String, acked: Boolean, driving: Boolean): String {
        val v = variant++
        val list = friendlyOpeners[lang]!!
        val opener = if (acked) "" else "${list[v % list.size]} "
        val tail = if (v % 2 == 0) friendlyTail(a, lang, driving) else null
        return "$opener${a.say}${if (tail != null) " $tail" else ""}"
    }

    /** "I closed all the doors." -> "closed all the doors" (en); "Fechei todas as portas." -> "fechei todas as portas" (pt); else null. */
    private fun clause(say: String, pt: Boolean): String? {
        val s = say.trim()
        if (!Regex("[.!]$").containsMatchIn(s) || Regex("[.!?]\\s").containsMatchIn(s.substring(0, s.length - 1))) return null
        val body = s.substring(0, s.length - 1)
        if (!pt) return Regex("^I (?:'ve |have )?([a-z].*)$").find(body)?.g(1)
        val first = body.split(' ').first()
        if (!Regex("^[A-ZÁÉÍÓÚÂÊÔÃÕ][a-záéíóúâêôãõç]*i$").matches(first)) return null
        return body.substring(0, 1).lowercase() + body.substring(1)
    }

    private fun join(parts: List<String>, pt: Boolean): String =
        if (parts.size == 1) parts.first() else "${parts.dropLast(1).joinToString(", ")} ${if (pt) "e" else "and"} ${parts.last()}"

    private fun merged(run: List<CarTurnAction>, pt: Boolean, friendly: Boolean, driving: Boolean): String? {
        val parts = run.map { clause(it.say, pt) }
        val lang = if (pt) "pt" else "en"
        val secures = run.any { it.tool == "lock_car" } && run.any { it.tool in closingTools }
        if (parts.any { it == null }) {
            // sentences that don't merge ("All the windows are already closed.", "Navigating to ..."): one after the other after the
            // opener, when at least one action ran and the rest were already so
            if (!friendly || !run.any { did(it) } || !run.all { did(it) || it.kind == "already" }) return null
            val opener = openers[lang]!![variant++ % openers[lang]!!.size]
            return "$opener ${run.joinToString(" ") { it.say }}${if (secures) " ${secure[lang]}" else ""}"
        }
        val opener = openers[lang]!![variant++ % openers[lang]!!.size]
        val body = join(parts.map { it!! }, pt)
        val sentence = if (pt) body.substring(0, 1).uppercase() + body.substring(1) else "I $body"
        // the friendly closing line of the last action that has one ("I unlocked the car and opened all the doors. Come on in!")
        var tail: String? = null
        if (!secures && friendly && run.all { did(it) }) {
            for (a in run.asReversed()) {
                tail = friendlyTail(a, lang, driving)
                if (tail != null) break
            }
        }
        return "$opener $sentence.${if (secures) " ${secure[lang]}" else ""}${if (tail != null) " $tail" else ""}"
    }

    /** The reply text as the driver should hear it. [text] = what the agent said (core order); [actions] = this turn's tool sentences. */
    fun composeCarSpeech(text: String, actions: List<CarTurnAction>, language: String, acked: Boolean = false, driving: Boolean = false): String {
        val pt = language.lowercase().startsWith("pt")
        // the friendlier voice: the tuned languages only (a Basic language says its own short "done")
        val friendly = language == "en" || language == "pt-BR"
        val pending = actions.toMutableList()
        val segs = mutableListOf<Pair<String, CarTurnAction?>>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            val i = pending.indexOfFirst { rest.startsWith(it.say) }
            if (i >= 0) {
                val found = pending.removeAt(i)
                rest = rest.substring(found.say.length).trimStart()
                // the action as it is said (spokenSay): the same facts in smoother words; merging and the friendly words work on it
                val a = found.withSay(spokenSay(found, language))
                segs += a.say to a
                continue
            }
            val m = re("^.*?[.!?](?:\\s+|$)", dotAll = true).find(rest)
            val piece = m?.value ?: rest
            segs += piece.trim() to null
            rest = rest.substring(piece.length).trimStart()
        }
        // friendly words only when everything said is the sentences of actions that ran (no refusal, question, undo or model text)
        val warm = friendly && segs.all { it.second?.merge == true }
        val out = mutableListOf<String>()
        var i = 0
        while (i < segs.size) {
            if (segs[i].second?.merge == true) {
                var j = i
                while (j < segs.size && segs[j].second?.merge == true) j++
                val run = segs.subList(i, j).map { it.second!! }
                val one = if (run.size >= 2) merged(run, pt, warm, driving)
                else if (warm && run.size == 1 && did(run.first())) friendlyOne(run.first(), if (pt) "pt" else "en", acked, driving)
                else null
                val texts = segs.subList(i, j).map { it.first }
                out += one ?: texts.filterIndexed { k, t -> k == 0 || t != texts[k - 1] }.joinToString(" ")
                i = j
            } else {
                out += segs[i].first
                i++
            }
        }
        // two confirmed actions in a Basic language both say "Erledigt.": once is enough
        val said = out.filterIndexed { k, t -> k == 0 || t != out[k - 1] }.joinToString(" ")
        return if (language == "pt-BR") sentencesOf(said).joinToString(" ") { ptLongerSentence(it) } else said
    }
}
