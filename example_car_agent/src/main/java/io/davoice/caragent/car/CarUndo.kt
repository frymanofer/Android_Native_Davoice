package io.davoice.caragent.car

import io.davoice.caragent.util.Json
import io.davoice.caragent.util.Obj
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.deepCopy

// Undo for the simulated car (agent core configure "undo"; port of the Flutter / React Native car apps' undo): every state-changing
// car call returns, next to its result, how to put the car back exactly as it was before the call: {"undo": {"calls": [...],
// "previous": {...}}}, or {"undo": false} when that can't be done (a new destination, a radio station). The agent keeps it (never
// shows it to the model) and, on "desfaz" / "undo that", runs those calls through these same tools, so every check still applies.

private val pos = listOf("front_left", "front_right", "rear_left", "rear_right")
private val seatList = listOf("driver", "passenger", "rear_left", "rear_right")

/** A deep copy of the parts of the car an action can change (CarState is changed in place). */
fun carSnapshot(s: CarState): Obj = deepCopy(
    mapOf(
        "temperature" to s.temperature + mapOf("unit" to s.unit),
        "fan" to s.fan,
        "airflow" to s.airflow,
        "defrost" to s.defrost,
        "windows" to s.windows,
        "doors" to s.doors,
        "doorLocks" to s.doorLocks,
        "sunroof" to s.sunroof,
        "sunshade" to s.sunshade,
        "seatHeater" to s.seatHeater,
        "seatVentilation" to s.seatVentilation,
        "volume" to s.volume,
        "muted" to s.muted,
        "wheelHeater" to s.wheelHeater,
        // no way back for these (no "cancel the route" / "previous station" tool in the demo)
        "irreversible" to mapOf(
            "destination" to s.destination, "avoid" to s.avoid, "radio" to s.radio, "media" to s.media, "source" to s.source,
            "audioFocus" to s.audioFocus, "call" to s.call, "scenario" to s.scenario, "trunk" to s.trunk, "frunk" to s.frunk,
            "chargePort" to s.chargePort, "screen" to s.screen, "massage" to listOf(s.massageSeat, s.massageOn, s.massageMode),
            "ambient" to listOf(s.ambientOn, s.ambientColor), "readingLights" to s.readingLights,
        ),
    ),
).asObj()

/**
 * What changed between two snapshots, as the calls that restore [before]: {calls, previous}; false = something changed that can't
 * be restored; null = nothing changed.
 */
fun carRestoreCalls(before: Obj, after: Obj): Any? {
    fun changed(k: String) = Json.stringify(before[k]) != Json.stringify(after[k])
    if (changed("irreversible")) return false
    val calls = mutableListOf<Obj>()
    val previous = linkedMapOf<String, Any?>()
    fun m(k: String, s: Obj) = s[k].asObj()
    fun perPosition(k: String, call: (String, Any?) -> Obj) {
        val b = m(k, before)
        val a = m(k, after)
        val changedPos = pos.filter { Json.stringify(b[it]) != Json.stringify(a[it]) }
        if (changedPos.isEmpty()) return
        if (changedPos.size == 4 && pos.all { b[it] == b["front_left"] }) {
            calls += call("all", b["front_left"])
        } else {
            for (p in changedPos) calls += call(p, b[p])
        }
    }

    if (changed("temperature")) {
        val t = m("temperature", before)
        val ta = m("temperature", after)
        previous["temperature"] = t
        if (t["driver"] == t["passenger"] && t["passenger"] == t["rear"]) {
            calls += mapOf("name" to "set_temperature", "arguments" to mapOf("temperature" to t["driver"], "unit" to t["unit"]))
        } else {
            if (t["driver"] != ta["driver"] || t["passenger"] != ta["passenger"] || t["unit"] != ta["unit"]) {
                calls += mapOf("name" to "set_dual_zone_temperature", "arguments" to mapOf("driver" to t["driver"], "passenger" to t["passenger"], "unit" to t["unit"]))
            }
            if (t["rear"] != ta["rear"]) {
                calls += mapOf("name" to "set_temperature", "arguments" to mapOf("temperature" to t["rear"], "zone" to "rear", "unit" to t["unit"]))
            }
        }
    }
    if (changed("fan")) {
        previous["fan"] = before["fan"]
        calls += mapOf("name" to "set_fan_speed", "arguments" to mapOf("level" to before["fan"]))
    }
    if (changed("airflow")) {
        previous["airflow"] = before["airflow"]
        calls += mapOf("name" to "set_airflow", "arguments" to mapOf("mode" to before["airflow"]))
    }
    if (changed("defrost")) {
        previous["defrost"] = before["defrost"]
        for (target in listOf("windshield", "rear")) {
            if (m("defrost", before)[target] != m("defrost", after)[target]) {
                calls += mapOf("name" to "set_defrost", "arguments" to mapOf("target" to target, "on" to m("defrost", before)[target]))
            }
        }
    }
    if (changed("windows")) {
        previous["windows"] = before["windows"]
        perPosition("windows") { w, v -> mapOf("name" to "set_window", "arguments" to mapOf("window" to w, "position" to v)) }
    }
    if (changed("doors")) {
        previous["doors"] = before["doors"]
        perPosition("doors") { d, v -> mapOf("name" to (if (v == "open") "open_door" else "close_doors"), "arguments" to mapOf("door" to d)) }
    }
    if (changed("doorLocks")) {
        previous["doorLocks"] = before["doorLocks"]
        perPosition("doorLocks") { d, v -> mapOf("name" to (if (v == true) "lock_car" else "unlock_car"), "arguments" to mapOf("door" to d)) }
    }
    if (changed("sunroof")) {
        previous["sunroof"] = before["sunroof"]
        calls += mapOf("name" to "set_sunroof", "arguments" to mapOf("action" to before["sunroof"]))
    }
    if (changed("sunshade")) {
        previous["sunshade"] = before["sunshade"]
        calls += mapOf("name" to "set_sunshade", "arguments" to mapOf("action" to before["sunshade"]))
    }
    for (k in listOf("seatHeater", "seatVentilation")) {
        if (!changed(k)) continue
        previous[k] = before[k]
        for (seat in seatList) {
            if (m(k, before)[seat] != m(k, after)[seat]) {
                calls += mapOf(
                    "name" to "set_seat_climate",
                    "arguments" to mapOf("seat" to seat, "feature" to (if (k == "seatHeater") "heater" else "ventilation"), "level" to m(k, before)[seat]),
                )
            }
        }
    }
    if (changed("volume") || changed("muted")) {
        previous["volume"] = before["volume"]
        previous["muted"] = before["muted"]
        if (before["volume"] != after["volume"]) calls += mapOf("name" to "set_volume", "arguments" to mapOf("action" to "set", "level" to before["volume"]))
        if (before["muted"] == true && (after["muted"] != true || before["volume"] != after["volume"])) {
            calls += mapOf("name" to "set_volume", "arguments" to mapOf("action" to "mute"))
        } else if (before["muted"] != true && after["muted"] == true) {
            calls += mapOf("name" to "set_volume", "arguments" to mapOf("action" to "unmute"))
        }
    }
    if (changed("wheelHeater")) {
        previous["wheelHeater"] = before["wheelHeater"]
        calls += mapOf("name" to "set_steering_wheel_heater", "arguments" to mapOf("on" to before["wheelHeater"]))
    }
    if (calls.isEmpty()) return null
    return mapOf("calls" to calls, "previous" to previous)
}
