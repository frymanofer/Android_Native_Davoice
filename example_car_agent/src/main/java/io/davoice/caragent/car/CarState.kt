package io.davoice.caragent.car

// A simulated car for the DaVoice Car demo (port of the Flutter / React Native example_car_agent's car state): the agent's car tools
// change this state, and the car screen shows what the AI understood and which tool it used. Nothing here talks to a real vehicle.

class CarState {
    var destination: String? = null
    var avoid: List<String> = emptyList()

    /** Temperatures per zone (driver / passenger / rear) and the unit ('celsius' | 'fahrenheit'). */
    var temperature: MutableMap<String, Double> = mutableMapOf("driver" to 21.0, "passenger" to 21.0, "rear" to 21.0)
    var unit = "celsius"
    var fan = 3
    var airflow = "face"
    var defrost: MutableMap<String, Boolean> = mutableMapOf("windshield" to false, "rear" to false)

    /** Which side the steering wheel is on (driver / passenger are resolved from it). Brazil: left. */
    var steering = "left"

    /** By place: front_left / front_right / rear_left / rear_right. Windows: open | close | half | crack; doors: open | closed. */
    var windows: MutableMap<String, String> = places("close")
    var doors: MutableMap<String, String> = places("closed")

    /** Every door locked (the car); doorLocks has each door. */
    var locked = false
    var doorLocks: MutableMap<String, Boolean> = mutableMapOf("front_left" to false, "front_right" to false, "rear_left" to false, "rear_right" to false)
    var sunroof = "close"
    var sunshade = "open"
    var trunk = "close"
    var frunk = "close"
    var chargePort = "close"
    var screen = "landscape"
    var seatHeater: MutableMap<String, String> = seats("off")
    var seatVentilation: MutableMap<String, String> = seats("off")
    var massageSeat = "driver"
    var massageOn = false
    var massageMode = 1
    var wheelHeater = false
    var ambientOn = false
    var ambientColor = "white"
    var readingLights = false
    var media: String? = null
    var radio: String? = null
    var source = "streaming"
    var audioFocus = "all"
    var volume = 12
    var muted = false
    var call: String? = null
    var scenario: String? = null

    /** Moving: the app refuses what isn't allowed while driving (the screen's Parked / Driving switch). */
    var driving = false
    var fuelPercent = 62

    private companion object {
        fun places(v: String) = mutableMapOf("front_left" to v, "front_right" to v, "rear_left" to v, "rear_right" to v)
        fun seats(v: String) = mutableMapOf("driver" to v, "passenger" to v, "rear_left" to v, "rear_right" to v)
    }
}

/** One tool call the AI made: what the car screen shows. */
data class CarAction(
    val id: Int,
    val tool: String,
    val args: Map<String, Any?>,
    /** What the car did, in words. */
    val summary: String,
    val ok: Boolean = true,
    /** Set when the app refused it (vehicle state, value range): nothing was changed. */
    val blocked: String? = null,
)

/** The one simulated car. Listeners hear every change (the screen redraws). */
object Car {
    var state = CarState()
        private set
    val actions = mutableListOf<CarAction>()
    private var nextId = 1
    private val listeners = mutableSetOf<() -> Unit>()

    fun listen(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    fun changed() = listeners.toList().forEach { it() }

    fun record(tool: String, args: Map<String, Any?>, summary: String, ok: Boolean = true, blocked: String? = null) {
        actions += CarAction(nextId++, tool, args, summary, ok, blocked)
        if (actions.size > 20) actions.removeAt(0)
        changed()
    }

    fun setDriving(driving: Boolean) {
        state.driving = driving
        changed()
    }

    /** The steering-wheel side (vehicle configuration); driver / passenger follow it. */
    fun setSteering(steering: String) {
        state.steering = steering
        changed()
    }

    fun reset() {
        val keep = state
        state = CarState().also {
            it.driving = keep.driving
            it.steering = keep.steering
        }
        actions.clear()
        changed()
    }

    /** Tests: a car in this state. */
    fun replaceState(s: CarState) {
        state = s
        actions.clear()
    }
}
