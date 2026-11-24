package io.davoice.caragent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.davoice.caragent.ui.CarAgentAppUi
import kotlinx.coroutines.CompletableDeferred

// The one activity: asks for the microphone (and, on Android 13+, notifications for the microphone service) and shows the app.
class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        permissionResult?.complete(granted[Manifest.permission.RECORD_AUDIO] == true || hasMic())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun requestMic(): Boolean {
        if (hasMic()) return true
        val result = CompletableDeferred<Boolean>().also { permissionResult = it }
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
        return result.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // configChanges in the manifest keep this activity (and the voice engines) across rotation and keyboard changes
        val app = CarAgentApp(applicationContext, lifecycleScope) { requestMic() }
        setContent { CarAgentAppUi(app) }
    }
}
