package io.davoice.caragent.ui

import android.util.Log
import androidx.compose.ui.platform.LocalUriHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import io.davoice.caragent.BuildConfig
import io.davoice.caragent.CarAgentApp
import io.davoice.caragent.R
import io.davoice.caragent.VoiceConfig
import io.davoice.caragent.agent.CarModel
import io.davoice.caragent.car.Car
import io.davoice.caragent.car.CarState
import io.davoice.caragent.conversation.ConversationPhase
import io.davoice.caragent.conversation.conversationHint
import io.davoice.caragent.conversation.conversationTapEnabled

// The car agent's screen (the same layout as the React Native and Flutter car apps' car screen): header with Parked / Driving, a
// status banner, the car seen from above with temperatures / fan / fuel / volume / doors beside it, the last message card (what was
// heard, what ran, the reply, the model's score when held back), the microphone line (tap to talk) or, in debug builds, a text box, and
// the settings sheet behind the gear (conversation mode, model, Local confidence).

private val suggestionsEn = listOf("Open the driver window", "I'm cold", "Open the sunroof", "Take me home", "Call Ana", "Open the trunk", "Set the AC to 19",
    "Ambient light blue", "Open the rear left door", "Lock the car", "Play Anitta", "How much fuel do I have?", "Defrost the windshield", "Volume 20")
private val suggestionsPt = listOf("Abre o vidro do motorista", "Tô com frio", "Abre o teto", "Me leva pra casa", "Liga pra Ana", "Abre o porta-malas",
    "Coloca o ar em 19", "Luz ambiente azul", "Abre a porta traseira esquerda", "Tranca o carro", "Toca Anitta", "Quanto tem de combustível?", "Desembaça o vidro", "Volume 20")

private fun banner(s: CarState): Pair<String, String>? = when {
    s.call != null -> "Calling ${s.call}" to "Phone"
    s.destination != null -> "→ ${s.destination}" to (if (s.avoid.isNotEmpty()) "avoid ${s.avoid.joinToString(", ")}" else "Navigation")
    s.radio != null -> "♪ ${s.radio}" to "Radio"
    s.media != null -> "♪ ${s.media}" to s.source
    s.scenario != null -> "${s.scenario!!.replace('_', ' ')} mode" to ""
    else -> null
}

