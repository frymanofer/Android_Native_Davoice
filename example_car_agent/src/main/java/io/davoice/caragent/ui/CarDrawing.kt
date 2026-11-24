package io.davoice.caragent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.davoice.caragent.car.CarAction
import io.davoice.caragent.car.CarState
import io.davoice.caragent.car.resolvePositions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

// The live top-down car (the same drawing as the React Native and Flutter car apps' car drawing), in the design's 260 x 460 viewBox,
// front at the top: the left of the drawing is the car's left side. Doors, windows, sunroof, trunk / frunk, charge port, lock, seat
// heating (amber) / ventilation (blue), wheel heater, defrosters, ambient light, the fan's air, audio focus waves; a refused action's
// part blinks amber.

const val CAR_VB_W = 260f
const val CAR_VB_H = 460f
const val CAR_ASPECT = 254f / 450f

private val windowLevel = mapOf("open" to 1f, "half" to 0.5f, "crack" to 0.2f, "close" to 0f)
private val levelAlpha = mapOf("low" to 0.35f, "medium" to 0.5f, "high" to 0.65f)
private val seatRect = linkedMapOf(
    "front_left" to Triple(74f, 194f, 52f), "front_right" to Triple(142f, 194f, 52f),
    "rear_left" to Triple(74f, 262f, 50f), "rear_right" to Triple(142f, 262f, 50f),
)
private val windowBar = linkedMapOf(
    "front_left" to Triple(55f, 170f, 70f), "front_right" to Triple(200f, 170f, 70f),
    "rear_left" to Triple(55f, 250f, 66f), "rear_right" to Triple(200f, 250f, 66f),
)

// x, y, height, rotation (degrees) around (pivotX, pivotY)
private data class DoorGeom(val x: Float, val y: Float, val h: Float, val deg: Float, val px: Float, val py: Float)
private val doorGeom = linkedMapOf(
    "front_left" to DoorGeom(44f, 168f, 72f, 38f, 51f, 168f), "front_right" to DoorGeom(209f, 168f, 72f, -38f, 209f, 168f),
    "rear_left" to DoorGeom(44f, 248f, 70f, 38f, 51f, 248f), "rear_right" to DoorGeom(209f, 248f, 70f, -38f, 209f, 248f),
)

private val ambient = listOf(
    Regex("blue|azul", RegexOption.IGNORE_CASE) to Color(0xFF60A5FA),
    Regex("red|vermelh", RegexOption.IGNORE_CASE) to Color(0xFFF87171),
    Regex("purple|violet|roxo|lilás|lilas", RegexOption.IGNORE_CASE) to Color(0xFFC084FC),
    Regex("green|verde", RegexOption.IGNORE_CASE) to Color(0xFF34D399),
    Regex("orange|laranja", RegexOption.IGNORE_CASE) to Color(0xFFFB923C),
    Regex("pink|rosa", RegexOption.IGNORE_CASE) to Color(0xFFF472B6),
    Regex("yellow|amarel", RegexOption.IGNORE_CASE) to Color(0xFFFACC15),
    Regex("warm|quente", RegexOption.IGNORE_CASE) to Color(0xFFFDE68A),
    Regex("white|branc", RegexOption.IGNORE_CASE) to Color(0xFFE5E7EB),
)

fun ambientColor(color: String): Color = ambient.firstOrNull { it.first.containsMatchIn(color) }?.second ?: Color(0xFF2DD4BF)

private fun seatPos(seat: String, steering: String) = when (seat) {
    "driver" -> if (steering == "right") "front_right" else "front_left"
    "passenger" -> if (steering == "right") "front_left" else "front_right"
    else -> seat
}

