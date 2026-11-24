package io.davoice.caragent.car

import android.util.Log
import io.davoice.caragent.agent.ToolDefinition
import io.davoice.caragent.agent.ToolReply
import io.davoice.caragent.flow.AppLanguage
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asList
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.cap
import io.davoice.caragent.util.numOf
import io.davoice.caragent.util.str

// The DaVoice Car tools for the agent (port of the Flutter / React Native car apps' car tools): definitions from assets/car/*.json
// (the same files as the other apps), handlers that change the simulated car. Each call is recorded for the car screen. The app says
// what it did (ToolReply: a sentence naming the action, position, zone and value - success, already in that state, or refused) and
// the turn ends there; nothing is claimed before the handler returns.

object CarTools {
    // what the agent was given this turn: the handlers check copied titles against it
    private var lastUserText = ""
    fun noteUserText(text: String) {
        lastUserText = text
    }

    private class Outcome(val summary: String, val result: Obj, val say: String? = null)

    /** Refused by the app: nothing changes; the message is a full sentence the user hears (the turn ends there). [offer]: what a plain
     *  "yes" to the refusal's question means (the agent core runs these calls on the user's yes, without the model). */
    private class Refused(override val message: String, val offer: List<Obj>? = null) : Exception(message)

    private fun call(name: String, door: Any? = null): Obj = mapOf("name" to name, "arguments" to if (door == null) emptyMap<String, Any?>() else mapOf("door" to door))

    private val pt get() = AppLanguage.current == "pt-BR"
    private fun l(en: String, pt: String) = if (this.pt) pt else en
    private val lang get() = if (pt) "pt-BR" else "en"

    private val ptSeat = mapOf("driver" to "motorista", "passenger" to "passageiro", "rear_left" to "traseiro esquerdo", "rear_right" to "traseiro direito")
    private val ptLevel = mapOf("off" to "desligado", "low" to "baixo", "medium" to "médio", "high" to "alto")
    private val ptPlace = mapOf("gas_station" to "posto de combustível", "ev_charger" to "carregador", "parking" to "estacionamento", "restaurant" to "restaurante", "coffee" to "café", "rest_area" to "parada")
    private val ptAvoid = mapOf("tolls" to "pedágios", "traffic" to "trânsito", "highways" to "rodovias", "ferries" to "balsas")
    private val ptAir = mapOf("face" to "rosto", "feet" to "pés", "face_and_feet" to "rosto e pés", "windshield" to "para-brisa")
    private val ptAirArt = mapOf("face" to "o rosto", "feet" to "os pés", "face_and_feet" to "o rosto e os pés", "windshield" to "o para-brisa")
    private val enSeat = mapOf("driver" to "the driver's seat", "passenger" to "the passenger seat", "rear_left" to "the rear left seat", "rear_right" to "the rear right seat")
    private val enPlace = mapOf("gas_station" to "a gas station", "ev_charger" to "an EV charger", "parking" to "a parking spot", "restaurant" to "a restaurant", "coffee" to "a coffee shop", "rest_area" to "a rest area")
    private val ptPlaceSay = mapOf("gas_station" to "um posto de combustível", "ev_charger" to "um carregador", "parking" to "um estacionamento", "restaurant" to "um restaurante", "coffee" to "um café", "rest_area" to "uma parada")
    private val enAir = mapOf("face" to "your face", "feet" to "your feet", "face_and_feet" to "your face and feet", "windshield" to "the windshield")

    private fun words(s: Any?) = str(s).replace('_', ' ')

    /** A number as the other apps print it: 22 (not 22.0), 22.5; pt-BR with a comma. */
    private fun n(v: Double): String {
        val s = Json.num(v)
        return if (pt) s.replace('.', ',') else s
    }

    private fun done(summary: String, extra: Obj = emptyMap(), say: String? = null) = Outcome(summary, mapOf("status" to "Done: $summary") + extra, say)

    /** Nothing to do: the car is already in the requested state (said as such; nothing is claimed as done). */
    private fun already(summary: String, say: String, extra: Obj = emptyMap()) = Outcome(summary, mapOf("already" to true) + extra, say)

    /** A lookup answered by the app itself (no second model round that could only say "Done."). */
    private fun answer(summary: String, result: Obj, say: String) = Outcome(summary, result, say)

    private val s get() = Car.state

    /** The positions a window / door argument means in this car (steering-wheel side from the vehicle configuration). */
    private fun positionsOf(target: Any?, obj: String): List<String> = resolvePositions(target, s.steering)
        ?: throw Refused(l("I don't know which $obj that is. Which one do you mean?", "Não sei qual ${if (obj == "door") "porta" else "vidro"} é esse. Qual você quer?"))

    private fun nm(target: Any?, obj: String) = positionName(target, obj, lang)
    private fun degrees(t: Double, unit: String) = "${n(t)}°${if (unit == "fahrenheit") "F" else "C"}"

    /** Not while the car moves (the screen's Parked / Driving switch). */
    private val notWhileDriving: Map<String, (Obj) -> Boolean> = mapOf(
        "set_trunk" to { _ -> true },
        "set_charge_port" to { _ -> true },
        "rotate_screen" to { _ -> true },
        "activate_scenario" to { a -> a["mode"] == "car_wash" || a["mode"] == "rest" },
        "set_window" to { a -> (a["window"] ?: "all") == "all" && a["position"] == "open" },
        "open_door" to { _ -> true },
    )

    private fun range(value: Any?, min: Double, max: Double, what: String): Double {
        val v = numOf(value)
        if (v == null || v.isNaN() || v < min || v > max) {
            throw Refused(l("I didn't change it: ${what.lowercase()} must be between ${n(min)} and ${n(max)}.", "Não mudei: ${what.lowercase()} precisa ficar entre ${n(min)} e ${n(max)}."))
        }
        return v
    }

