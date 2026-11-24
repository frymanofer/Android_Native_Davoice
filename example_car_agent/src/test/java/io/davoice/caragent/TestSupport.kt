package io.davoice.caragent

import io.davoice.caragent.agent.AgentPack
import io.davoice.caragent.car.CarState
import io.davoice.caragent.car.CarTools
import io.davoice.caragent.util.Json
import io.davoice.caragent.util.asObj
import java.io.File

// Test helpers: the app's assets and the shared fixtures (src/test/resources/fixtures, kept identical to the React Native car app's
// __tests__/fixtures and the Flutter car app's test/fixtures).

object TestSupport {
    private var loaded = false

    fun asset(path: String) = File("src/main/assets/$path").readText()

    fun load() {
        if (loaded) return
        CarTools.load { asset(it) }
        AgentPack.load { asset(it) }
        loaded = true
    }

    fun fixtureText(name: String) = File("src/test/resources/fixtures/$name").readText()
    fun fixture(name: String): Any? = Json.parse(fixtureText(name))

    /** The React Native car app's copy, when the repositories sit side by side (null otherwise). */
    fun rnFixture(name: String): String? = File("../../ReactNative_DaVoice/example_car_agent/__tests__/fixtures/$name").takeIf { it.exists() }?.readText()

    /** The demo's starting car with the case's changes (only what differs from the start is in the fixture). */
    @Suppress("UNCHECKED_CAST")
    fun carWith(changes: Map<String, Any?>): CarState {
        val s = CarState()
        for ((k, v) in changes) {
            when (k) {
                "windows" -> s.windows = (v as Map<String, String>).toMutableMap()
                "doors" -> s.doors = (v as Map<String, String>).toMutableMap()
                "doorLocks" -> s.doorLocks = (v as Map<String, Boolean>).toMutableMap()
                "locked" -> s.locked = v as Boolean
                "sunroof" -> s.sunroof = v as String
                "seatHeater" -> s.seatHeater = (v as Map<String, String>).toMutableMap()
                "seatVentilation" -> s.seatVentilation = (v as Map<String, String>).toMutableMap()
                "temperature" -> {
                    val t = v.asObj()
                    for (z in listOf("driver", "passenger", "rear")) (t[z] as? Number)?.let { s.temperature[z] = it.toDouble() }
                    (t["unit"] as? String)?.let { s.unit = it }
                }
                "volume" -> s.volume = (v as Number).toInt()
                "muted" -> s.muted = v as Boolean
                "fan" -> s.fan = (v as Number).toInt()
                "media" -> s.media = v as String?
                "radio" -> s.radio = v as String?
                "destination" -> s.destination = v as String?
                else -> error("fixture state \"$k\" is not mapped")
            }
        }
        return s
    }
}
