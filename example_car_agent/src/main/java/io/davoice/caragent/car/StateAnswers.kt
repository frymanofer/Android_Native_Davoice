package io.davoice.caragent.car

import io.davoice.caragent.util.Json

// A question about the car's state is answered from the car itself (port of the Flutter / React Native car apps' state answers; shared
// fixtures state_answers.json and car_dialogs.json): the model RAN the action for a question. "Is the car locked?" locked it, "Are the
// windows closed?" closed them. The answer says the state and offers the obvious change ("No, the car is unlocked. Want me to lock
// it?"): a yes sends that request to the agent (FollowUp), so the model runs a request it handles; nothing runs here. Only a question
// (question words, a state word and a part of the car together) is answered; anything else goes to the agent as before. Pure: the
// language and the car state are parameters. Tuned languages (en, pt-BR) only.

/** The reply, the request a plain yes sends to the agent (null: nothing offered), and whether the reply asks where to go. */
data class StateAnswer(val reply: String, val offer: String? = null, val askDestination: Boolean = false)

object StateAnswers {
    private val cache = HashMap<String, Regex>()
    private fun rx(p: String) = cache.getOrPut(p) { Regex(p) }
    private fun has(f: String, p: String) = rx(p).containsMatchIn(f)

    private fun list(items: List<String>, lang: String): String =
        if (items.size <= 1) items.joinToString("") else "${items.dropLast(1).joinToString(", ")} ${if (lang == "en") "and" else "e"} ${items.last()}"

    /** A position as the driver says it: the driver's / passenger window (door) by the car's steering side, else its place. */
    private fun posName(p: String, obj: String, l: String, s: CarState): String {
        val driver = resolvePositions("driver", s.steering)!!.first()
        val passenger = resolvePositions("passenger", s.steering)!!.first()
        return positionName(if (p == driver) "driver" else if (p == passenger) "passenger" else p, obj, l).text
    }

    private const val FILLER = "^(?:(?:hey|so|um|uh|hmm|ok|okay|oi|ei|e ai|hum|entao|tipo) )*"
    private const val EN_Q = "^(?:is|are|was|were|did|do|does|have|has|what|what s|whats|where|how|which|can you (?:check|tell me|see)|could you (?:check|tell me|see)|do you know|i wonder|tell me)\\b"
    private const val PT_Q = "^(?:sera que|voce sabe|vc sabe|sabe|qual|quais|quanto|quantos|em quanto|que|o que|pra onde|para onde|onde|como|eu (?:tranquei|fechei|deixei|travei)|tranquei|fechei|deixei|travei|(?:(?:o|a|os|as) )?(?:meu |minha |meus |minhas )?(?:carro|vidro|vidros|janela|janelas|porta|portas|teto|aquecimento|ventilacao|banco|volume|som|temperatura|ar)\\b)"
    private const val EN_WH = "^(?:what s|whats|what is|what|how|which|tell me|can you tell me|could you tell me|do you know)\\b"
    private const val PT_WH = "^(?:qual|quais|em quanto|quanto|como|sera que|voce sabe|vc sabe|sabe|(?:ta|esta) em quanto)\\b"

    /** A question with a request after it ("What's the temperature? Set it to 22") goes to the agent. */
    private const val EN_AND_DO = "\\b(?:set it|turn it|make it|put it|change it|lock it|close it|open it|close them|open them|and (?:set|turn|make|open|close|lock|unlock|play|raise|lower)|then)\\b"
    private const val PT_AND_DO = "\\b(?:coloca|poe|bota|muda|aumenta|abaixa|abre|fecha|tranca|destranca|liga|desliga|toca|esquenta|refresca)\\b"

    /** Polite requests are requests ("Can you open the window?"): not questions about the state. */
    private const val EN_REQUEST = "^(?:can|could|would|will) you (?!check|tell me|see)|^would you mind|^please\\b"

    private fun isQuestion(raw: String, f: String, lang: String): Boolean {
        val t = f.replaceFirst(rx(FILLER), "")
        val endsQ = Regex("\\?\\s*$").containsMatchIn(raw)
        if (lang == "en") return !has(t, EN_REQUEST) && (has(t, EN_Q) || endsQ)
        return has(t, PT_Q) || (endsQ &&
            !has(t, "^(?:abre|fecha|tranca|destranca|liga|desliga|deixa|coloca|poe|bota|aumenta|abaixa|esquenta|refresca|pode|voce pode|vc pode|da pra)\\b"))
    }