    private fun lockDoors(door: Any?, lock: Boolean): Outcome {
        val which = positionsOf(door, "door")
        val whole = which.size == 4
        val nn = nm(door, "door")
        if (lock) {
            val open = which.filter { s.doors[it] == "open" }
            if (open.isNotEmpty()) {
                val on = if (open.size == 1) positionName(open[0], "door", lang) else positionName("all", "door", lang)
                // yes = close what is open, then lock what was asked
                val offer = (if (whole && open.size > 1) listOf(call("close_doors", "all")) else open.map { call("close_doors", it) }) + listOf(call("lock_car", door))
                throw Refused(
                    if (open.size == 1) l("I can't lock it while ${on.text} is open. Close it first?", "Não consigo trancar com ${on.text} ${adj("abert", on)}. Quer que eu feche primeiro?")
                    else l("I can't lock the car while doors are open. Close them first?", "Não consigo trancar com portas abertas. Quer que eu feche primeiro?"),
                    offer,
                )
            }
        }
        val stem = if (lock) "trancad" else "destrancad"
        if (which.all { s.doorLocks[it] == lock }) {
            return if (whole) already(l("car already ${if (lock) "locked" else "unlocked"}", "carro já ${stem}o"), l("The car is already ${if (lock) "locked" else "unlocked"}.", "O carro já está ${stem}o."))
            else already(
                l("${nn.text} already ${if (lock) "locked" else "unlocked"}", "${nn.text} já ${adj(stem, nn)}"),
                l("${cap(nn.text)} ${be(nn, "en")} already ${if (lock) "locked" else "unlocked"}.", "${cap(nn.text)} já ${be(nn, "pt-BR")} ${adj(stem, nn)}."),
            )
        }
        for (k in which) s.doorLocks[k] = lock
        s.locked = s.doorLocks.values.all { it }
        Car.changed()
        return if (whole) done(l(if (lock) "car locked" else "car unlocked", if (lock) "carro trancado" else "carro destrancado"), mapOf("doors" to which),
            l(if (lock) "I locked the car." else "I unlocked the car.", if (lock) "Tranquei o carro." else "Destranquei o carro."))
        else done(l("${nn.text} ${if (lock) "locked" else "unlocked"}", "${nn.text} ${adj(stem, nn)}"), mapOf("doors" to which),
            l("I ${if (lock) "locked" else "unlocked"} ${nn.text}.", "${if (lock) "Tranquei" else "Destranquei"} ${nn.text}."))
    }

    /** The place find_along_route found last: "yes" to its offer navigates there (2.1 km away, not the demo's usual 24 minutes). */
    private var lastFound: String? = null

    /** The route's time and distance, said again by get_trip_eta. */
    private var trip: Pair<Int, Double> = 24 to 18.0
    private val enNearest = mapOf("gas_station" to "the nearest gas station", "ev_charger" to "the nearest EV charger", "parking" to "the nearest parking spot",
        "restaurant" to "the nearest restaurant", "coffee" to "the nearest coffee shop", "rest_area" to "the nearest rest area")
    private val ptNearest = mapOf("gas_station" to "o posto de combustível mais próximo", "ev_charger" to "o carregador mais próximo",
        "parking" to "o estacionamento mais próximo", "restaurant" to "o restaurante mais próximo", "coffee" to "o café mais próximo",
        "rest_area" to "a parada mais próxima")

