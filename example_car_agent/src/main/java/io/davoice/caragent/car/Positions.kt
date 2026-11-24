package io.davoice.caragent.car

// Windows and doors by place (port of the Flutter / React Native car apps' positions): front_left / front_right / rear_left /
// rear_right, the relative driver / passenger (resolved from the car's steering-wheel side, never assumed), the sides front / rear /
// left / right, and all. Pure functions (the language is a parameter).

val positions = listOf("front_left", "front_right", "rear_left", "rear_right")

/** The physical positions a target means in this car, or null for a value the car doesn't know. */
fun resolvePositions(target: Any?, steering: String): List<String>? {
    val t = if (target == null || target == "") "all" else target.toString()
    val driver = if (steering == "right") "front_right" else "front_left"
    val passenger = if (steering == "right") "front_left" else "front_right"
    return when (t) {
        "all" -> positions.toList()
        "driver" -> listOf(driver)
        "passenger" -> listOf(passenger)
        "front" -> listOf("front_left", "front_right")
        "rear" -> listOf("rear_left", "rear_right")
        "left" -> listOf("front_left", "rear_left")
        "right" -> listOf("front_right", "rear_right")
        else -> if (t in positions) listOf(t) else null
    }
}

/** How the reply names it: the user's own words for the target (driver stays "the driver's window", not a place). */
data class PositionName(val text: String, val plural: Boolean, val fem: Boolean)

private val enOne = mapOf(
    "driver" to "the driver's", "passenger" to "the passenger", "front_left" to "the front left", "front_right" to "the front right",
    "rear_left" to "the rear left", "rear_right" to "the rear right",
)
private val enMany = mapOf("all" to "all the", "front" to "the front", "rear" to "the rear", "left" to "the left", "right" to "the right")
private val ptPlace = mapOf(
    "front_left" to listOf("dianteiro esquerdo", "dianteira esquerda"), "front_right" to listOf("dianteiro direito", "dianteira direita"),
    "rear_left" to listOf("traseiro esquerdo", "traseira esquerda"), "rear_right" to listOf("traseiro direito", "traseira direita"),
)
private val ptSide = mapOf("front" to "da frente", "rear" to "de trás", "left" to "do lado esquerdo", "right" to "do lado direito")

/** [obj] is 'window' or 'door'; [lang] 'en' or 'pt-BR'. */
fun positionName(target: Any?, obj: String, lang: String): PositionName {
    val t = if (target == null || target == "") "all" else target.toString()
    val fem = lang == "pt-BR" && obj == "door"
    if (lang != "pt-BR") {
        enMany[t]?.let { return PositionName("$it ${obj}s", plural = true, fem = fem) }
        return PositionName("${enOne[t] ?: "the"} $obj", plural = false, fem = fem)
    }
    val noun = if (obj == "door") "porta" else "vidro"
    if (t == "all") return PositionName(if (fem) "todas as portas" else "todos os vidros", plural = true, fem = fem)
    ptSide[t]?.let { return PositionName("${if (fem) "as" else "os"} ${noun}s $it", plural = true, fem = fem) }
    if (t == "driver" || t == "passenger") {
        return PositionName("${if (fem) "a" else "o"} $noun do ${if (t == "driver") "motorista" else "passageiro"}", plural = false, fem = fem)
    }
    ptPlace[t]?.let { return PositionName("${if (fem) "a" else "o"} $noun ${it[if (fem) 1 else 0]}", plural = false, fem = fem) }
    return PositionName("${if (fem) "a" else "o"} $noun", plural = false, fem = fem)
}

/** pt-BR adjective agreement: adj("fechad", n) -> fechado / fechada / fechados / fechadas. */
fun adj(stem: String, n: PositionName): String = stem + (if (n.fem) "a" else "o") + (if (n.plural) "s" else "")

/** "is" / "are", "está" / "estão". */
fun be(n: PositionName, lang: String): String = if (lang != "pt-BR") (if (n.plural) "are" else "is") else (if (n.plural) "estão" else "está")
