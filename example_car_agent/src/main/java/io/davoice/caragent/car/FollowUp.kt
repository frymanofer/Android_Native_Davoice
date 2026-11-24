package io.davoice.caragent.car

import io.davoice.caragent.flow.notInDemoTexts
import io.davoice.caragent.util.g
import io.davoice.caragent.util.gs
import io.davoice.caragent.util.re
import io.davoice.caragent.util.replaceFirstLit
import io.davoice.caragent.util.replaceFirstMapped

// What the driver says right after the agent's own question or offer (the same rules and lists as the Flutter / React Native car apps'
// follow-up; shared fixture follow_up.json):
//  - an answer to the agent's question goes to the agent WITH the request it answers ("Which window should I open?" -> "The driver's
//    one" = "Open the window, the driver's one"; "Where do you want to go?" -> "The airport" = "Navigate to the airport").
//  - "never mind", "cancel", "stop", "no" with nothing pending: a short "Okay." (with an offer pending they go to the agent core).
//  - an offer pending: more ways to say yes ("tá bom", "sounds good") reach the core as its plain "yes"; "take me there" accepts a
//    navigation offer.
//  - a question about the car ("Is the car locked?") is answered from the car (StateAnswers).
//  - "Say that again" / "What?" / "Repete": the last reply again.
//  - requests the model gets wrong every time are said the way it handles them ("Turn on the seat heater" -> "Heat the seat").
// Nothing here runs an action. Tuned languages (en, pt-BR) only: the Basic languages have their own yes / no flow.

/** What goes to the agent ([text]), or the app's own short reply ([reply]). */
data class TurnInput(val text: String? = null, val reply: String? = null)

object FollowUp {
    private fun tuned(language: String) = when (language) {
        "en" -> "en"
        "pt-BR" -> "pt"
        else -> null
    }

    private fun fold(s: String) = BasicLanguage.fold(s)

    private val onlyCache = HashMap<List<String>, Regex>()
    private fun only(text: String, phrases: List<String>): Boolean {
        val t = fold(text)
        if (t.isEmpty()) return false
        val r = onlyCache.getOrPut(phrases) { Regex("^(?:(?:${phrases.sortedByDescending { it.length }.joinToString("|")})(?: |$))+$") }
        return r.containsMatchIn(t)
    }

    private fun containsAny(text: String, phrases: List<String>): Boolean {
        val t = " ${fold(text)} "
        return phrases.any { t.contains(" $it ") }
    }