    private fun windowTarget(f: String, lang: String): String {
        if (lang == "en") {
            if (has(f, "\\b(my window|my side|driver s|drivers|driver)\\b")) return "driver"
            if (has(f, "\\b(passenger|passenger s)\\b")) return "passenger"
            if (has(f, "\\b(rear|back)\\b")) return "rear"
            if (has(f, "\\bfront\\b")) return "front"
            return "all"
        }
        if (has(f, "\\b(meu vidro|minha janela|meu lado|motorista)\\b")) return "driver"
        if (has(f, "\\b(passageiro|carona)\\b")) return "passenger"
        if (has(f, "\\b(de tras|traseiros?|traseiras?)\\b")) return "rear"
        if (has(f, "\\bda frente\\b")) return "front"
        return "all"
    }

    private fun seatOf(f: String, lang: String) = if (has(f, if (lang == "en") "\\b(passenger|passenger s)\\b" else "\\b(passageiro|carona)\\b")) "passenger" else "driver"

    private val enLevel = mapOf("low" to "low", "medium" to "medium", "high" to "high")
    private val ptLevel = mapOf("low" to "baixo", "medium" to "médio", "high" to "alto")
    private val enSeatName = mapOf("driver" to "the driver's seat", "passenger" to "the passenger seat")
    private val ptSeatName = mapOf("driver" to "do banco do motorista", "passenger" to "do banco do passageiro")

    private fun lock(s: CarState, lang: String, askedUnlocked: Boolean): StateAnswer {
        val open = positions.filter { s.doorLocks[it] != true }
        val all = open.isEmpty()
        val none = open.size == 4
        val yes = if (askedUnlocked) !all else all
        if (lang == "en") {
            val offer = "Lock the car"
            if (all) return StateAnswer("${if (yes) "Yes" else "No"}, the car is locked.")
            if (none) return StateAnswer("${if (yes) "Yes" else "No"}, the car is unlocked. Want me to lock it?", offer)
            val doors = open.map { posName(it, "door", "en", s) }
            return StateAnswer("Not completely: ${list(doors, lang)} ${if (doors.size > 1) "are" else "is"} unlocked. Want me to lock the car?", offer)
        }
        val offer = "Tranca o carro"
        if (all) return StateAnswer("${if (yes) "Sim" else "Não"}, o carro está trancado.")
        if (none) return StateAnswer("${if (yes) "Sim" else "Não"}, o carro está destrancado. Quer que eu tranque?", offer)
        val doors = open.map { posName(it, "door", "pt-BR", s) }
        return StateAnswer("Não totalmente: ${list(doors, lang)} ${if (doors.size > 1) "estão destrancadas" else "está destrancada"}. Quer que eu tranque o carro?", offer)
    }

    private fun allThe(text: String, lang: String): String = if (lang == "en")
        text.replaceFirst(Regex("^the "), "all the ").replaceFirst("all the all the", "all the")
    else text.replaceFirst(Regex("^os "), "todos os ").replaceFirst("todos os todos os", "todos os")

    private fun windows(s: CarState, lang: String, target: String, askedOpen: Boolean): StateAnswer {
        val l = if (lang == "en") "en" else "pt-BR"
        val ps = resolvePositions(target, s.steering) ?: emptyList()
        val open = ps.filter { s.windows[it] != "close" }
        val n = positionName(target, "window", l)
        if (ps.size == 1) {
            val isOpen = open.size == 1
            val yes = askedOpen == isOpen
            if (lang == "en") {
                return if (isOpen) StateAnswer("${if (yes) "Yes" else "No"}, ${n.text} is open. Want me to close it?", "Close ${n.text}")
                else StateAnswer("${if (yes) "Yes" else "No"}, ${n.text} is closed.")
            }
            return if (isOpen) StateAnswer("${if (yes) "Sim" else "Não"}, ${n.text} está aberto. Quer que eu feche?", "Fecha ${n.text}")
            else StateAnswer("${if (yes) "Sim" else "Não"}, ${n.text} está fechado.")
        }
        if (open.isEmpty()) {
            return if (lang == "en") StateAnswer("${if (askedOpen) "No" else "Yes"}, ${allThe(n.text, lang)} are closed.")
            else StateAnswer("${if (askedOpen) "Não" else "Sim"}, ${allThe(n.text, lang)} estão fechados.")
        }
        val names = open.map { posName(it, "window", l, s) }
        val one = open.size == 1
        val close = if (one) "${if (lang == "en") "Close" else "Fecha"} ${names[0]}" else "${if (lang == "en") "Close" else "Fecha"} ${n.text}"
        val allOpen = open.size == ps.size
        if (lang == "en") {
            val what = if (allOpen) "${allThe(n.text, lang)} are open" else "${list(names, lang)} ${if (one) "is" else "are"} open"
            return StateAnswer("${if (askedOpen) "Yes" else "No"}, $what. Want me to close ${if (one) "it" else "them"}?", close)
        }
        val what = if (allOpen) "${allThe(n.text, lang)} estão abertos" else "${list(names, lang)} ${if (one) "está aberto" else "estão abertos"}"
        return StateAnswer("${if (askedOpen) "Sim" else "Não"}, $what. Quer que eu feche?", close)
    }