@Composable
fun CarScreen(app: CarAgentApp) {
    @Suppress("UNUSED_VARIABLE") val version = app.carVersion // redraw on every car change
    var settingsOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    BackHandler { if (settingsOpen) settingsOpen = false else app.leaveCar() }
    val s = Car.state
    val actions = Car.actions.toList()
    val latest = actions.lastOrNull()
    val pt = app.language == "pt-BR"
    val openDoors = s.doors.values.count { it == "open" }
    val doorsLabel = if (openDoors > 0) "$openDoors open" else if (s.locked) "Locked" else if (s.doorLocks.values.any { it }) "Part locked" else "Unlocked"
    val blocked = latest != null && !latest.ok
    val title = when {
        app.working -> "Working…"
        app.ignored -> "Ignored"
        latest != null -> "${if (blocked) "Blocked: " else ""}${latest.summary.replaceFirstChar { it.uppercase() }}"
        else -> "Ready"
    }
    val pool = if (pt) suggestionsPt else suggestionsEn
    val k = (actions.size * 3) % pool.size
    val chips = (0..2).map { pool[(k + it) % pool.size] }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // header
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBox("Back", plain = true, onClick = { app.leaveCar() }) { ChevronLeft() }
                Image(painterResource(R.drawable.davoice_emblem), null, Modifier.size(32.dp).clip(CircleShape))
                Text("Car Agent", style = inter(15, FontWeight.Bold, C.text), modifier = Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF2A3632), RoundedCornerShape(12.dp)).background(C.carbon).padding(3.dp)) {
                    for (driving in listOf(false, true)) {
                        val on = s.driving == driving
                        Box(Modifier.height(38.dp).clip(RoundedCornerShape(9.dp)).background(if (on) C.emerald else Color.Transparent)
                            .clickable(role = Role.RadioButton) { Car.setDriving(driving) }.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                            Text(if (driving) "Driving" else "Parked", style = inter(13, FontWeight.SemiBold, if (on) C.onPrimary else C.tag))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.mint))
            // banner, reset, settings
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp).height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pop = banner(s)
                if (pop != null) {
                    Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.mint, RoundedCornerShape(12.dp)).background(C.mint.copy(alpha = 0.12f)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(C.mint))
                        Text(pop.first, style = inter(13, FontWeight.SemiBold, C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (pop.second.isNotEmpty()) Text(pop.second, style = inter(12, FontWeight.Normal, C.text2), maxLines = 1)
                    }
                } else Spacer(Modifier.weight(1f))
                IconBox("Reset car and chat", onClick = { Car.reset(); app.resetChat() }) { ResetIcon() }
                IconBox("Settings: conversation, model and local confidence", onClick = { settingsOpen = true }) { GearIcon() }
            }
            // the car and the stats beside it
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                val sideW = maxOf(76.dp, maxWidth * 0.2f)
                val carW: Dp = minOf(maxHeight * CAR_ASPECT, maxWidth - sideW * 2)
                Column(Modifier.padding(start = 10.dp, top = 24.dp).width(sideW), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Stat("Driver", "${io.davoice.caragent.util.Json.num(s.temperature["driver"] ?: 21.0)}°", big = true)
                    Stat("Fan", "${s.fan}", unit = " / 7")
                    Stat("Fuel", "${s.fuelPercent}%")
                }
                Column(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 24.dp).width(sideW), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Stat("Passenger", "${io.davoice.caragent.util.Json.num(s.temperature["passenger"] ?: 21.0)}°", big = true, end = true)
                    Stat("Volume", if (s.muted) "Muted" else "${s.volume}", sub = if (s.radio != null) "Radio" else s.source, end = true)
                    Stat("Doors", doorsLabel, color = if (openDoors > 0 || s.locked) C.mint else null, end = true)
                }
                if (carW > 0.dp) Box(Modifier.align(Alignment.Center)) { CarDrawing(s, latest, carW, app.carVersion) }
            }
            // the last message
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp).heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(C.messageCard)
                    .border(1.5.dp, if (blocked) C.warning.copy(alpha = 0.8f) else if (latest != null || app.heard.isNotEmpty()) C.mint else Color(0xFF1F2B27), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (app.heard.isNotEmpty()) Text("“${app.heard}”", style = inter(13, FontWeight.Medium, C.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, style = inter(17, FontWeight.ExtraBold, if (blocked && !app.working) C.warning2 else Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.reply.isNotEmpty() && !app.ignored) Text(app.reply, style = inter(14, FontWeight.Medium, Color(0xFFA7F3D0), lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!app.working && app.lastTurn.low) Text("Held back · ${app.lastTurn.score.ifEmpty { "low score" }}", style = inter(12, FontWeight.Medium, C.warning2))
            }
            ModelStatusLine(app)
            // what the microphone is doing and what to say (every build: "Say “Hey DaVoice” before each request" when asleep)
            val asleep = app.conversationPhase == ConversationPhase.ASLEEP
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = if (BuildConfig.DEBUG) 0.dp else 16.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
                .border(1.5.dp, if (asleep) C.titanium else C.mint, RoundedCornerShape(28.dp)).background(Color(0xFF1A2622))
                .clickable(enabled = conversationTapEnabled(app.conversationPhase), role = Role.Button) { app.tapToTalk() }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MicIcon(asleep)
                Text(conversationHint(app.conversationPhase, VoiceConfig.WAKE_PHRASE, app.conversationSettings.wakeWord), style = inter(15, FontWeight.Medium, C.tag), maxLines = 1)
            }
            // debug builds: chips + a text box under it
            if (BuildConfig.DEBUG) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in chips) {
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, C.mint.copy(alpha = 0.75f), RoundedCornerShape(20.dp)).background(C.carbon)
                            .clickable(enabled = !app.working, role = Role.Button) { app.sendText(c) }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text(c, style = inter(12, FontWeight.SemiBold, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 16.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
                    .border(1.5.dp, C.mint, RoundedCornerShape(28.dp)).background(Color(0xFF1A2622)).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clickable(role = Role.Button) { app.tapToTalk() }.semantics { contentDescription = if (app.conversationPhase == ConversationPhase.ASLEEP) "Tap to talk" else "Microphone" }) {
                        MicIcon(app.conversationPhase == ConversationPhase.ASLEEP)
                    }
                    BasicTextField(text, { text = it }, Modifier.weight(1f).semantics { contentDescription = "What the driver says" }, enabled = !app.working,
                        textStyle = inter(15, FontWeight.Normal, C.text), cursorBrush = SolidColor(C.mint), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { app.sendText(text.trim()); text = "" } }),
                        decorationBox = { inner -> if (text.isEmpty()) Text("What the driver says…", style = inter(15, FontWeight.Normal, C.text2)); inner() })
                    Box(Modifier.size(44.dp).clip(CircleShape).background(primaryGradient).clickable(role = Role.Button) { if (text.isNotBlank()) { app.sendText(text.trim()); text = "" } }
                        .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) { SendIcon() }
                }
            }
        }
        if (settingsOpen) SettingsSheet(app) { settingsOpen = false }
    }
}

