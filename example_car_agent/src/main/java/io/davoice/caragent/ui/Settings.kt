package io.davoice.caragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.davoice.caragent.agent.CONFIDENCE_STEP
import io.davoice.caragent.agent.ConfidenceSettings
import io.davoice.caragent.agent.confidenceFallbackDetails
import io.davoice.caragent.agent.confidenceFallbackLabels
import io.davoice.caragent.agent.confidenceFallbacks
import io.davoice.caragent.agent.formatThreshold
import io.davoice.caragent.agent.snapThreshold
import io.davoice.caragent.conversation.Choice
import io.davoice.caragent.conversation.ConversationSettings
import io.davoice.caragent.conversation.endOfSpeechChoices
import io.davoice.caragent.conversation.followUpChoices
import io.davoice.caragent.conversation.idleChoices
import io.davoice.caragent.conversation.listenChoices
import io.davoice.caragent.conversation.preFetchChoices

// The settings sheet's panels: the conversation mode (the same controls and words as the React Native and Flutter apps'
// ConversationSettingsPanel) and Local confidence (ConfidenceControls).

@Composable
private fun Segments(options: List<Pair<String, String>>, selected: String, label: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, text) in options) {
            val on = key == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) C.selected else C.carbon)
                    .border(if (on) 2.dp else 1.dp, if (on) C.mint else C.controlBorder, RoundedCornerShape(8.dp))
                    .alpha(if (enabled) 1f else 0.5f)
                    .semantics { contentDescription = "$label: $text"; this.selected = on }
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(key) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = inter(13, if (on) FontWeight.Bold else FontWeight.Medium, if (on) C.text else C.text2), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = inter(14, FontWeight.Bold, C.text))

@Composable
private fun Caption(text: String) = Text(text, style = inter(12, FontWeight.Medium, C.text2))

@Composable
private fun ChoiceRow(label: String, detail: String, choices: List<Choice>, value: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        Segments(choices.map { "${it.value}" to it.label }, "$value", label) { onSelect(it.toInt()) }
        Caption(detail)
    }
}

/** Take turns or interrupt, wake word or just talk, and each mode's timeouts. Changes apply at once; the app saves them. */
@Composable
fun ConversationSettingsPanel(value: ConversationSettings, wakePhrase: String, onChange: (ConversationSettings) -> Unit) {
    val w = value.wakeWordMode
    val f = value.freeMode
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Turns")
        Segments(listOf("turns" to "Take turns", "interrupt" to "Interrupt"), if (value.interrupt) "interrupt" else "turns", "Turns") { onChange(value.copy(interrupt = it == "interrupt")) }
        Caption(if (value.interrupt) "Talk over the agent to stop it. Uses echo cancellation (AEC), experimental on Android." else "Listening pauses while the agent answers.")
        Spacer(Modifier.height(8.dp))
        Label("Start each request with")
        Segments(listOf("wake" to "Wake word", "free" to "Just talk"), if (value.wakeWord) "wake" else "free", "Start each request with") { onChange(value.copy(wakeWord = it == "wake")) }
        Caption(if (value.wakeWord) "Say “$wakePhrase” before a request. When the agent asks a question, answer without it." else "After the first wake word, just talk: no wake word needed.")
        if (value.wakeWord) {
            ChoiceRow("End of speech", "Silence that ends your request", endOfSpeechChoices, w.endOfSpeechMs) { onChange(value.copy(wakeWordMode = w.copy(endOfSpeechMs = it))) }
            ChoiceRow("Listen after the wake word", "Then back to the wake word", listenChoices, w.listenMs) { onChange(value.copy(wakeWordMode = w.copy(listenMs = it))) }
            ChoiceRow("Follow-up without the wake word", if (w.followUpMs > 0) "After a question from the agent, how long it waits for your answer" else "The wake word before every request, even after a question",
                followUpChoices, w.followUpMs) { onChange(value.copy(wakeWordMode = w.copy(followUpMs = it))) }
            ChoiceRow("Pre-fetch", if (w.preFetchMs > 0) "Keeps words said right after the wake word" else "Wait for the recognizer after the wake word",
                preFetchChoices, w.preFetchMs) { onChange(value.copy(wakeWordMode = w.copy(preFetchMs = it))) }
            if (!value.interrupt) {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Wake word stops the agent")
                        Caption("“$wakePhrase” while it talks, then your request")
                    }
                    Switch(w.stopWord, { onChange(value.copy(wakeWordMode = w.copy(stopWord = it))) }, Modifier.semantics { contentDescription = "Wake word stops the agent" },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.emerald, checkedThumbColor = C.mintPale))
                }
            }
        } else {
            ChoiceRow("End of speech", "Silence that ends your request", endOfSpeechChoices, f.endOfSpeechMs) { onChange(value.copy(freeMode = f.copy(endOfSpeechMs = it))) }
            ChoiceRow("Stop listening after", if (f.idleMs > 0) "Then say “$wakePhrase” or tap the microphone" else "Listening stays on",
                idleChoices, f.idleMs) { onChange(value.copy(freeMode = f.copy(idleMs = it))) }
        }
    }
}

/** Local confidence: ON / OFF, the threshold (0.00 .. 1.00 in 0.05 steps, "0.90 / 90%") and what a command below it becomes. OFF keeps
 *  today's behaviour; the safety checks apply either way. */
@Composable
fun ConfidenceControls(value: ConfidenceSettings, disabled: Boolean, onChange: (ConfidenceSettings) -> Unit) {
    val off = !value.enabled
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Local confidence")
                Caption(if (value.enabled) "On · ${formatThreshold(value.threshold)}" else "Off")
            }
            Switch(value.enabled, { onChange(value.copy(enabled = it)) }, enabled = !disabled, modifier = Modifier.semantics { contentDescription = "Local confidence on or off" },
                colors = SwitchDefaults.colors(checkedTrackColor = C.emerald, checkedThumbColor = C.mintPale))
        }
        Row(Modifier.alpha(if (off) 0.45f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = snapThreshold(value.threshold).toFloat(), onValueChange = { onChange(value.copy(threshold = snapThreshold(it.toDouble()))) },
                valueRange = 0f..1f, steps = (1 / CONFIDENCE_STEP).toInt() - 1, enabled = !disabled, modifier = Modifier.weight(1f).semantics { contentDescription = "Confidence threshold" },
                colors = SliderDefaults.colors(thumbColor = C.mintPale, activeTrackColor = C.mint, inactiveTrackColor = C.titanium),
            )
            Text(formatThreshold(value.threshold), style = inter(13, FontWeight.Bold, C.text), textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
        }
        Column(Modifier.alpha(if (off) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption("Below the threshold:")
            Segments(confidenceFallbacks.map { it to confidenceFallbackLabels[it]!! }, value.fallback, "Below the threshold", enabled = !disabled) { onChange(value.copy(fallback = it)) }
            Caption(confidenceFallbackDetails[value.fallback] ?: "")
        }
    }
}

/** A labelled row with an action button (the model). */
@Composable
fun SettingRow(label: String, value: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = inter(14, FontWeight.SemiBold, C.text))
            Text(value, style = inter(12, FontWeight.Normal, C.text2), maxLines = 2)
        }
        if (action != null) {
            Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.mint.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(action, style = inter(14, FontWeight.SemiBold, C.mint))
            }
        }
    }
}
