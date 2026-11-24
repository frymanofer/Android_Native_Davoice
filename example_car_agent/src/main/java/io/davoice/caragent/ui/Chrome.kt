package io.davoice.caragent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.davoice.caragent.R

// The shared look of the setup screens (the other apps' redesign chrome): the emblem header, a panel with a title and a primary
// button at the bottom.

@Composable
fun Emblem(size: Int = 72) {
    Image(painterResource(R.drawable.davoice_emblem), contentDescription = "DaVoice", modifier = Modifier.size(size.dp).clip(CircleShape))
}

@Composable
fun PanelScreen(title: String, subtitle: String? = null, footer: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Emblem(64)
            Spacer(Modifier.height(10.dp))
            Text("DaVoice: AI Car Voice Agent", style = inter(13, FontWeight.Bold, C.mint, letterSpacing = 2.sp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(panelGradient)
                .border(1.dp, C.titanium, RoundedCornerShape(20.dp)).padding(18.dp),
        ) {
            Text(title, style = inter(22, FontWeight.ExtraBold, C.text))
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = inter(14, FontWeight.Medium, C.text2, lineHeight = 20.sp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            if (footer != null) {
                Spacer(Modifier.height(12.dp))
                footer()
            }
        }
    }
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(primaryGradient).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = inter(16, FontWeight.Bold, C.onPrimary)) }
}

@Composable
fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(C.carbon).border(1.dp, C.controlBorder, RoundedCornerShape(14.dp))
            .alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = inter(15, FontWeight.SemiBold, C.text)) }
}

/** A selectable tile (a language, a voice, an option). */
@Composable
fun Tile(title: String, detail: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) C.selected else C.tile)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.mint else C.tileBorder, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = inter(15, FontWeight.SemiBold, C.text))
            if (detail != null) Text(detail, style = inter(12, FontWeight.Medium, C.text2))
        }
    }
}

/** A thin progress bar (0..1). */
@Composable
fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.titanium)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.mint))
    }
}

@Composable
fun Centered(text: String) {
    Text(text, style = inter(15, FontWeight.Medium, C.text2), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