@Composable
private fun ModelStatusLine(app: CarAgentApp) {
    if (app.modelStatus.isEmpty() || app.modelStatus == "Ready") return
    Box(Modifier.padding(horizontal = 12.dp).padding(top = 6.dp)) { ModelStatus(app) }
}

/** The app's privacy policy (as the React Native car agent's PrivacyPolicyLink). */
private const val PRIVACY_POLICY_URL = "https://davoice.io/privacy/car-agent"

@Composable
private fun SettingsSheet(app: CarAgentApp, close: () -> Unit) {
    // The sheet edits a draft; nothing changes in the app until Save (Cancel or a tap outside drops the draft).
    var conversation by remember { mutableStateOf(app.conversationSettings) }
    var confidence by remember { mutableStateOf(app.confidence) }
    val dirty = conversation != app.conversationSettings || confidence != app.confidence
    Box(Modifier.fillMaxSize().background(Color(0x73000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close)) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 112.dp, bottom = 24.dp).statusBarsPadding().clip(RoundedCornerShape(16.dp)).background(C.panel)
                .border(1.dp, C.titanium, RoundedCornerShape(16.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CONVERSATION", style = inter(11, FontWeight.Bold, C.mint, letterSpacing = 2.sp))
                ConversationSettingsPanel(conversation, VoiceConfig.WAKE_PHRASE) { conversation = it }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.titanium))
                Text("MODEL", style = inter(11, FontWeight.Bold, C.mint, letterSpacing = 2.sp))
                SettingRow("Model", "${CarModel.DISPLAY_NAME}${if (app.modelStatus.isNotEmpty() && app.modelStatus != "Ready") " · ${app.modelStatus}" else ""}")
                ConfidenceControls(confidence, disabled = app.working) { confidence = it }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.titanium))
                Text("ABOUT", style = inter(11, FontWeight.Bold, C.mint, letterSpacing = 2.sp))
                val uriHandler = LocalUriHandler.current
                SettingRow("Privacy policy", "davoice.io/privacy/car-agent", action = "Open") {
                    // opens in the browser (Google Play asks for the policy inside the app too)
                    runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) }.onFailure { Log.w("CarAgent", "could not open the privacy policy", it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Cancel", onClick = close) }
                Box(Modifier.weight(1f)) {
                    PrimaryButton("Save", enabled = dirty) {
                        if (conversation != app.conversationSettings) app.changeConversation(conversation)
                        if (confidence != app.confidence) app.changeConfidence(confidence)
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String? = null, sub: String? = null, big: Boolean = false, color: Color? = null, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label.uppercase(), style = inter(10, FontWeight.Bold, C.text2, letterSpacing = 1.2.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = inter(if (big) 28 else 18, FontWeight.Bold, color ?: C.text), maxLines = 1)
            if (unit != null) Text(unit, style = inter(12, FontWeight.Normal, C.text2))
        }
        if (sub != null) Text(sub, style = inter(11, FontWeight.Normal, C.text2), maxLines = 1)
    }
}

@Composable
private fun IconBox(label: String, plain: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val base = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
    Box(
        (if (plain) base else base.border(1.dp, Color(0xFF2A3632), RoundedCornerShape(12.dp)).background(C.carbon))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---- line icons (drawn, no icon library) --------------------------------------------------------------------------------------

@Composable
private fun ChevronLeft() = Canvas(Modifier.size(20.dp)) {
    val u = size.width / 24f
    drawLine(C.text, Offset(15 * u, 5 * u), Offset(8 * u, 12 * u), 2.4f * u * 1.2f, StrokeCap.Round)
    drawLine(C.text, Offset(8 * u, 12 * u), Offset(15 * u, 19 * u), 2.4f * u * 1.2f, StrokeCap.Round)
}

@Composable
private fun ResetIcon() = Canvas(Modifier.size(18.dp)) {
    val u = size.width / 24f
    drawArc(C.text, 200f, 290f, false, topLeft = Offset(3 * u, 3 * u), size = androidx.compose.ui.geometry.Size(18 * u, 18 * u), style = Stroke(2 * u, cap = StrokeCap.Round))
    drawLine(C.text, Offset(3 * u, 4 * u), Offset(3 * u, 8 * u), 2 * u, StrokeCap.Round)
    drawLine(C.text, Offset(3 * u, 8 * u), Offset(7 * u, 8 * u), 2 * u, StrokeCap.Round)
}

/** The settings icon: a gear (a ring and eight teeth), as the other apps' GearIcon. */
@Composable
fun GearIcon(color: Color = C.text) = Canvas(Modifier.size(20.dp)) {
    val u = size.width / 24f
    val c = Offset(12 * u, 12 * u)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val start = Offset(c.x + (6.2f * u) * Math.sin(a).toFloat(), c.y - (6.2f * u) * Math.cos(a).toFloat())
        val end = Offset(c.x + (10.6f * u) * Math.sin(a).toFloat(), c.y - (10.6f * u) * Math.cos(a).toFloat())
        drawLine(color, start, end, 4.2f * u, StrokeCap.Round)
    }
    drawCircle(color, 5.4f * u, c, style = Stroke(3.2f * u))
}

@Composable
private fun MicIcon(dim: Boolean) = Canvas(Modifier.size(18.dp)) {
    val u = size.width / 24f
    val col = if (dim) C.text2 else C.mint
    drawRoundRect(col, Offset(9 * u, 3 * u), androidx.compose.ui.geometry.Size(6 * u, 11 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = Stroke(2.2f * u))
    drawArc(col, 0f, 180f, false, topLeft = Offset(5 * u, 4 * u), size = androidx.compose.ui.geometry.Size(14 * u, 14 * u), style = Stroke(2.2f * u, cap = StrokeCap.Round))
    drawLine(col, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), 2.2f * u, StrokeCap.Round)
}

@Composable
private fun SendIcon() = Canvas(Modifier.size(18.dp)) {
    val u = size.width / 24f
    drawLine(C.onPrimary, Offset(5 * u, 12 * u), Offset(19 * u, 12 * u), 2.6f * u, StrokeCap.Round)
    drawLine(C.onPrimary, Offset(13 * u, 6 * u), Offset(19 * u, 12 * u), 2.6f * u, StrokeCap.Round)
    drawLine(C.onPrimary, Offset(13 * u, 18 * u), Offset(19 * u, 12 * u), 2.6f * u, StrokeCap.Round)
}