/** The part of the car a refused tool call was about: "trunk" | "frunk" | "sunroof" | "chargePort" | "lock" | doors / windows. */
private fun blockedPart(action: CarAction?, steering: String): Pair<String, List<String>>? {
    if (action == null || action.ok) return null
    val t = action.tool
    val a = action.args
    if ("trunk" in t) return (if (a["target"] == "frunk" || a["which"] == "frunk") "frunk" else "trunk") to emptyList()
    if ("sunroof" in t) return "sunroof" to emptyList()
    if ("charge" in t) return "chargePort" to emptyList()
    if ("lock" in t) return "lock" to emptyList()
    val where = resolvePositions(a["position"] ?: a["target"] ?: a["door"] ?: a["window"], steering) ?: emptyList()
    if ("door" in t) return "doors" to where
    if ("window" in t) return "windows" to where
    return null
}

@Composable
fun CarDrawing(state: CarState, latest: CarAction?, width: Dp, version: Int) {
    val blink = remember { Animatable(0f) }
    LaunchedEffect(latest?.id) {
        if (latest != null && !latest.ok) {
            blink.snapTo(0f)
            blink.animateTo(1f, tween(3000, easing = LinearEasing))
        }
    }
    val waves = rememberInfiniteTransition(label = "waves").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val steering = state.steering
    val blocked = blockedPart(latest, steering)
    val focusSeat = if (state.call != null) seatPos("driver", steering)
    else if (state.audioFocus in listOf("driver", "passenger", "rear_left", "rear_right")) seatPos(state.audioFocus, steering) else null
    val t = blink.value
    // three amber pulses after a refused action: 1 -> 0.35 -> 1, three times
    val blinkOpacity = if (t == 0f && !blink.isRunning) 0f else if (t >= 1f) 1f else 0.35f + 0.65f * (0.5f + 0.5f * cos(t * 3 * 2 * PI.toFloat()))
    val label = "Car seen from above. ${if (latest != null) "Last action: ${latest.summary}" else "No action yet"}."
    Canvas(Modifier.size(width, width * (CAR_VB_H / CAR_VB_W)).semantics { contentDescription = label }) {
        @Suppress("UNUSED_EXPRESSION") version // redraw on every car change
        scale(size.width / CAR_VB_W, pivot = Offset.Zero) { drawCar(state, blocked, if (blocked == null) 0f else blinkOpacity, focusSeat, waves.value) }
    }
}

private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = RoundRect(Rect(x, y, x + w, y + h), CornerRadius(r, r))
private fun Path.addRR(r: RoundRect) = apply { addRoundRect(r) }

private fun DrawScope.fillRR(r: RoundRect, c: Color) = drawPath(Path().addRR(r), c)
private fun DrawScope.strokeRR(r: RoundRect, c: Color, w: Float, effect: PathEffect? = null) = drawPath(Path().addRR(r), c, style = Stroke(w, pathEffect = effect))
private fun DrawScope.strokePath(p: Path, c: Color, w: Float, round: Boolean = false) = drawPath(p, c, style = Stroke(w, cap = if (round) StrokeCap.Round else StrokeCap.Butt))

private fun DrawScope.door(pos: String, fill: Color, stroke: Color) {
    val d = doorGeom[pos]!!
    rotate(d.deg, Offset(d.px, d.py)) {
        val r = rr(d.x, d.y, 7f, d.h, 3f)
        fillRR(r, fill)
        strokeRR(r, stroke, 1.5f)
    }
}