    private val dismiss = mapOf(
        "en" to listOf("never mind", "nevermind", "forget it", "forget about it", "forget that", "cancel", "cancel that", "cancel it", "stop", "stop it",
            "nothing", "no", "nope", "nah", "no thanks", "no thank you", "not now", "leave it", "that s all", "that s it", "all good", "i m good",
            "i m fine", "no worries", "don t", "don t bother", "thanks", "okay", "ok", "oh"),
        "pt" to listOf("deixa pra la", "deixa para la", "deixa", "deixa assim", "esquece", "esquece isso", "cancela", "cancelar", "cancela isso", "para",
            "pare", "nada", "nada nao", "nao", "nao precisa", "nao obrigado", "nao obrigada", "agora nao", "melhor nao", "so isso", "e so isso",
            "obrigado", "obrigada", "valeu", "ok", "ta"),
    )
    private val dismissCore = mapOf(
        "en" to listOf("never mind", "nevermind", "forget it", "forget about it", "forget that", "cancel", "cancel that", "cancel it", "stop", "stop it",
            "nothing", "no", "nope", "nah", "no thanks", "no thank you", "not now", "leave it", "that s all", "that s it", "don t", "don t bother"),
        "pt" to listOf("deixa pra la", "deixa para la", "deixa", "deixa assim", "esquece", "esquece isso", "cancela", "cancelar", "cancela isso", "para",
            "pare", "nada", "nada nao", "nao", "nao precisa", "nao obrigado", "nao obrigada", "agora nao", "melhor nao", "so isso", "e so isso"),
    )
    private val dismissReply = mapOf("en" to listOf("Okay.", "No problem."), "pt" to listOf("Tudo bem, então não vou fazer nada.", "Sem problema, fica para uma próxima vez."))
    private val yes = mapOf(
        "en" to listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "go ahead", "do it", "sounds good", "go for it", "please do",
            "sure thing", "why not", "let s do it", "of course", "absolutely", "alright", "all right", "perfect", "great", "cool",
            "that would be great", "yes please"),
        "pt" to listOf("sim", "claro", "pode", "isso", "bora", "por favor", "faz isso", "pode ser", "com certeza", "certo", "quero", "ta bom", "ta",
            "beleza", "fechado", "manda", "manda ver", "vai", "uhum", "aham", "pode sim", "ok", "perfeito", "otimo", "show", "demorou",
            "quero sim"),
    )
    private val goThere = mapOf(
        "en" to listOf("take me there", "go there", "navigate there", "let s go there", "let s go", "drive there", "navigate to it", "take me", "go",
            "yes take me there", "sure take me there", "ok take me there", "okay take me there", "yes please take me there"),
        "pt" to listOf("me leva la", "me leva", "leva eu la", "me leve la", "vamos la", "vai la", "navega pra la", "navega para la", "bora la", "quero ir",
            "partiu", "sim me leva la", "pode me levar", "pode me levar la", "me leva ate la"),
    )
    private val commandStart = mapOf(
        "en" to Regex("^(open|close|shut|turn|set|play|lock|unlock|navigate|take|find|raise|lower|increase|decrease|put|make|start|stop|switch|tune|mute|unmute|call|what|how|where|when|who|why|is|are|can|could|do|does|please|i|it|its|undo|cancel|show|tell|give|heat|cool|warm|defrost|avoid|go)( |$)"),
        "pt" to Regex("^(abre|abra|abrir|fecha|feche|fechar|liga|ligue|ligar|desliga|desligue|coloca|coloque|poe|bota|toca|toque|tocar|tranca|tranque|destranca|navega|navegue|leva|me|acha|procura|aumenta|abaixa|baixa|diminui|sobe|muda|troca|sintoniza|silencia|o que|como|onde|quando|quem|quanto|quantos|quantas|por que|pode|voce|vc|eu|esta|ta|to|faz|deixa|desfaz|esquenta|esfria|desembaca|evita|vai)( |$)"),
    )
    private val lowerFirst = mapOf(
        "en" to setOf("the", "a", "an", "to", "my", "mine", "both", "yours", "your", "his", "her", "our", "all", "front", "rear", "back", "left",
            "right", "passenger", "driver", "driver s", "just", "only", "some", "everyone", "everybody"),
        "pt" to setOf("o", "a", "os", "as", "pro", "pra", "para", "pros", "pras", "meu", "minha", "dois", "ambos", "todos", "todas", "do", "da",
            "no", "na", "so", "apenas", "frente", "tras", "esquerdo", "direito", "passageiro", "motorista", "um", "uma", "algum", "alguma"),
    )

    /** "Stop" while music plays means the music. */
    private val stopMusic = mapOf(
        "en" to listOf("stop", "stop it", "pause", "pause it", "stop the music", "pause the music", "stop music", "stop playing", "enough", "please stop", "stop please"),
        "pt" to listOf("para", "pare", "pausa", "pausar", "para a musica", "para o som", "pausa a musica", "para de tocar", "chega", "para por favor", "por favor para"),
    )

    /** The seat in an answer to "Which seat: yours, the passenger's, or both?" (checked in this order on the folded answer). */
    private val seatAnswer = mapOf(
        "en" to listOf(
            Regex("\\b(both|everyone|all|the two|both of us)\\b") to "both",
            Regex("\\b(passenger|passenger s|his|hers|her|him|the other one|my wife s|my husband s)\\b") to "passenger",
            Regex("\\b(mine|me|my|myself|driver|driver s|yours)\\b") to "driver",
        ),
        "pt" to listOf(
            Regex("\\b(os dois|ambos|todos|dos dois|nos dois|as duas)\\b") to "both",
            Regex("\\b(carona|passageiro|dele|dela|o outro)\\b") to "passenger",
            Regex("\\b(meu|o meu|eu|mim|motorista|o seu)\\b") to "driver",
        ),
    )
    private val seatWords = mapOf(
        "en" to mapOf("driver" to "the driver's seat", "passenger" to "the passenger seat", "both" to "both front seats"),
        "pt" to mapOf("driver" to "o banco do motorista", "passenger" to "o banco do passageiro", "both" to "os dois bancos da frente"),
    )

    /** The request with the seat put in (null: no seat word to replace). */
    private fun withSeat(request: String, seat: String, lang: String): String? {
        val r = request.trim().replace(Regex("[.!?]+$"), "")
        val f = fold(r)
        val off = if (lang == "en") Regex("\\b(off|stop|disable|deactivate)\\b").containsMatchIn(f)
        else Regex("\\b(desliga|desligar|desligue|desativa|para)\\b").containsMatchIn(f)
        val level = Regex("\\b(low|medium|high|max|maximum|min|minimum|level|baixo|medio|alto|maximo|minimo|nivel|\\d+)\\b").containsMatchIn(f)
        val heat = if (lang == "en") Regex("\\b(heat|heater|heaters|heated|heating|warm|warmer)\\b").containsMatchIn(f)
        else Regex("\\b(esquent\\w*|aquec\\w*|quent\\w*)\\b").containsMatchIn(f)
        val cool = if (lang == "en") Regex("\\b(cool|cooler|cooling|vent|ventilate|ventilation|ventilated)\\b").containsMatchIn(f)
        else Regex("\\b(ventil\\w*|refresc\\w*|esfri\\w*|fresc\\w*)\\b").containsMatchIn(f)
        val words = seatWords[lang]!!
        if (!off && !level && heat != cool) {
            val verb = if (lang == "en") (if (heat) "heat" else "cool") else (if (heat) "esquenta" else "refresca")
            fun one(k: String) = "$verb ${words[k]}"
            val said = if (seat == "both") "${one("driver")} ${if (lang == "en") "and" else "e"} ${one("passenger")}" else one(seat)
            return said.substring(0, 1).uppercase() + said.substring(1)
        }
        val w = words[seat]!!
        fun swap(rx: Regex, to: (MatchResult) -> String): String? = if (rx.containsMatchIn(r)) r.replaceFirstMapped(rx, to) else null
        if (lang == "en") {
            return swap(re("\\b(the |my |our )?seats\\b", ignoreCase = true)) { w }
                ?: swap(re("\\b(the |my )?seat (heaters?|ventilation)\\b", ignoreCase = true)) { m ->
                    val what = m.gs(2)
                    if (seat == "both") "both front seat ${if (what.startsWith("heater")) "heaters" else "ventilation"}" else "$w ${what.replace(Regex("s$"), "")}"
                }
                ?: swap(re("\\b(the |my )?seat\\b", ignoreCase = true)) { w }
        }
        return swap(re("\\b(os|o|meus|meu) bancos?\\b", ignoreCase = true)) { w }
            ?: swap(re("\\bdos? bancos?\\b", ignoreCase = true)) { if (seat == "both") "dos dois bancos da frente" else "d$w" }
    }

    /** "Say that again": the last reply again. */
    private val repeat = mapOf(
        "en" to listOf("what", "what did you say", "what was that", "say that again", "say it again", "repeat", "repeat that", "repeat it", "come again",
            "pardon", "pardon me", "sorry what", "huh", "i didn t hear you", "i didn t hear that", "i didn t catch that", "can you repeat that",
            "could you repeat that", "can you say that again", "could you say that again", "please repeat", "one more time", "what was that again"),
        "pt" to listOf("o que", "o que voce disse", "o que vc disse", "o que foi", "o que foi que voce disse", "repete", "repete ai", "repete por favor",
            "pode repetir", "fala de novo", "fala de novo por favor", "como", "como e que e", "hein", "nao ouvi", "nao escutei", "nao entendi",
            "fala outra vez", "repete isso"),
    )

    /** "Next song" while music plays: this demo has no track control. */
    private val skip = mapOf(
        "en" to listOf("skip", "skip it", "skip this", "skip this song", "skip the song", "skip song", "next", "next song", "next track", "next one",
            "play the next song", "play the next one", "previous song", "previous track", "go back a song", "last song"),
        "pt" to listOf("pula", "pula essa", "pula essa musica", "pula a musica", "proxima", "proxima musica", "proxima faixa", "passa", "passa essa",
            "passa a musica", "musica anterior", "volta a musica", "toca a proxima"),
    )

    /** A bare "Turn it off" / "Desliga" with nothing to refer to. */
    private val bareOff = mapOf(
        "en" to listOf("turn it off", "turn that off", "switch it off", "turn off", "switch off", "shut it off", "off"),
        "pt" to listOf("desliga", "desliga isso", "desliga ele", "desliga ela", "desligar", "desliga ai", "apaga", "apaga isso"),
    )
    private val bareOn = mapOf(
        "en" to listOf("turn it on", "turn that on", "switch it on", "turn on", "switch on", "on"),
        "pt" to listOf("liga", "liga isso", "liga ele", "liga ela", "ligar", "liga ai", "acende", "acende isso"),
    )
    private val askWhat = mapOf(
        "en" to mapOf("off" to ("What should I turn off?" to "Turn off"), "on" to ("What should I turn on?" to "Turn on")),
        "pt" to mapOf("off" to ("O que você quer que eu desligue?" to "Desliga"), "on" to ("O que você quer que eu ligue?" to "Liga")),
    )
    private val whichSeat = mapOf("en" to "Which seat: yours, the passenger's, or both?", "pt" to "Qual banco: o seu, o do carona ou os dois?")

    /** The driver's words, said the way the model handles them (null: as they are). */
    private fun canonical(userText: String, lang: String): String? {
        val f = fold(userText)
        val t = userText.replace('’', '\'')
        if (lang == "en") {
            if (Regex("^(?:please )?(?:turn |put |set |crank )?(?:the )?(?:volume|music|sound|it) (?:all the way up|(?:up )?to (?:the )?max(?:imum)?|(?:at |on )?max(?:imum)?)(?: please)?$|^(?:please )?max(?:imum)? volume(?: please)?$").containsMatchIn(f)) {
                return "Set the volume to 30"
            }
            if (Regex("^(?:please )?turn on (?:the |our )?seat heaters (?:for )?(?:both of us|both|everyone)(?: please)?$").containsMatchIn(f)) {
                return "Heat the driver's seat and heat the passenger seat"
            }
            if (Regex("^(?:please )?lock (?:it )?(?:up|everything)(?: please)?$").containsMatchIn(f)) return "Lock the car"
            if (Regex("^(?:please )?close (?:everything|it all|all of it|up everything)(?: please)?$").containsMatchIn(f)) return "Close all the windows and the sunroof"
            if (Regex("^(?:please )?turn (?:on|off) the lights(?: please)?$").containsMatchIn(f)) {
                return if (f.contains(" off ")) "Turn off the cabin lights" else "Turn on the cabin lights"
            }
            val warm = Regex("^(?:a bit |a little |slightly )?(warmer|hotter|cooler|colder)(?: please)?$").find(f)
            if (warm != null) return if (Regex("warmer|hotter").containsMatchIn(warm.gs(1))) "Make it warmer" else "Make it cooler"
            if (Regex("^(?:is )?(?:everything|all) (?:ok|okay|alright|all right|fine|good) with the car$|^is the car (?:ok|okay|alright|all right|fine)$|^how s the car(?: doing)?$|^car status$").containsMatchIn(f)) {
                return "Are there any warning lights?"
            }
            // "No, I said the passenger window" was ignored (no reply at all); "No, the passenger window" runs
            val said = re("^(?:no,? |nope,? )?i (?:said|meant|mean)\\s+(.+)$", ignoreCase = true).find(t.trim())
            if (said != null && fold(said.gs(1)).split(' ').size <= 8) return "No, ${said.gs(1)}"
            val seat = re("\\bturn(ing)? on (the |my |our )?(driver's |drivers |passenger's |passenger )?seat (heater|heating|warmer)(s?)\\b", ignoreCase = true)
            if (seat.containsMatchIn(t) && !Regex("\\b(low|medium|high|max|maximum|level|\\d+)\\b").containsMatchIn(f)) {
                return t.replaceFirstMapped(seat) { m ->
                    val who = m.g(3)
                    val whose = if (who != null) (if (re("^driver", ignoreCase = true).containsMatchIn(who)) "the driver's " else "the passenger ") else (m.g(2) ?: "the ").lowercase()
                    "heat${if (m.g(1) != null) "ing" else ""} ${whose}seat${if ((m.g(5) ?: "").isNotEmpty() && who == null) "s" else ""}"
                }.replaceFirstLit(Regex("^heat"), "Heat")
            }
            return null
        }
        if (Regex("^(?:coloca |poe |bota |deixa |aumenta )?(?:o )?(?:volume|som)(?: (?:no|ao|ate o|em|pro|para o) (?:maximo|talo)| maximo)(?: por favor)?$|^aumenta (?:o volume |o som |tudo )?(?:no|ao|ate o) (?:maximo|talo)(?: por favor)?$").containsMatchIn(f)) {
            return "Coloca o volume em 30"
        }
        if (Regex("^(?:por favor )?fecha tudo(?: por favor)?$").containsMatchIn(f)) return "Fecha todos os vidros e o teto solar"
        if (Regex("^(?:por favor )?(?:acende|liga) as luzes(?: por favor)?$").containsMatchIn(f)) return "Liga a luz ambiente"
        if (Regex("^(?:da pra |pode |voce pode |vc pode )?deixa(?:r)? (?:o carro |aqui )?(?:um pouco |um pouquinho )?mais fresco(?: por favor)?$").containsMatchIn(f)) {
            return "Esfria um pouco"
        }
        if (Regex("^(?:(?:ta|esta) tudo (?:certo|bem|ok|em ordem) com o carro|o carro (?:ta|esta) (?:bem|ok|certo)|como (?:ta|esta) o carro)$").containsMatchIn(f)) {
            return "Tem alguma luz de alerta?"
        }
        // the "Você …?" rule runs last, so "Você liga o aquecimento do meu banco?" also loses its question form
        val pt = t
            .replaceFirstMapped(re("\\b(?:liga|ligar|ligue|ativa) o aquecimento do (meu |seu )?banco( do motorista| do passageiro| do carona)?\\b", ignoreCase = true)) { m ->
                val who = m.g(2)
                "esquenta o ${m.g(1) ?: ""}banco${if (who != null) (if (re("carona", ignoreCase = true).containsMatchIn(who)) " do passageiro" else who) else ""}"
            }
            .replaceFirstLit(re("\\b(?:liga|ligar|ligue) o banco aquecido\\b", ignoreCase = true), "esquenta o banco")
            .replaceFirstLit(
                re("\\b(?:liga|ligar|ligue) o aquecimento dos dois bancos( da frente)?\\b|\\besquenta os dois bancos( da frente)?\\b", ignoreCase = true),
                "esquenta o banco do motorista e esquenta o banco do passageiro",
            )
            .replaceFirstMapped(re("^(?:você|voce|vc) (esquenta|refresca)(.*?)\\?*$", ignoreCase = true)) { m -> "${m.gs(1)}${m.gs(2)}" }
        if (pt != t) return pt.substring(0, 1).uppercase() + pt.substring(1)
        return null
    }

    /** A heat / cool request for "the seats" with no seat said: asked here. The request to complete. */
    private fun pluralSeats(userText: String, lang: String): String? {
        if (lang != "pt") return null
        val f = fold(userText).replaceFirstLit(Regex(" por favor$"), "")
        if (Regex("^(?:liga|ligar|ligue|ativa) o aquecimento dos bancos$|^esquenta os bancos$|^(?:liga|ligar) os bancos aquecidos$").containsMatchIn(f)) {
            return "Esquenta os bancos"
        }
        if (Regex("^(?:liga|ligar|ligue|ativa) a ventilacao dos bancos$|^refresca os bancos$").containsMatchIn(f)) return "Refresca os bancos"
        return null
    }

    /** The driver says the offered request short ("Close it", "Lock it", "Turn it on", "Fecha", "Pode trancar"): the offer itself. */
    private val offerVerbs = mapOf(
        "en" to listOf(
            re("^close\\b", ignoreCase = true) to Regex("^(?:close|shut)$"),
            re("^lock\\b", ignoreCase = true) to Regex("^lock$"),
            re("^(?:heat|cool)\\b", ignoreCase = true) to Regex("^(?:turn on|switch on|heat|cool|turn on)$"),
        ),
        "pt" to listOf(
            re("^fecha\\b", ignoreCase = true) to Regex("^(?:fecha|fechar|feche|pode fechar)$"),
            re("^tranca\\b", ignoreCase = true) to Regex("^(?:tranca|trancar|tranque|pode trancar)$"),
            re("^(?:esquenta|refresca)\\b", ignoreCase = true) to Regex("^(?:liga|ligar|ligue|pode ligar|esquenta|refresca)$"),
        ),
    )

    private fun sameRequest(userText: String, request: String, lang: String): Boolean {
        val verb = offerVerbs[lang]!!.firstOrNull { it.first.containsMatchIn(request) }?.second ?: return false
        val f = fold(userText)
            .replaceFirstLit(Regex("^(?:yes|yeah|sure|ok|okay|please|sim|claro|pode|ok|beleza) "), "")
            .replaceFirstLit(Regex(" (?:please|por favor|pra mim|para mim)$"), "")
            .replaceFirstMapped(Regex("^(turn|switch) (?:it|them) on$")) { "${it.gs(1)} on" }
            .replaceFirstLit(Regex(" (?:it|them|that|ele|eles|ela|elas|isso)$"), "")
        return verb.containsMatchIn(f)
    }

    private data class Pending(val kind: String, val request: String)

    private var pending: Pending? = null
    private var dismissVariant = 0
    private var lastSpoken = ""

    /** For tests. */
    fun reset() {
        pending = null
        dismissVariant = 0
        lastSpoken = ""
    }

    /** What the driver last heard (said again for "Say that again"). */
    fun noteCarSpoken(text: String) {
        if (text.trim().isNotEmpty()) lastSpoken = text.trim()
    }

    /** What goes to the agent for what the driver said, or the app's own short reply. [offer]: the calls a plain yes would run now. */
    fun carTurnInput(userText: String, language: String, offer: List<String>?, musicOn: Boolean = false, state: CarState? = null, lastActed: Boolean? = null): TurnInput {
        val lang = tuned(language) ?: return TurnInput(text = BasicLanguage.normalizeYesNo(userText, language) ?: BasicLanguage.withCarHints(userText, language))
        val asked = pending
        pending = null
        // "Say that again": what was said, and the question in it still waits for its answer
        if (lastSpoken.isNotEmpty() && only(userText, repeat[lang]!!)) {
            pending = asked
            return TurnInput(reply = lastSpoken)
        }
        // the app's own offer (an answer about the car: "Want me to lock it?"): a yes is that request
        if (asked != null && asked.kind == "command" && (only(userText, yes[lang]!!) || sameRequest(userText, asked.request, lang))) {
            return TurnInput(text = asked.request)
        }
        // a question about the car: answered from the car (the model ran the action)
        val known = if (state == null) null else StateAnswers.answer(userText, language, state)
        if (known != null) {
            if (known.offer != null) pending = Pending("command", known.offer)
            else if (known.askDestination) pending = Pending("destination", "")
            return TurnInput(reply = known.reply)
        }
        val yesWord = if (lang == "pt") "sim" else "yes"
        // music playing, "next song": not in this demo
        if (musicOn && only(userText, skip[lang]!!)) return TurnInput(reply = notInDemoTexts[language] ?: notInDemoTexts["en"]!!)
        // music playing: "stop" / "para" means the music
        if (musicOn && offer.isNullOrEmpty() && only(userText, stopMusic[lang]!!)) {
            return TurnInput(text = if (lang == "pt") "Desliga o som" else "Stop the music")
        }
        if (!offer.isNullOrEmpty()) {
            if (only(userText, yes[lang]!!)) return TurnInput(text = yesWord)
            if ("navigate_to" in offer && only(userText, goThere[lang]!!)) return TurnInput(text = yesWord)
            if (only(userText, dismiss[lang]!!) && containsAny(userText, dismissCore[lang]!!)) return TurnInput(text = if (lang == "pt") "não" else "no")
            return TurnInput(text = userText)
        }
        if (only(userText, dismiss[lang]!!) && containsAny(userText, dismissCore[lang]!!)) {
            val list = dismissReply[lang]!!
            return TurnInput(reply = list[dismissVariant++ % list.size])
        }
        // "Turn it off" with nothing it can mean (no music, the last request changed nothing): asked, not guessed
        if (!musicOn && lastActed == false && asked == null) {
            val which = if (only(userText, bareOff[lang]!!)) "off" else if (only(userText, bareOn[lang]!!)) "on" else null
            if (which != null) {
                val (question, verb) = askWhat[lang]!![which]!!
                pending = Pending("verb", verb)
                return TurnInput(reply = question)
            }
        }
        if (asked != null) {
            val a = fold(userText)
            val words = if (a.isEmpty()) 0 else a.split(' ').size
            if (words in 1..5 && !commandStart[lang]!!.containsMatchIn(a) && !only(userText, yes[lang]!!)) {
                // "The driver's one" -> "the driver's one" inside the request (a name such as "Coldplay" keeps its capital)
                val saidText = userText.trim().replace(Regex("[.!?]+$"), "")
                val answer = if (fold(saidText.split(' ').first()) in lowerFirst[lang]!!) saidText.substring(0, 1).lowercase() + saidText.substring(1) else saidText
                when (asked.kind) {
                    "destination" -> return TurnInput(
                        text = if (lang == "pt") {
                            if (re("^(pra|pro|para|pras|pros)\\b", ignoreCase = true).containsMatchIn(answer)) "Navega $answer" else "Navega para $answer"
                        } else if (re("^to\\b", ignoreCase = true).containsMatchIn(answer)) "Navigate $answer" else "Navigate to $answer",
                    )
                    "media" -> return TurnInput(text = "${if (lang == "pt") "Toca" else "Play"} $answer")
                    "verb" -> return TurnInput(text = "${asked.request} $answer")
                    "seat" -> {
                        // the core asks again unless the request names one seat: the seat goes into the request
                        val seat = seatAnswer[lang]!!.firstOrNull { it.first.containsMatchIn(a) }?.second
                        val rewritten = if (seat == null) null else withSeat(asked.request, seat, lang)
                        if (rewritten != null) return TurnInput(text = rewritten)
                    }
                }
                if (asked.kind != "command") return TurnInput(text = "${asked.request.trim().replace(Regex("[.!?,;:]+$"), "")}, $answer")
            }
        }
        val seats = pluralSeats(userText, lang)
        if (seats != null) {
            pending = Pending("seat", seats)
            return TurnInput(reply = whichSeat[lang])
        }
        return TurnInput(text = canonical(userText, lang) ?: userText)
    }

    /** After the agent's reply: remember a question the next short answer completes. [sent] = what went to the agent for this request. */
    fun noteCarReply(sent: String, reply: String, language: String) {
        pending = null
        if (tuned(language) == null) return
        val r = reply.trim()
        if (!r.endsWith("?")) return
        val last = (Regex("[^.!?]*\\?$").find(r)?.value ?: r).trim()
        pending = when {
            re("where (?:do you want|would you like) to go\\?$|para onde (?:você |vc )?quer ir\\?$", ignoreCase = true).containsMatchIn(last) -> Pending("destination", sent)
            re("what (?:do you want|would you like) to (?:hear|listen to)\\?$|o que (?:você |vc )?quer ouvir\\?$", ignoreCase = true).containsMatchIn(last) -> Pending("media", sent)
            re("^(?:which seat|qual banco)\\b", ignoreCase = true).containsMatchIn(last) -> Pending("seat", sent)
            re("\\b(?:which|qual|quais)\\b", ignoreCase = true).containsMatchIn(last) && !re("\\b(?:again|repetir|de novo)\\b", ignoreCase = true).containsMatchIn(last) -> Pending("merge", sent)
            else -> null
        }
    }
}