    private fun doors(s: CarState, lang: String, askedOpen: Boolean): StateAnswer {
        val open = positions.filter { s.doors[it] == "open" }
        if (open.isEmpty()) {
            return StateAnswer(if (lang == "en") "${if (askedOpen) "No" else "Yes"}, all the doors are closed." else "${if (askedOpen) "Não" else "Sim"}, todas as portas estão fechadas.")
        }
        val l = if (lang == "en") "en" else "pt-BR"
        val names = open.map { posName(it, "door", l, s) }
        val one = open.size == 1
        if (lang == "en") {
            return StateAnswer("${if (askedOpen) "Yes" else "No"}, ${list(names, lang)} ${if (one) "is" else "are"} open. Want me to close ${if (one) "it" else "them"}?", "Close the doors")
        }
        val n = positionName(open[0], "door", l)
        return StateAnswer("${if (askedOpen) "Sim" else "Não"}, ${list(names, lang)} ${if (one) "está ${adj("abert", n)}" else "estão abertas"}. Quer que eu feche?", "Fecha as portas")
    }

    private fun sunroof(s: CarState, lang: String, askedOpen: Boolean): StateAnswer {
        val isOpen = s.sunroof != "close"
        val yes = askedOpen == isOpen
        if (lang == "en") {
            if (!isOpen) return StateAnswer("${if (yes) "Yes" else "No"}, the sunroof is closed.")
            return StateAnswer("${if (yes) "Yes" else "No"}, the sunroof is ${if (s.sunroof == "tilt") "tilted open" else "open"}. Want me to close it?", "Close the sunroof")
        }
        if (!isOpen) return StateAnswer("${if (yes) "Sim" else "Não"}, o teto solar está fechado.")
        return StateAnswer("${if (yes) "Sim" else "Não"}, o teto solar está ${if (s.sunroof == "tilt") "inclinado" else "aberto"}. Quer que eu feche?", "Fecha o teto solar")
    }

    private fun seat(s: CarState, lang: String, seat: String, feature: String, askedOn: Boolean): StateAnswer {
        val level = (if (feature == "heater") s.seatHeater else s.seatVentilation)[seat] ?: "off"
        val on = level != "off"
        val yes = askedOn == on
        if (lang == "en") {
            val what = "${enSeatName[seat]} ${if (feature == "heater") "heater" else "ventilation"}"
            if (on) return StateAnswer("${if (yes) "Yes" else "No"}, $what is on ${enLevel[level]}.")
            return StateAnswer("${if (yes) "Yes" else "No"}, $what is off. Want me to turn it on?", "${if (feature == "heater") "Heat" else "Cool"} ${enSeatName[seat]}")
        }
        val what = "${if (feature == "heater") "o aquecimento" else "a ventilação"} ${ptSeatName[seat]}"
        if (on) return StateAnswer("${if (yes) "Sim" else "Não"}, $what está ${if (feature == "heater") "ligado" else "ligada"} no ${ptLevel[level]}.")
        return StateAnswer(
            "${if (yes) "Sim" else "Não"}, $what está ${if (feature == "heater") "desligado" else "desligada"}. Quer que eu ligue?",
            "${if (feature == "heater") "Esquenta" else "Refresca"} o banco do ${if (seat == "driver") "motorista" else "passageiro"}",
        )
    }

    private fun num(v: Double, lang: String): String {
        val t = Json.num(v)
        return if (lang == "pt") t.replace('.', ',') else t
    }

    private fun temperature(s: CarState, lang: String): StateAnswer {
        val u = if (s.unit == "fahrenheit") "°F" else "°C"
        val d = s.temperature["driver"] ?: 21.0
        val p = s.temperature["passenger"] ?: 21.0
        if (d == p) return StateAnswer(if (lang == "en") "It's set to ${num(d, lang)}$u." else "Está em ${num(d, lang)}$u.")
        return StateAnswer(if (lang == "en") "The driver's side is set to ${num(d, lang)}$u and the passenger side to ${num(p, lang)}$u."
        else "O lado do motorista está em ${num(d, lang)}$u e o do passageiro em ${num(p, lang)}$u.")
    }