    private val handlers: Map<String, (Obj) -> Outcome> = mapOf(
        "navigate_to" to { a ->
            val d = str(a["destination"])
            val shown = if (d == "home") l("home", "casa") else if (d == "work") l("work", "trabalho") else d
            s.destination = shown
            Car.changed()
            if (lastFound != null && d == lastFound) {
                trip = 3 to 2.1
                done(l("navigating to $shown", "navegando para $shown"), mapOf("minutes" to 3, "km" to 2.1),
                    l("Navigating to $shown: about 3 minutes, 2.1 km.", "Indo para $shown: cerca de 3 minutos, 2,1 km."))
            } else {
                trip = 24 to 18.0
                done(l("navigating to $shown", "navegando para $shown"), mapOf("minutes" to 24, "km" to 18),
                    l("Navigating to $shown: about 24 minutes, 18 km.", "Indo para $shown: cerca de 24 minutos, 18 km."))
            }
        },
        // the found place comes with an offer: a plain "yes" (or "take me there") navigates there (the agent core runs the offered call)
        "find_along_route" to { a ->
            val p = str(a["place_type"])
            val found = l(enNearest[p] ?: "the nearest ${words(p)}", ptNearest[p] ?: "${words(p)} mais próximo")
            lastFound = found
            done(l("found a ${words(p)} 2.1 km ahead", "${ptPlace[p] ?: p} encontrado a 2,1 km"), mapOf(
                "name" to "Nearest match",
                "km" to 2.1,
                "offer" to mapOf("calls" to listOf(mapOf("name" to "navigate_to", "arguments" to mapOf("destination" to found)))),
            ), l("I found ${enPlace[p] ?: words(p)} 2.1 km ahead.", "Encontrei ${ptPlaceSay[p] ?: p} a 2,1 km."))
        },
        "get_trip_eta" to { _ ->
            val dest = s.destination
            if (dest == null) {
                answer(l("no active route", "nenhuma rota ativa"), mapOf("navigating" to false),
                    l("There's no active route. Where do you want to go?", "Não há nenhuma rota ativa. Para onde você quer ir?"))
            } else {
                val (minutes, km) = trip
                val kmText = Json.num(km)
                answer(l("arriving in $minutes min", "chegada em $minutes min"), mapOf("destination" to dest, "minutes_left" to minutes, "km_left" to km),
                    l("You'll get to $dest in about $minutes minutes, $kmText km to go.", "Você chega em $dest em cerca de $minutes minutos; faltam ${kmText.replace('.', ',')} km."))
            }
        },
        "set_route_avoid" to { a ->
            val v = str(a["avoid"])
            if (v in s.avoid) {
                already(l("route unchanged", "rota sem mudança"), l("The route already avoids ${words(v)}.", "A rota já evita ${ptAvoid[v] ?: v}."))
            } else {
                s.avoid = s.avoid + v
                Car.changed()
                done(l("route avoids $v", "rota evitando ${ptAvoid[v] ?: v}"), emptyMap(), l("The route now avoids ${words(v)}.", "A rota agora evita ${ptAvoid[v] ?: v}."))
            }
        },
        "set_temperature" to { a ->
            val u = str(a["unit"] ?: s.unit)
            val z = str(a["zone"] ?: "all")
            val temperature = numOf(a["temperature"])
            val change = numOf(a["change"])
            val current = if (z == "all") s.temperature["driver"]!! else (s.temperature[z] ?: s.temperature["driver"]!!)
            if (temperature == null && change == null) throw Refused(l("no temperature given", "nenhuma temperatura informada"))
            val target = temperature ?: (current + change!!)
            val t = if (u == "fahrenheit") range(target, 60.0, 86.0, l("The temperature", "A temperatura")) else range(target, 16.0, 30.0, l("The temperature", "A temperatura"))
            val zones = if (z == "all") listOf("driver", "passenger", "rear") else listOf(z)
            if (zones.all { s.temperature[it] == t } && u == s.unit) {
                val enZ = mapOf("all" to "The whole cabin is", "driver" to "The driver's side is", "passenger" to "The passenger side is", "rear" to "The back is")[z] ?: "${cap(words(z))} is"
                val ptZ = mapOf("all" to "A cabine toda já está", "driver" to "O lado do motorista já está", "passenger" to "O lado do passageiro já está", "rear" to "A parte de trás já está")[z] ?: "$z já está"
                already(l("temperature already ${degrees(t, u)}", "temperatura já em ${degrees(t, u)}"), l("$enZ already at ${degrees(t, u)}.", "$ptZ em ${degrees(t, u)}."), mapOf("temperature" to t))
            } else {
                s.unit = u
                for (k in zones) s.temperature[k] = t
                Car.changed()
                val zoneText = if (z == "all") l("cabin", "cabine") else l(words(z), ptSeat[z] ?: z)
                val deg = degrees(t, u)
                val enZone = mapOf("all" to "the whole cabin", "driver" to "the driver's side", "passenger" to "the passenger side", "rear" to "the back")[z] ?: words(z)
                val ptZone = mapOf("all" to "a cabine toda", "driver" to "o lado do motorista", "passenger" to "o lado do passageiro", "rear" to "a parte de trás")[z] ?: z
                val up = temperature == null && change!! > 0
                val down = temperature == null && change!! < 0
                val say = l("${if (up) "I raised" else if (down) "I lowered" else "I set"} $enZone to $deg.", "${if (up) "Subi" else if (down) "Baixei" else "Coloquei"} $ptZone ${if (up || down) "para" else "em"} $deg.")
                done(l("$zoneText temperature $deg", "temperatura $zoneText em $deg"), mapOf("temperature" to t), say)
            }
        },
        // Two zones in one request: each value validated; what can't be set is reported, the rest is done.
        "set_dual_zone_temperature" to { a ->
            val u = str(a["unit"] ?: s.unit)
            val (min, max) = if (u == "fahrenheit") 60.0 to 86.0 else 16.0 to 30.0
            fun check(v: Any?): Double? = numOf(v)?.takeIf { it in min..max }
            val d = check(a["driver"])
            val p = check(a["passenger"])
            if (d == null && p == null) {
                throw Refused(l("I didn't change the temperature: both values must be between ${n(min)} and ${n(max)}.", "Não mudei a temperatura: os dois valores precisam ficar entre ${n(min)} e ${n(max)}."))
            }
            if (d == s.temperature["driver"] && p == s.temperature["passenger"] && u == s.unit) {
                already(l("temperatures already set", "temperaturas já ajustadas"),
                    l("The driver's side is already at ${degrees(d!!, u)} and the passenger side at ${degrees(p!!, u)}.", "O lado do motorista já está em ${degrees(d!!, u)} e o do passageiro em ${degrees(p!!, u)}."))
            } else {
                s.unit = u
                if (d != null) s.temperature["driver"] = d
                if (p != null) s.temperature["passenger"] = p
                Car.changed()
                val result = mapOf(
                    "driver" to if (d != null) mapOf("ok" to true, "temperature" to d) else mapOf("ok" to false, "error" to "out of range"),
                    "passenger" to if (p != null) mapOf("ok" to true, "temperature" to p) else mapOf("ok" to false, "error" to "out of range"),
                    "atomic" to false,
                )
                if (d != null && p != null) {
                    done(l("driver ${degrees(d, u)}, passenger ${degrees(p, u)}", "motorista ${degrees(d, u)}, passageiro ${degrees(p, u)}"), result,
                        l("I set the driver's side to ${degrees(d, u)} and the passenger side to ${degrees(p, u)}.", "Coloquei o lado do motorista em ${degrees(d, u)} e o do passageiro em ${degrees(p, u)}."))
                } else {
                    val okSide = if (d != null) l("the driver's side", "o lado do motorista") else l("the passenger side", "o lado do passageiro")
                    val badSide = if (d != null) l("the passenger side", "o do passageiro") else l("the driver's side", "o do motorista")
                    val v = d ?: p!!
                    done(l("partly: $okSide ${degrees(v, u)}", "parcial: $okSide ${degrees(v, u)}"), result,
                        l("I set $okSide to ${degrees(v, u)}, but not $badSide: it must be between ${n(min)} and ${n(max)}.", "Coloquei $okSide em ${degrees(v, u)}, mas não $badSide: precisa ficar entre ${n(min)} e ${n(max)}."))
                }
            }
        },
        "comfort_feeling" to { a ->
            val cold = a["feeling"] == "cold"
            s.temperature["driver"] = (s.temperature["driver"]!! + if (cold) 2 else -2).coerceIn(16.0, 30.0)
            s.seatHeater["driver"] = if (cold) "medium" else "off"
            s.seatVentilation["driver"] = if (cold) "off" else "medium"
            Car.changed()
            done(
                if (cold) l("warmer air, seat heater on medium", "ar mais quente e aquecimento do banco no médio") else l("cooler air, seat ventilation on medium", "ar mais frio e ventilação do banco no médio"),
                emptyMap(),
                if (cold) l("I made it warmer: the air is two degrees up and your seat heater is on medium.", "Deixei mais quente: subi o ar dois graus e liguei o aquecimento do banco no médio.")
                else l("I cooled it down: the air is two degrees lower and your seat ventilation is on medium.", "Deixei mais fresco: baixei o ar dois graus e liguei a ventilação do banco no médio."),
            )
        },
        "set_fan_speed" to { a ->
            val level = numOf(a["level"])
            val change = numOf(a["change"])
            if (level == null && change == null) throw Refused(l("no fan level given", "nenhum nível de ventilação informado"))
            val target = level ?: (s.fan + change!!)
            val fan = range(target.coerceIn(1.0, 7.0), 1.0, 7.0, l("The fan level", "O nível de ventilação")).toInt()
            // Outside 1-7 the car goes to the nearest end and says so (never a silent clamp).
            val edge = if (target > 7) l("The fan goes up to level 7", "A ventilação vai até o nível 7") else if (target < 1) l("The lowest fan level is 1", "O nível mínimo da ventilação é 1") else ""
            if (fan == s.fan) {
                already(l("fan already $fan", "ventilação já no $fan"),
                    if (edge.isNotEmpty()) l("$edge, and it is already there.", "$edge, e já está nele.") else l("The fan is already at level $fan.", "A ventilação já está no nível $fan."))
            } else {
                s.fan = fan
                Car.changed()
                done(l("fan speed $fan", "ventilação no nível $fan"), mapOf("fan_level" to fan),
                    if (edge.isNotEmpty()) l("$edge, so I set it to level $fan.", "$edge, então coloquei no nível $fan.") else l("I set the fan to level $fan.", "Coloquei a ventilação no nível $fan."))
            }
        },
        "set_airflow" to { a ->
            val m = str(a["mode"])
            if (s.airflow == m) {
                already(l("airflow unchanged", "ar sem mudança"), l("The air already goes to ${enAir[m] ?: words(m)}.", "O ar já vai para ${ptAirArt[m] ?: m}."))
            } else {
                s.airflow = m
                Car.changed()
                done(l("air to ${words(m)}", "ar para ${ptAir[m] ?: m}"), emptyMap(), l("The air now goes to ${enAir[m] ?: words(m)}.", "O ar agora vai para ${ptAirArt[m] ?: m}."))
            }
        },
        "set_defrost" to { a ->
            val target = str(a["target"])
            val want = a["on"] != false
            val what = if (target == "rear") l("rear defogger", "desembaçador traseiro") else l("windshield defrost", "desembaçador do para-brisa")
            if (s.defrost[target] == want) {
                already(l("$what already ${if (want) "on" else "off"}", "$what já ${if (want) "ligado" else "desligado"}"),
                    l("The $what is already ${if (want) "on" else "off"}.", "O $what já está ${if (want) "ligado" else "desligado"}."))
            } else {
                s.defrost[target] = want
                Car.changed()
                done("$what ${if (want) l("on", "ligado") else l("off", "desligado")}", emptyMap(),
                    l("I turned the $what ${if (want) "on" else "off"}.", "${if (want) "Liguei" else "Desliguei"} o $what."))
            }
        },
        "set_window" to { a ->
            val which = positionsOf(a["window"], "window")
            val nn = nm(a["window"], "window")
            val pos = str(a["position"])
            val enState = mapOf("open" to "open", "close" to "closed", "half" to "half open", "crack" to "open a crack")[pos] ?: pos
            val ptState = mapOf("open" to adj("abert", nn), "close" to adj("fechad", nn), "half" to "${adj("abert", nn)} até a metade", "crack" to "com uma fresta")[pos] ?: pos
            if (which.all { s.windows[it] == pos }) {
                already(l("${nn.text} already $enState", "${nn.text} já $ptState"), l("${cap(nn.text)} ${be(nn, "en")} already $enState.", "${cap(nn.text)} já ${be(nn, "pt-BR")} $ptState."))
            } else {
                for (k in which) s.windows[k] = pos
                Car.changed()
                val say = l(
                    mapOf("open" to "I opened ${nn.text}.", "close" to "I closed ${nn.text}.", "half" to "I opened ${nn.text} halfway.", "crack" to "I opened ${nn.text} a crack.")[pos] ?: "${cap(nn.text)}: $enState.",
                    mapOf("open" to "Abri ${nn.text}.", "close" to "Fechei ${nn.text}.", "half" to "Abri ${nn.text} até a metade.", "crack" to "Deixei ${nn.text} com uma fresta.")[pos] ?: "${cap(nn.text)}: $ptState.",
                )
                done(l("${nn.text} $enState", "${nn.text} $ptState"), mapOf("windows" to which), say)
            }
        },
        "open_door" to { a ->
            val which = positionsOf(a["door"], "door")
            val nn = nm(a["door"], "door")
            // Locked: the app asks; the model never unlocks on its own.
            val lockedNow = which.filter { s.doorLocks[it] == true }
            if (lockedNow.isNotEmpty()) {
                // yes = unlock what is locked, then open what was asked
                val opening = call("open_door", a["door"])
                if (lockedNow.size == 4) throw Refused(l("The car is locked. Should I unlock it?", "O carro está trancado. Quer que eu destranque?"), listOf(call("unlock_car"), opening))
                val ln = if (lockedNow.size == 1) positionName(lockedNow[0], "door", lang) else nn
                throw Refused(
                    l("${cap(ln.text)} ${be(ln, "en")} locked. Should I unlock ${if (ln.plural) "them" else "it"}?", "${cap(ln.text)} ${be(ln, "pt-BR")} ${adj("trancad", ln)}. Quer que eu destranque?"),
                    lockedNow.map { call("unlock_car", it) } + listOf(opening),
                )
            }
            if (which.all { s.doors[it] == "open" }) {
                already(l("${nn.text} already open", "${nn.text} já ${adj("abert", nn)}"), l("${cap(nn.text)} ${be(nn, "en")} already open.", "${cap(nn.text)} já ${be(nn, "pt-BR")} ${adj("abert", nn)}."))
            } else {
                for (k in which) s.doors[k] = "open"
                Car.changed()
                done(l("${nn.text} open", "${nn.text} ${adj("abert", nn)}"), mapOf("doors" to which), l("I opened ${nn.text}.", "Abri ${nn.text}."))
            }
        },
        "close_doors" to { a ->
            val which = positionsOf(a["door"], "door")
            val nn = nm(a["door"], "door")
            if (which.all { s.doors[it] == "closed" }) {
                already(l("${nn.text} already closed", "${nn.text} já ${adj("fechad", nn)}"), l("${cap(nn.text)} ${be(nn, "en")} already closed.", "${cap(nn.text)} já ${be(nn, "pt-BR")} ${adj("fechad", nn)}."))
            } else {
                for (k in which) s.doors[k] = "closed"
                Car.changed()
                done(l("${nn.text} closed", "${nn.text} ${adj("fechad", nn)}"), mapOf("doors" to which), l("I closed ${nn.text}.", "Fechei ${nn.text}."))
            }
        },
        // each door separately; no door / all = the whole car
        "lock_car" to { a -> lockDoors(a["door"], true) },
        "unlock_car" to { a -> lockDoors(a["door"], false) },
        "set_sunroof" to { a ->
            val action = str(a["action"])
            val enA = if (action == "close") "closed" else if (action == "tilt") "tilted" else "open"
            val ptA = if (action == "close") "fechado" else if (action == "tilt") "inclinado" else "aberto"
            if (s.sunroof == action) {
                already(l("sunroof unchanged", "teto sem mudança"), l("The sunroof is already $enA.", "O teto solar já está $ptA."))
            } else {
                s.sunroof = action
                Car.changed()
                done(l("sunroof $enA", "teto solar $ptA"), emptyMap(),
                    l("I ${if (action == "close") "closed" else if (action == "tilt") "tilted" else "opened"} the sunroof.", "${if (action == "close") "Fechei" else if (action == "tilt") "Inclinei" else "Abri"} o teto solar."))
            }
        },
        "set_sunshade" to { a ->
            s.sunshade = str(a["action"])
            Car.changed()
            done(l("sunshade ${if (a["action"] == "close") "closed" else "open"}", "cortina do teto ${if (a["action"] == "close") "fechada" else "aberta"}"))
        },
        "set_trunk" to { a ->
            val which = str(a["which"])
            val action = str(a["action"])
            if (which == "frunk") s.frunk = action else s.trunk = action
            Car.changed()
            done(l("$which ${if (action == "close") "closed" else "open"}", "${if (which == "frunk") "porta-malas dianteiro" else "porta-malas"} ${if (action == "close") "fechado" else "aberto"}"))
        },
        "set_charge_port" to { a ->
            s.chargePort = str(a["action"])
            Car.changed()
            done(l("charging port ${if (a["action"] == "close") "closed" else "open"}", "tampa de recarga ${if (a["action"] == "close") "fechada" else "aberta"}"))
        },
        "rotate_screen" to { a ->
            val next = if (a["orientation"] != null) str(a["orientation"]) else (if (s.screen == "landscape") "portrait" else "landscape")
            s.screen = next
            Car.changed()
            done(l("screen in $next", "tela na ${if (next == "portrait") "vertical" else "horizontal"}"))
        },
        "set_seat_climate" to { a ->
            val st = str(a["seat"] ?: "driver")
            val feature = str(a["feature"])
            val level = str(a["level"])
            val map = if (feature == "ventilation") s.seatVentilation else s.seatHeater
            val enF = if (feature == "ventilation") "ventilation" else "heater"
            if (map[st] == level) {
                val ptF0 = if (feature == "ventilation") "A ventilação" else "O aquecimento"
                already(l("seat unchanged", "banco sem mudança"), if (level == "off")
                    l("The $enF in ${enSeat[st]} is already off.", "$ptF0 do banco do ${ptSeat[st]} já está ${if (feature == "ventilation") "desligada" else "desligado"}.")
                else l("The $enF in ${enSeat[st]} is already on $level.", "$ptF0 do banco do ${ptSeat[st]} já está no ${ptLevel[level] ?: level}."))
            } else {
                map[st] = level
                Car.changed()
                val ptF = if (feature == "ventilation") "a ventilação" else "o aquecimento"
                done(l("${words(st)} seat $enF $level", "${if (feature == "ventilation") "ventilação" else "aquecimento"} do banco do ${ptSeat[st]} ${ptLevel[level] ?: level}"), emptyMap(),
                    if (level == "off") l("I turned off the $enF in ${enSeat[st]}.", "Desliguei $ptF do banco do ${ptSeat[st]}.")
                    else l("I set the $enF in ${enSeat[st]} to $level.", "Coloquei $ptF do banco do ${ptSeat[st]} no ${ptLevel[level] ?: level}."))
            }
        },
        "set_seat_massage" to { a ->
            s.massageSeat = str(a["seat"] ?: "driver")
            s.massageOn = a["on"] != false
            if (a["mode"] != null) s.massageMode = numOf(a["mode"])?.toInt() ?: s.massageMode
            Car.changed()
            done(if (a["on"] == false) l("seat massage off", "massagem desligada")
            else l("seat massage on${if (a["mode"] != null) ", mode ${str(a["mode"])}" else ""}", "massagem ligada${if (a["mode"] != null) ", modo ${str(a["mode"])}" else ""}"))
        },
        "set_steering_wheel_heater" to { a ->
            s.wheelHeater = a["on"] != false
            Car.changed()
            done(l("steering wheel heater ${if (a["on"] == false) "off" else "on"}", "volante aquecido ${if (a["on"] == false) "desligado" else "ligado"}"))
        },
        "set_cabin_lights" to { a ->
            val on = a["on"] != false
            if (a["light"] == "reading") {
                s.readingLights = on
                Car.changed()
                done(l("reading lights ${if (on) "on" else "off"}", "luz de leitura ${if (on) "acesa" else "apagada"}"))
            } else {
                s.ambientOn = on
                if (a["color"] != null) s.ambientColor = str(a["color"])
                Car.changed()
                val color = if (a["color"] != null && on) ", ${str(a["color"])}" else ""
                done(l("ambient lighting ${if (on) "on" else "off"}$color", "luz ambiente ${if (on) "acesa" else "apagada"}$color"))
            }
        },
        "play_media" to { a ->
            // the driver's own words when the model copied the title wrong ("Perma Woman" for "Pretty Woman", MediaQuery)
            val q = groundMediaQuery(str(a["query"]), lastUserText)
            if (q != str(a["query"])) log("play_media query \"${str(a["query"])}\" -> \"$q\" (the driver's words)")
            s.media = q
            s.radio = null
            s.muted = false
            Car.changed()
            done(l("playing $q", "tocando $q"), emptyMap(), l("Playing $q.", "Tocando $q."))
        },
        "tune_radio" to { a ->
            val band = str(a["band"])
            // FM said as digits ("1067", "985") arrives without the decimal point: 875-1080 can only mean 87.5-108.0 MHz.
            val fm = numOf(a["frequency"])
            val freq: Any? = if (band != "am" && fm != null && fm >= 875 && fm <= 1080) fm / 10 else a["frequency"]
            val f = if (freq != null) (if (band == "am") range(freq, 520.0, 1710.0, "AM") else range(freq, 87.5, 108.0, "FM")) else null
            val station = "${band.uppercase()}${if (f != null) " ${n(f)}" else ""}"
            s.radio = station
            s.media = null
            s.source = "radio"
            Car.changed()
            done(l("radio $station", "rádio $station"), emptyMap(), l("I tuned the radio to $station.", "Sintonizei a rádio $station."))
        },
        "set_audio_source" to { a ->
            val src = str(a["source"])
            s.source = src
            Car.changed()
            val shown = if (src == "usb") "USB" else src
            done(l("audio source $src", "fonte de áudio: $src"), emptyMap(), l("I switched the audio to $shown.", "Mudei o áudio para $shown."))
        },
        "set_audio_focus" to { a ->
            val seat = str(a["seat"])
            s.audioFocus = seat
            Car.changed()
            done(if (seat == "all") l("sound for everyone", "som para todos") else l("sound focused on ${words(seat)}", "som focado no ${ptSeat[seat] ?: seat}"))
        },
        "set_volume" to { a ->
            var action = str(a["action"])
            val level = a["level"]
            var volume = s.volume
            // A step of the user's own size ("by 5", "2 notches"): change = the step (the agent core writes it from the user's words);
            // "up" with a level BELOW the current volume, or "down" with one ABOVE it, cannot be a target: it is the step.
            val amount = numOf(a["change"])
            val target = numOf(level)
            var step: Double? = null
            if (action != "mute" && action != "unmute") {
                if (amount != null && !amount.isNaN() && amount != 0.0) {
                    step = if (amount < 0 || action == "down") -Math.abs(amount) else Math.abs(amount)
                } else if (target != null && !target.isNaN() && action == "up" && target > 0 && target < volume) {
                    step = target
                } else if (target != null && !target.isNaN() && action == "down" && target > volume) {
                    step = -target
                }
            }
            if (step != null) {
                volume = Math.round((s.volume + step).coerceIn(0.0, 30.0)).toInt()
                action = if (step > 0) "up" else "down"
            } else if (action == "set" || (level != null && action != "mute" && action != "unmute")) {
                volume = range(level, 0.0, 30.0, l("Volume", "O volume")).toInt()
            } else if (action == "up") {
                volume = (s.volume + 3).coerceIn(0, 30)
            } else if (action == "down") {
                volume = (s.volume - 3).coerceIn(0, 30)
            }
            if (action == "mute" && s.muted) {
                already(l("already muted", "já no mudo"), l("The sound is already muted.", "O som já está no mudo."))
            } else if (action == "unmute" && !s.muted) {
                already(l("sound already on", "som já ligado"), l("The sound is already on, volume ${s.volume}.", "O som já está ligado, volume ${s.volume}."))
            } else if (step == null && (action == "set" || (action != "up" && action != "down" && level != null)) && volume == s.volume && !s.muted) {
                already(l("volume already $volume", "volume já em $volume"), l("The volume is already at $volume.", "O volume já está em $volume."))
            } else {
                val muted = if (action == "mute") true else if (action == "unmute" || action == "up" || action == "set") false else s.muted
                s.volume = volume
                s.muted = muted
                Car.changed()
                val say = when (action) {
                    "mute" -> l("I muted the sound.", "Deixei o som no mudo.")
                    "unmute" -> l("The sound is back, volume $volume.", "O som voltou, volume $volume.")
                    "up" -> l("I turned the volume up to $volume.", "Aumentei o volume para $volume.")
                    "down" -> l("I turned the volume down to $volume.", "Abaixei o volume para $volume.")
                    else -> l("I set the volume to $volume.", "Coloquei o volume em $volume.")
                }
                done(if (muted) l("muted", "som mudo") else "volume $volume", mapOf("volume" to volume, "muted" to muted), say)
            }
        },
        "call_contact" to { a ->
            s.call = str(a["name"])
            Car.changed()
            done(l("calling ${str(a["name"])}${if (a["number_type"] != null) " (${str(a["number_type"])})" else ""}", "ligando para ${str(a["name"])}"))
        },
        "manage_call" to { a ->
            val action = str(a["action"])
            s.call = if (action == "answer") l("Incoming caller", "Chamada recebida") else null
            Car.changed()
            val en = mapOf("answer" to "call answered", "decline" to "call declined", "hang_up" to "call ended")[action] ?: action
            val p = mapOf("answer" to "chamada atendida", "decline" to "chamada recusada", "hang_up" to "chamada encerrada")[action] ?: action
            done(l(en, p))
        },
        "send_message" to { a -> done(l("message sent to ${str(a["contact"])}: \"${str(a["message"])}\"", "mensagem enviada para ${str(a["contact"])}: \"${str(a["message"])}\"")) },
        "read_messages" to { _ ->
            Outcome(l("read 2 unread messages", "2 mensagens lidas"), mapOf(
                "messages" to listOf(mapOf("from" to "Daniel", "text" to "Are we still on for dinner?"), mapOf("from" to "Mom", "text" to "Call me when you can.")),
            ))
        },
        "get_vehicle_status" to { a ->
            val item = str(a["item"])
            val fuel = s.fuelPercent
            val value: Obj = if (item == "fuel") mapOf("percent" to fuel, "km" to Math.round(fuel * 5.0).toInt()) else mapOf(
                "range" to mapOf("km" to 312),
                "battery" to mapOf("percent" to 76),
                "energy_consumption" to mapOf("km_per_liter" to 12.4),
                "tire_pressure" to mapOf("front_left_psi" to 32, "front_right_psi" to 32, "rear_left_psi" to 31, "rear_right_psi" to 32),
                "warnings" to mapOf("warnings" to emptyList<String>()),
            )[item] ?: emptyMap()
            val shown = mapOf(
                "range" to l("312 km of range", "autonomia de 312 km"),
                "fuel" to l("fuel $fuel%", "combustível em $fuel%"),
                "battery" to l("battery 76%", "bateria em 76%"),
                "energy_consumption" to l("12.4 km/l average", "média de 12,4 km/l"),
                "tire_pressure" to l("tires 31–32 psi", "pneus entre 31 e 32 psi"),
                "warnings" to l("no warnings", "nenhum alerta"),
            )[item] ?: item
            val say = mapOf(
                "range" to l("You have about 312 km of range left.", "Você tem cerca de 312 km de autonomia."),
                "fuel" to l("The tank is at $fuel%, about ${Math.round(fuel * 5.0)} km.", "O tanque está em $fuel%, cerca de ${Math.round(fuel * 5.0)} km."),
                "battery" to l("The battery is at 76%.", "A bateria está em 76%."),
                "energy_consumption" to l("Average consumption is 12.4 km per liter.", "O consumo médio é de 12,4 km por litro."),
                "tire_pressure" to l("The tires are fine: 31 to 32 psi.", "Os pneus estão bem: entre 31 e 32 psi."),
                "warnings" to l("No warning lights are on.", "Nenhuma luz de alerta acesa."),
            )[item]
            val result = mapOf("item" to item) + value
            if (say != null) answer(shown, result, say) else Outcome(shown, result)
        },
        "activate_scenario" to { a ->
            val mode = str(a["mode"])
            s.scenario = if (mode == "smoking" || mode == "rest" || mode == "rain") mode else "car_wash"
            when (mode) {
                "smoking" -> {
                    s.windows[if (s.steering == "right") "front_right" else "front_left"] = "crack"
                    s.airflow = "face"
                }
                "rest" -> {
                    s.sunshade = "close"
                    s.ambientOn = true
                    s.ambientColor = "warm white"
                    s.temperature["driver"] = 22.0
                    s.temperature["passenger"] = 22.0
                }
                else -> {
                    s.windows = mutableMapOf("front_left" to "close", "front_right" to "close", "rear_left" to "close", "rear_right" to "close")
                    s.sunroof = "close"
                }
            }
            Car.changed()
            val ptMode = mapOf("smoking" to "fumante", "rest" to "descanso", "rain" to "chuva", "car_wash" to "lava-rápido")[mode] ?: mode
            done(l("${words(mode)} mode on", "modo $ptMode ativado"))
        },
    )

