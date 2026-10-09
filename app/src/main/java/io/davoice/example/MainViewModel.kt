package io.davoice.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Keeps the demo (and its native engines) alive across configuration changes. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val demo = VoiceDemo(application, viewModelScope)

    override fun onCleared() {
        demo.shutdown()
    }
}