    private fun media(s: CarState, lang: String): StateAnswer {
        val muted = if (s.muted) (if (lang == "en") ", but the sound is muted" else ", mas o som está no mudo") else ""
        if (s.media != null) return StateAnswer(if (lang == "en") "You're listening to ${s.media}$muted." else "Você está ouvindo ${s.media}$muted.")
        if (s.radio != null) return StateAnswer(if (lang == "en") "You're listening to ${s.radio}$muted." else "Você está ouvindo a rádio ${s.radio}$muted.")
        return StateAnswer(if (lang == "en") "Nothing's playing right now." else "Não tem nada tocando agora.")
    }

    private fun destination(s: CarState, lang: String): StateAnswer {
        val d = s.destination ?: return StateAnswer(
            if (lang == "en") "There's no active route. Where do you want to go?" else "Não há nenhuma rota ativa. Para onde você quer ir?",
            null, askDestination = true,
        )
        if (lang == "en") {
            if (d == "home") return StateAnswer("We're heading home.")
            val p = if (Regex("^[a-z]").containsMatchIn(d) && !Regex("^(the|a|an|my|your|our|work|downtown)\\b").containsMatchIn(d)) "the $d" else d
            return StateAnswer("We're heading to $p.")
        }
        if (d == "casa") return StateAnswer("Estamos indo para casa.")
        val p = if (Regex("^[a-zà-ü]").containsMatchIn(d) && !Regex("^(o|a|os|as|um|uma|meu|minha)\\b").containsMatchIn(d))
            "${if (Regex("(a|ção|dade|gem)$").containsMatchIn(d.split(' ').first())) "a" else "o"} $d" else d
        return StateAnswer("Estamos indo para $p.")
    }