    /** Whether car results carry their undo (agent core configure "undo"): set by the LLM service for models with undo. */
    @Volatile var undoResults = false

    /** Car tools that only read (nothing to undo, and their failure is not "the last action"). */
    private val readOnlyTools = setOf("get_vehicle_status", "get_trip_eta", "find_along_route")

    /** Logs to logcat (and to the console in unit tests, where android.util.Log is a stub). */
    var log: (String) -> Unit = { msg -> try { Log.i("Car", msg) } catch (_: Throwable) { println("[Car] $msg") } }

    /** Runs one tool call (the handler the agent calls); returns a ToolReply when the app says the sentence itself. [undoing]: the agent
     *  runs a recorded restoring call ("desfaz"): same checks, a sentence that says it was undone, nothing recorded. */
    fun run(name: String, args: Obj, undoing: Boolean = false): Any? {
        val runHandler = handlers[name]
        // Basic language: not tuned, so every state change is asked first and runs only after the user's yes
        val language = AppLanguage.current
        val basic = BasicLanguage.isBasic(language)
        try {
            if (runHandler == null) throw Refused(l("Sorry, this car doesn't have that function.", "Desculpe, este carro não tem essa função."))
            if (s.driving && (notWhileDriving[name]?.invoke(args) == true)) {
                throw Refused(if (basic) BasicLanguage.driving(language) else l("I can't do that while the car is moving.", "Não posso fazer isso com o carro em movimento."))
            }
            if (basic && !undoing && name !in readOnlyTools && !BasicLanguage.takeConfirmed(name, args)) {
                val asked = BasicLanguage.addPending(listOf(mapOf("name" to name, "arguments" to args)))
                val question = BasicLanguage.confirmQuestion(listOf(mapOf("name" to name, "arguments" to args)), language)
                log("ASK $name ${Json.stringify(args)} (basic $language): $question")
                SpeechComposer.noteCarSay(name, question, false)
                // the offer holds every call asked in this request (the core keeps one offer per request): one yes runs them all
                return ToolReply(mapOf("ok" to false, "refused" to "waiting for the user to confirm", "confirm" to true, "offer" to mapOf("calls" to asked)), question)
            }
            // vehicle policy: an undo never unlocks the car while it moves
            if (undoing && name == "unlock_car" && s.driving) {
                throw Refused(l("I can't unlock the doors while the car is moving.", "Não posso destrancar as portas com o carro em movimento."))
            }
            val before = if (undoResults && !undoing) carSnapshot(s) else null
            val out = runHandler(args)
            Car.record(name, args, out.summary)
            log("$name ${Json.stringify(args)} -> ${out.summary}")
            val result = LinkedHashMap<String, Any?>(out.result)
            // an offer of a successful result (find_along_route): the tuned languages only (a Basic language's yes needs its own question)
            val offerCalls = result["offer"].asObj()["calls"] as? List<*>
            if (offerCalls != null && (basic || undoing)) result.remove("offer")
            val offerNames = if (offerCalls != null && !basic && !undoing) offerCalls.map { str(it.asObj()["name"]) } else null
            if (before != null) {
                val undo = carRestoreCalls(before, carSnapshot(s))
                if (undo != null) result["undo"] = undo
            }
            var say = out.say
            if (undoing && say != null) say = l("Undone. $say", "Desfeito. $say")
            // Basic language: the user just confirmed this exact action, so a short "done" in their language; lookups say their answer
            if (basic) {
                say = if (name in readOnlyTools) BasicLanguage.answer(name, args, language, s.destination, s.fuelPercent) ?: say else BasicLanguage.done(language)
            }
            // what kind of sentence it is: only an action that changed the car gets the friendly words (SpeechComposer)
            val kind = if (name in readOnlyTools) "answer" else if (result["already"] == true) "already" else "did"
            if (say != null) {
                SpeechComposer.noteCarSay(name, say, !undoing, args, kind, offerNames)
                return ToolReply(mapOf("ok" to true) + result, say)
            }
            val status = result["status"]
            if (status is String && status.startsWith("Done: ")) {
                val said = if (undoing) l("Undone. ${cap(out.summary)}.", "Desfeito. ${cap(out.summary)}.") else "${cap(out.summary)}."
                SpeechComposer.noteCarSay(name, said, !undoing, args, kind)
                return ToolReply(mapOf("ok" to true) + result, said)
            }
            return mapOf("ok" to true) + result
        } catch (r: Refused) {
            Car.record(name, args, r.message, ok = false, blocked = r.message)
            log("REFUSED $name ${Json.stringify(args)}: ${r.message}")
            // Said by the app, turn ends: the model gets no chance to "fix" it with another action. Nothing changed in the car.
            val text = cap(r.message)
            // undo: a state-changing call that did not go through is still the last action, so "undo" right after it says nothing was
            // done instead of restoring an older action ({"ok": false} + "undo": false)
            val failedUndo = undoResults && !undoing && name !in readOnlyTools
            var offer = r.offer
            var refusal = if (Regex("[.?!]$").containsMatchIn(text)) text else l("I didn't do that: ${r.message}.", "Não fiz isso: ${r.message}.")
            if (basic) {
                // the tool's own sentences are English / Portuguese: a refusal with an offer becomes the question for that offer
                if (!offer.isNullOrEmpty() && !undoing) {
                    offer = BasicLanguage.addPending(offer)
                    refusal = BasicLanguage.confirmQuestion(offer, language)
                } else {
                    refusal = if (r.message == BasicLanguage.driving(language)) r.message else BasicLanguage.cannot(language)
                }
            }
            SpeechComposer.noteCarSay(name, refusal, false, offer = if (!offer.isNullOrEmpty() && !undoing) offer.map { str(it["name"]) } else null)
            val result = linkedMapOf<String, Any?>("ok" to false, "refused" to r.message)
            if (failedUndo) result["undo"] = false
            if (!offer.isNullOrEmpty() && !undoing) result["offer"] = mapOf("calls" to offer)
            return ToolReply(result, refusal)
        }
    }

