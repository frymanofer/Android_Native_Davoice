package io.davoice.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.davoice.example.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Renders [UiState] and forwards taps to [VoiceDemo]. All logic lives in VoiceDemo. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val demo get() = viewModel.demo
    private var shownWakeWordCount = 0

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) demo.start() else showMicNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        shownWakeWordCount = demo.state.value.wakeWordCount

        buildChoiceButtons()
        wireButtons()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { demo.state.collect(::render) }
        }

        // Only the microphone is requested, like the React Native example. Never
        // run two permission requests at once: Android denies the second one
        // without showing a dialog.
        if (hasMicPermission()) demo.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buildChoiceButtons() {
        Voice.entries.forEach { voice ->
            binding.voiceButtons.addView(choiceButton(voice.displayName) { demo.chooseVoice(voice) })
        }
        AppMode.entries.forEach { mode ->
            binding.modeButtons.addView(choiceButton(mode.title) { demo.chooseMode(mode) })
        }
    }

    private fun choiceButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun wireButtons() = with(binding) {
        skipNarration.setOnClickListener { demo.skipNarration() }
        svUseSaved.setOnClickListener { demo.chooseSpeakerVerification(SvChoice.UseExisting) }
        svRedo.setOnClickListener { demo.chooseSpeakerVerification(SvChoice.RedoOnboarding) }
        svSkip.setOnClickListener { demo.chooseSpeakerVerification(SvChoice.Skip) }
        svContinue.setOnClickListener { demo.continueAfterVerification() }
        speakButton.setOnClickListener { demo.speakTyped(typeInput.text.toString()) }
        backButton.setOnClickListener { demo.backToModes() }
        localModelPick.setOnClickListener { pickLocalModel() }
        shareRecording.setOnClickListener { shareRecording() }
    }

    private fun render(s: UiState) = with(binding) {
        message.text = s.message
        sentence.text = s.sentence
        sentence.visibility = if (s.sentence.isNotEmpty()) View.VISIBLE else View.GONE
        skipNarration.visibility = if (s.narrating) View.VISIBLE else View.GONE

        panelVoice.show(s.screen == Screen.ChooseVoice)
        panelSvPrompt.show(s.screen == Screen.SpeakerVerificationPrompt)
        panelSvStatus.show(s.screen == Screen.SpeakerVerificationStatus)
        panelModes.show(s.screen == Screen.ChooseMode)
        panelTypeToTts.show(s.screen == Screen.TypeToTTS)
        panelSttOnly.show(s.screen == Screen.STTOnly)
        panelChat.show(s.screen == Screen.Chat)
        backButton.show(s.screen in setOf(Screen.Combined, Screen.STTOnly, Screen.TypeToTTS, Screen.Chat))
        shareRecording.show(s.recordings.isNotEmpty())

        // Speaker verification
        svUseSaved.show(s.svHasSaved)
        svProgress.max = s.svTarget
        svProgress.progress = s.svCollected
        svProgress.show(s.svPhase == SvPhase.Onboarding)
        svProgressText.text = when (s.svPhase) {
            SvPhase.Onboarding -> "Recording voice samples: ${s.svCollected} / ${s.svTarget}. Keep talking."
            SvPhase.Verifying -> "Keep talking: the score is live. Tap Continue when done."
            SvPhase.Idle -> ""
        }
        svScore.show(s.svScore != null)
        s.svScore?.let {
            svScore.text = if (it.isFinite()) String.format(Locale.US, "%.3f", it) else "—"
            svScore.setBackgroundColor(getColor(if (s.svMatch) R.color.match else R.color.no_match))
        }
        svContinue.isEnabled = s.svCanContinue

        // Modes
        speakButton.isEnabled = !s.manualTtsSpeaking
        lastSentence.text = s.lastSentSentence

        // Chat
        val local = s.mode == AppMode.LocalAIChat
        localModelRow.show(local)
        localModelStatus.text = "${s.localModelName}: ${s.localModelStatus}"
        localModelPick.isEnabled = !s.chatLoading
        chatStatus.text = s.chatStatus
        chatLoading.show(s.chatLoading)
        chatTranscript.text = transcript(s.chatLines, if (local) "Local AI" else "Gemini")

        if (s.wakeWordCount != shownWakeWordCount) {
            shownWakeWordCount = s.wakeWordCount
            flash()
        }
    }

    private fun transcript(lines: List<ChatLine>, assistant: String): CharSequence {
        val out = SpannableStringBuilder()
        for (line in lines) {
            if (out.isNotEmpty()) out.append("\n\n")
            val label = if (line.fromUser) "You: " else "$assistant: "
            val start = out.length
            out.append(label)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(
                ForegroundColorSpan(getColor(if (line.fromUser) R.color.accent else R.color.match)),
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            out.append(line.text.ifEmpty { "…" })
        }
        return out
    }

    /** Brief background flash on each wake-word detection. */
    private fun flash() {
        binding.message.setBackgroundColor(getColor(R.color.flash))
        binding.message.postDelayed({ binding.message.setBackgroundColor(getColor(R.color.surface)) }, 600)
    }

    private fun pickLocalModel() {
        val models = demo.localModels()
        val selected = models.indexOf(demo.selectedLocalModel())
        val labels = models.map { model ->
            val size = String.format(Locale.US, "%.1f GB", model.sizeBytes / 1e9)
            "${model.displayName} ($size${if (demo.isLocalModelDownloaded(model)) ", downloaded" else ""})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_model)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                dialog.dismiss()
                if (which != selected) demo.selectLocalModel(models[which])
            }
            .show()
    }

    private fun shareRecording() {
        val path = demo.state.value.recordings.firstOrNull() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_recording)))
        } catch (t: Throwable) {
            Toast.makeText(this, "Can't share $path: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMicNeeded() {
        binding.message.text = getString(R.string.mic_needed)
        val dialog = MaterialAlertDialogBuilder(this).setMessage(R.string.mic_needed)
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied once: Android will still show its dialog again.
            dialog.setPositiveButton(R.string.try_again) { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        } else {
            // "Don't ask again" (or denied twice): only Settings can grant it now.
            dialog.setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        dialog.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings with the permission granted.
        if (hasMicPermission()) demo.start()
    }

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
