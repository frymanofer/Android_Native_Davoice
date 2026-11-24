package io.davoice.caragent.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.davoice.caragent.R

// The "emerald" design tokens: the same values as the React Native and Flutter example_car_agent's theme.
object C {
    val bg = Color(0xFF060908)
    val panel = Color(0xFF080B0A)
    val carbon = Color(0xFF26342F)
    val carbon2 = Color(0xFF101513)
    val tile = Color(0xFF2B3D36)
    val tileBorder = Color(0xBF34D399)
    val controlBorder = Color(0xFF8C9995)
    val panelTop = Color(0xFF0D1411)
    val titanium = Color(0xFF4B5552)
    val olive = Color(0xFF3A4D3F)
    val emerald = Color(0xFF10B981)
    val mint = Color(0xFF34D399)
    val mintLight = Color(0xFF6EE7B7)
    val mintPale = Color(0xFFD1FAE5)
    val text = Color(0xFFF9FAFB)
    val text2 = Color(0xFFAAB2BA)
    val tag = Color(0xFFD1D5DB)
    val onPrimary = Color(0xFF04140D)
    val warning = Color(0xFFF59E0B)
    val warning2 = Color(0xFFFBBF24)
    val vent = Color(0xFF60A5FA)

    /** The card that carries the screen's main message. */
    val messageCard = Color(0xFF1E2B26)
    val selected = Color(0xFF1C4636)
}

val primaryGradient = Brush.verticalGradient(listOf(Color(0xFF34D399), Color(0xFF10B981)))
val panelGradient = Brush.verticalGradient(0f to Color(0xFF0D1411), 0.38f to Color(0xFF060908), 1f to Color(0xFF020303))

/** Inter, as in the other apps (res/font/inter_*.ttf). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

fun inter(size: Int, weight: FontWeight, color: Color, letterSpacing: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = letterSpacing, lineHeight = lineHeight)