private fun DrawScope.drawCar(s: CarState, blocked: Pair<String, List<String>>?, blockedOpacity: Float, focusSeat: String?, wave: Float) {
    val steering = s.steering
    // wheels and mirrors
    for ((x, y) in listOf(38f to 76f, 210f to 76f, 38f to 346f, 210f to 346f)) fillRR(rr(x, y, 12f, 58f, 4f), Color(0xFF050706))
    drawOval(Color(0xFF2C3331), Offset(44f - 9f, 150f - 5f), Size(18f, 10f))
    drawOval(Color(0xFF2C3331), Offset(216f - 9f, 150f - 5f), Size(18f, 10f))
    // body
    val body = Path().apply {
        moveTo(88f, 14f); quadraticTo(130f, 2f, 172f, 14f); quadraticTo(206f, 24f, 208f, 70f); lineTo(211f, 384f)
        quadraticTo(211f, 444f, 168f, 452f); lineTo(92f, 452f); quadraticTo(49f, 444f, 49f, 384f); lineTo(52f, 70f)
        quadraticTo(54f, 24f, 88f, 14f); close()
    }
    drawPath(body, Brush.horizontalGradient(listOf(Color(0xFF1B2321), Color(0xFF3B4542), Color(0xFF1B2321)), startX = 49f, endX = 211f))
    strokePath(body, Color(0xFF6B7470), 1.5f)
    // headlights / tail lights
    strokePath(Path().apply { moveTo(64f, 36f); quadraticTo(78f, 22f, 102f, 18f) }, C.mint, 4f, round = true)
    strokePath(Path().apply { moveTo(196f, 36f); quadraticTo(182f, 22f, 158f, 18f) }, C.mint, 4f, round = true)
    strokePath(Path().apply { moveTo(62f, 438f); quadraticTo(84f, 448f, 104f, 449f) }, Color(0xCCF87171), 3f, round = true)
    strokePath(Path().apply { moveTo(198f, 438f); quadraticTo(176f, 448f, 156f, 449f) }, Color(0xCCF87171), 3f, round = true)
    // frunk
    val frunk = Path().apply { moveTo(70f, 40f); quadraticTo(130f, 22f, 190f, 40f); lineTo(192f, 116f); lineTo(68f, 116f); close() }
    if (s.frunk == "open") drawPath(frunk, Color(0x3834D399))
    strokePath(frunk, if (s.frunk == "open") C.mint else C.titanium, 1.6f)
    // windshield
    val glass = Brush.verticalGradient(listOf(Color(0xFF2A4A40), Color(0xFF0F1C18)), startY = 120f, endY = 160f)
    val ws = Path().apply { moveTo(70f, 120f); lineTo(190f, 120f); lineTo(180f, 160f); lineTo(80f, 160f); close() }
    drawPath(ws, glass)
    strokePath(ws, C.titanium, 1f)
    if (s.defrost["windshield"] == true) {
        for ((y, cy) in listOf(132f to 126f, 142f to 136f, 152f to 146f)) strokePath(Path().apply { moveTo(84f, y); quadraticTo(130f, cy, 176f, y) }, Color(0xFFFB923C), 1.6f, round = true)
    }
    // cabin (ambient light on its rim)
    val amb = if (s.ambientOn) ambientColor(s.ambientColor) else null
    fillRR(rr(64f, 162f, 132f, 162f, 14f), Color(0xFF0A100E))
    strokeRR(rr(64f, 162f, 132f, 162f, 14f), amb ?: Color(0xFF2C3331), if (amb != null) 3f else 1f)
    if (s.airflow == "face" && s.fan >= 5) {
        for (x in listOf(96f, 164f)) {
            var y = 166f
            while (y < 188f) {
                drawLine(Color(0xFF7DD3FC), Offset(x, y), Offset(x, min(y + 3, 188f)), 1.6f, StrokeCap.Round)
                y += 7f
            }
        }
    }
    val wheelX = if (steering == "right") 164f else 96f
    drawCircle(if (s.wheelHeater) C.warning else Color(0xFF59625F), 12f, Offset(wheelX, 178f), style = Stroke(4f))
    // seats
    for ((pos, r) in seatRect) {
        val seat = listOf("driver", "passenger", "rear_left", "rear_right").first { seatPos(it, steering) == pos }
        val heat = s.seatHeater[seat]
        val vent = s.seatVentilation[seat]
        val color = if (heat != null && heat != "off") C.warning.copy(alpha = levelAlpha[heat] ?: 0.6f)
        else if (vent != null && vent != "off") C.vent.copy(alpha = levelAlpha[vent] ?: 0.5f) else Color(0xFF26322E)
        val box = rr(r.first, r.second, 44f, r.third, 10f)
        fillRR(box, color)
        strokeRR(box, C.olive, 1f)
    }
    // sunroof
    val roof = rr(114f, 198f, 32f, 104f, 8f)
    fillRR(roof, if (s.sunroof == "open") Color(0x1F34D399) else Color(0xBF1E3C32))
    strokeRR(roof, if (s.sunroof == "close") C.titanium else C.mint, 1.5f, if (s.sunroof == "tilt") PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null)
    // windows: a bar filled by how far each one is open
    for ((pos, w) in windowBar) {
        fillRR(rr(w.first, w.second, 5f, w.third, 2.5f), Color(0xFF2A3632))
        val level = windowLevel[s.windows[pos]] ?: 0f
        if (level > 0) fillRR(rr(w.first, w.second, 5f, Math.round(w.third * level).toFloat(), 2.5f), C.mint)
    }
    // open doors
    for (pos in doorGeom.keys) if (s.doors[pos] == "open") door(pos, Color(0x4034D399), C.mint)
    // rear window, rear defroster, trunk, charge port, lock
    val rear = Path().apply { moveTo(80f, 328f); lineTo(180f, 328f); lineTo(188f, 352f); lineTo(72f, 352f); close() }
    drawPath(rear, glass)
    strokePath(rear, C.titanium, 1f)
    if (s.defrost["rear"] == true) {
        drawLine(Color(0xFFFB923C), Offset(84f, 336f), Offset(176f, 336f), 1.4f)
        drawLine(Color(0xFFFB923C), Offset(80f, 344f), Offset(180f, 344f), 1.4f)
    }
    val trunk = Path().apply { moveTo(68f, 356f); lineTo(192f, 356f); lineTo(196f, 420f); quadraticTo(130f, 440f, 64f, 420f); close() }
    if (s.trunk == "open") drawPath(trunk, Color(0x3834D399))
    strokePath(trunk, if (s.trunk == "open") C.mint else C.titanium, 1.8f)
    fillRR(rr(47f, 370f, 7f, 16f, 2f), if (s.chargePort == "open") C.mint else Color(0xFF2C3331))
    if (s.locked || s.doorLocks.values.any { it }) {
        fillRR(rr(120f, 384f, 20f, 16f, 3f), C.mint)
        strokePath(Path().apply { moveTo(124f, 384f); lineTo(124f, 379f); arcTo(Rect(124f, 373f, 136f, 385f), 180f, 180f, false); lineTo(136f, 384f) }, C.mint, 2.5f)
    }
    // a refused action's part, blinking amber
    if (blocked != null && blockedOpacity > 0) {
        val bf = C.warning.copy(alpha = 0.3f * blockedOpacity)
        val bs = C.warning.copy(alpha = blockedOpacity)
        fun both(p: Path) {
            drawPath(p, bf)
            drawPath(p, bs, style = Stroke(2f))
        }
        when (blocked.first) {
            "trunk" -> both(trunk)
            "frunk" -> both(frunk)
            "sunroof" -> both(Path().addRR(roof))
            "chargePort" -> both(Path().addRR(rr(45f, 368f, 11f, 20f, 3f)))
            "lock" -> both(Path().addRR(rr(116f, 372f, 28f, 32f, 5f)))
            "doors" -> for (pos in blocked.second) if (pos in doorGeom) door(pos, bf, bs)
            "windows" -> for (pos in blocked.second) windowBar[pos]?.let { w -> both(Path().addRR(rr(w.first - 1, w.second, 7f, w.third, 3f))) }
        }
    }
    // audio focus / a call: two waves around the seat
    if (focusSeat != null && focusSeat in seatRect) {
        val r = seatRect[focusSeat]!!
        val c = Offset(r.first + 22, r.second + 26)
        for (phase in listOf(wave, (wave + 0.5f) % 1f)) {
            val v = FastOutSlowInEasing.transform(phase)
            drawCircle(C.mint.copy(alpha = 0.9f * (1 - v)), 34f * (0.8f + 0.7f * v), c, style = Stroke(1.5f))
        }
    }
}
