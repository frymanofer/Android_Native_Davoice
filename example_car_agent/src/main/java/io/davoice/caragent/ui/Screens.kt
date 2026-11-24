package io.davoice.caragent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.davoice.caragent.CarAgentApp
import io.davoice.caragent.R
import io.davoice.caragent.Stage
import io.davoice.caragent.VoiceConfig
import io.davoice.caragent.flow.appLanguageInfos

// The setup screens (the other apps' redesign: language, voice, speaker, wake word) and the screen switch.

@Composable
fun CarAgentAppUi(app: CarAgentApp) {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        when (app.stage) {
            Stage.LANGUAGE -> LanguageScreen(app)
            Stage.DOWNLOAD -> DownloadScreen(app)
            Stage.PREPARING -> PanelScreen("Getting ready") {
                CircularProgressIndicator(color = C.mint, modifier = Modifier.align(Alignment.CenterHorizontally))
                Centered(app.message)
            }
            Stage.VOICE -> VoiceScreen(app)
            Stage.SPEAKER -> SpeakerScreen(app)
            Stage.ENROLL -> EnrollScreen(app)
            Stage.WAKE -> WakeWordScreen(app)
            Stage.CAR -> CarScreen(app)
        }
    }
}

@Composable
private fun LanguageScreen(app: CarAgentApp) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.hood_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        PanelScreen("Choose your language", "The car agent listens and answers in this language. Its voice downloads the first time.") {
            if (app.message.isNotEmpty()) Text(app.message, style = inter(13, FontWeight.Medium, C.warning2))
            for (l in appLanguageInfos.filter { !it.hidden }) {
                val missing = app.packs.missingBytes(l.id)
                Tile(l.label, if (missing > 0) "Download ${missing / 1_000_000} MB" else "Ready", selected = l.id == app.language) { app.chooseLanguage(l.id) }
            }
        }
    }
}

@Composable
private fun DownloadScreen(app: CarAgentApp) {
    val total = app.downloadTotal.coerceAtLeast(1)
    PanelScreen("Downloading the voice", appLanguageInfos.first { it.id == app.language }.label) {
        ProgressBar(app.downloadDone.toFloat() / total)
        Centered("${app.downloadDone / 1_000_000} / ${app.downloadTotal / 1_000_000} MB")
    }
}

@Composable
private fun VoiceScreen(app: CarAgentApp) {
    PanelScreen("Choose a voice", "Tap a voice to hear it.", footer = { PrimaryButton("Continue") { app.continueFromVoice() } }) {
        for (v in app.voices) Tile(v, selected = v == app.voice) { app.chooseVoice(v) }
    }
}

@Composable
private fun SpeakerScreen(app: CarAgentApp) {
    PanelScreen(
        "Speaker verification",
        "With a voice signature the car agent opens and unlocks doors only for your voice, and the wake word answers only you.",
    ) {
        if (app.message.isNotEmpty()) Text(app.message, style = inter(13, FontWeight.Medium, C.warning2))
        if (app.svHasSaved) Tile("Use my saved signature", "Recorded earlier on this phone") { app.useSavedSpeaker() }
        Tile("Create a new signature", "Read ${VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT} short sentences") { app.createSpeaker() }
        Tile("Skip", "Anyone can use the car agent") { app.skipSpeaker() }
    }
}

@Composable
private fun EnrollScreen(app: CarAgentApp) {
    val i = app.svCollected.coerceAtMost(app.svSentences.size - 1).coerceAtLeast(0)
    PanelScreen("Read this aloud", "Sample ${minOf(app.svCollected + 1, VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT)} of ${VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT}") {
        ProgressBar(app.svCollected.toFloat() / VoiceConfig.SV_ONBOARDING_SAMPLE_COUNT)
        Spacer(Modifier.height(12.dp))
        Text(app.svSentences.getOrElse(i) { "" }, style = inter(22, FontWeight.Bold, C.text, lineHeight = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)))
        Centered("Speak in your normal voice.")
    }
}

@Composable
private fun WakeWordScreen(app: CarAgentApp) {
    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(180.dp).clip(CircleShape).background(C.carbon2), contentAlignment = Alignment.Center) { Emblem(150) }
        Text("Say “${VoiceConfig.WAKE_PHRASE}”", style = inter(30, FontWeight.ExtraBold, C.text), textAlign = TextAlign.Center)
        Text(app.message, style = inter(15, FontWeight.Medium, C.text2), textAlign = TextAlign.Center)
        Text("${app.voice} · ${appLanguageInfos.first { it.id == app.language }.label.substringBefore(" (")}${if (app.driverOnly) " · your voice only" else ""}",
            style = inter(13, FontWeight.Medium, C.tag))
        ModelStatus(app)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) { SecondaryButton("Start without the wake word") { app.enterCar() } }
    }
}

/** The on-device model's download / loading (it continues in the background). */
@Composable
fun ModelStatus(app: CarAgentApp) {
    val s = app.modelStatus
    if (s.isEmpty() || s == "Ready") return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("DaVoice Automotive Agent: $s", style = inter(13, FontWeight.SemiBold, if (s.startsWith("Failed")) C.warning2 else C.mint))
        if (s.startsWith("Downloading")) ProgressBar(app.modelProgress.toFloat())
    }
}