    /** The answer to a question about the car's state, or null (not such a question: the agent gets it). */
    fun answer(userText: String, language: String, s: CarState): StateAnswer? {
        val lang = when (language) {
            "en" -> "en"
            "pt-BR" -> "pt"
            else -> return null
        }
        val f = BasicLanguage.fold(userText)
        if (f.isEmpty() || f.split(' ').size > 14 || !isQuestion(userText, f, lang)) return null
        // a second sentence ("Is the car locked? Lock it") or a request in it: the agent decides
        if (Regex("[.?!;]\\s*\\S").containsMatchIn(userText.trim().replaceFirst(Regex("[.?!]+$"), "")) || has(f, if (lang == "en") EN_AND_DO else PT_AND_DO)) {
            return null
        }
        val t = f.replaceFirst(rx(FILLER), "")
        if (lang == "en") {
            val outside = has(f, "\\b(outside|outdoors?|out there|weather|today|tomorrow|tonight|forecast)\\b")
            if (has(f, "\\b(locked|unlocked)\\b") || has(f, "\\b(?:did|have) (?:i|you) lock\\b")) return lock(s, lang, has(f, "\\bunlocked\\b"))
            if (has(f, "\\bwindows?\\b") && has(f, "\\b(open|opened|closed|shut|up|down|cracked)\\b")) {
                return windows(s, lang, windowTarget(f, lang), has(f, "\\b(open|opened|down|cracked)\\b"))
            }
            if (has(f, "\\bdoors?\\b") && has(f, "\\b(open|opened|closed|shut|ajar)\\b")) return doors(s, lang, has(f, "\\b(open|opened|ajar)\\b"))
            if (has(f, "\\bsun ?roof\\b") && has(f, "\\b(open|opened|closed|shut)\\b")) return sunroof(s, lang, has(f, "\\b(open|opened)\\b"))
            if (has(f, "\\bseats?\\b") && has(f, "\\b(heater|heaters|heating|heated|warmer|warmers)\\b") && has(f, "\\b(on|off|running|working)\\b")) {
                return seat(s, lang, seatOf(f, lang), "heater", !has(f, "\\boff\\b"))
            }
            if (has(f, "\\bseats?\\b") && has(f, "\\b(ventilation|ventilated|cooling|cooler|fans?)\\b") && has(f, "\\b(on|off|running|working)\\b")) {
                return seat(s, lang, seatOf(f, lang), "ventilation", !has(f, "\\boff\\b"))
            }
            if (!outside && (has(f, "\\b(temperature|temp)\\b") && has(t, EN_WH) || has(t, "^how (?:warm|hot|cold) is it (?:set|in here)\\b"))) {
                return temperature(s, lang)
            }
            if (has(t, EN_WH) && has(t, "\\bvolume\\b|^how loud\\b")) return StateAnswer(if (s.muted) "The sound is muted." else "The volume is at ${s.volume}.")
            if (has(t, EN_WH) && has(f, "\\bfan\\b") && !has(f, "\\bseat\\b")) return StateAnswer("The fan is at level ${s.fan}.")
            if (has(t, "^(?:what s playing|whats playing|what is playing|what song is (?:this|playing)|what am i listening to|what station is (?:this|on)|what s on the radio|who s (?:singing|playing)|who is (?:singing|playing)|what music is (?:this|playing))\\b")) {
                return media(s, lang)
            }
            if (has(t, "^(?:where are we (?:going|headed|heading)|what s (?:my|our|the) destination|what is (?:my|our|the) destination|where am i (?:going|headed|heading))\\b")) {
                return destination(s, lang)
            }
            return null
        }
        val outside = has(f, "\\b(la fora|fora|lado de fora|tempo|clima|hoje|amanha|previsao)\\b")
        if (has(f, "\\b(trancad[oa]s?|destrancad[oa]s?|travad[oa]s?|destravad[oa]s?|tranquei|travei|trancou|travou)\\b")) {
            return lock(s, lang, has(f, "\\b(destrancad|destravad)"))
        }
        if (has(f, "\\bcarro\\b") && has(f, "\\b(abert[oa]|fechad[oa])\\b") && !has(f, "\\b(vidros?|janelas?|portas?|teto)\\b")) {
            return lock(s, lang, has(f, "\\babert"))
        }
        if (has(f, "\\b(vidros?|janelas?)\\b") && has(f, "\\b(abert[oa]s?|fechad[oa]s?|abaixad[oa]s?|levantad[oa]s?|fechei)\\b")) {
            return windows(s, lang, windowTarget(f, lang), has(f, "\\b(abert|abaixad)"))
        }
        if (has(f, "\\bportas?\\b") && has(f, "\\b(abert[oa]s?|fechad[oa]s?|fechei)\\b")) return doors(s, lang, has(f, "\\babert"))
        if (has(f, "\\bteto( solar)?\\b") && has(f, "\\b(abert[oa]|fechad[oa])\\b")) return sunroof(s, lang, has(f, "\\babert"))
        if (has(f, "\\bbancos?\\b") && has(f, "\\b(aquecimento|aquecid[oa]|esquentando)\\b") && has(f, "\\b(ligad[oa]|desligad[oa]|esquentando|funcionando)\\b")) {
            return seat(s, lang, seatOf(f, lang), "heater", !has(f, "\\bdesligad"))
        }
        if (has(f, "\\bbancos?\\b") && has(f, "\\b(ventilacao|ventilad[oa])\\b") && has(f, "\\b(ligad[oa]|desligad[oa]|funcionando)\\b")) {
            return seat(s, lang, seatOf(f, lang), "ventilation", !has(f, "\\bdesligad"))
        }
        if (!outside && (has(f, "\\btemperatura\\b") && has(t, PT_WH) || has(t, "^(?:ta|esta) em quantos graus\\b|^quantos graus\\b"))) {
            return temperature(s, lang)
        }
        if (has(f, "\\b(volume|som)\\b") && has(t, PT_WH) && !has(f, "\\b(musica|tocando)\\b")) {
            return StateAnswer(if (s.muted) "O som está no mudo." else "O volume está em ${s.volume}.")
        }
        if (has(t, PT_WH) && has(f, "\\b(ventilacao|ventilador)\\b") && !has(f, "\\bbanco")) return StateAnswer("A ventilação está no nível ${s.fan}.")
        if (has(t, "^(?:o que (?:ta|esta) tocando|que musica (?:e essa|e esta|ta tocando|esta tocando)|qual (?:e )?(?:a )?musica|que radio (?:e essa|e esta)|qual (?:e )?(?:a )?radio|qual (?:e )?(?:a )?estacao|o que (?:eu )?(?:to|tou|estou) ouvindo|quem (?:ta|esta) cantando|quem canta)\\b")) {
            return media(s, lang)
        }
        if (has(t, "^(?:pra onde|para onde|onde) (?:a gente |nos )?(?:ta|esta|estamos|tamos|vamos|vai)(?: indo)?\\b|^qual (?:e )?o (?:meu |nosso )?destino\\b")) {
            return destination(s, lang)
        }
        return null
    }
}