    // ---- definitions --------------------------------------------------------------------------------------------------------------

    /** Which tool descriptions the model sees: a model must get the wording it was trained with. h3d = the Automotive Agent's. */
    private val wordingFiles = mapOf("v1" to "car/carToolDefinitions.json", "h3d" to "car/carToolDefinitions.h3d.json")
    private val loaded = mutableMapOf<String, Obj>()

    /** Loads the definition files and the Basic-language sentences ([read]: an asset path -> its text). */
    fun load(read: (String) -> String) {
        for ((k, path) in wordingFiles) loaded[k] = Json.obj(read(path))
        BasicLanguage.load(read("car/basic_languages.json"))
    }

    /** The car tools for the agent: the demo's profile (each wording's own) or the whole catalog. */
    fun tools(profile: String = "vw_demo", wording: String = "v1"): List<ToolDefinition> {
        val file = loaded[wording] ?: loaded["v1"]!!
        val keep = if (profile == "all") null else file["profiles"].asObj()["vw_demo"].asList().map { str(it) }.toSet()
        return file["tools"].asList().map { it.asObj() }.filter { keep == null || str(it["name"]) in keep }.map { t ->
            val name = str(t["name"])
            ToolDefinition(
                name = name,
                description = str(t["description"]),
                inputSchema = t["inputSchema"].asObj(),
                execution = t["execution"] as? Obj,
                handler = { args, context -> run(name, args, undoing = context["undo"] == true) },
            )
        }
    }

    /** Every car tool name (any wording): these run on the phone (no "online" filler). */
    val names: Set<String> get() = loaded.values.flatMap { f -> f["tools"].asList().map { str(it.asObj()["name"]) } }.toSet()

    /** Tests: a fresh turn state. */
    fun resetForTests() {
        lastFound = null
        trip = 24 to 18.0
        lastUserText = ""
    }
}
