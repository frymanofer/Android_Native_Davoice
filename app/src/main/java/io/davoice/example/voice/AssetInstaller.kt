package io.davoice.example.voice

import android.content.Context
import java.io.File

/**
 * The DaVoice engines open models by file path. Bundled models live inside the
 * APK, so each one is copied to app storage the first time it's needed, and
 * again after an app update (the new APK may carry a newer model).
 */
class AssetInstaller(private val context: Context) {

    private val root = File(context.filesDir, "davoice_assets")
    private val stamp = File(root, ".installed_version")

    /** Returns a real file path for [assetPath] (e.g. "models/model_ex2_rich.dm"). */
    @Synchronized
    fun path(assetPath: String): String {
        if (!stamp.exists() || stamp.readText() != appVersion()) {
            // First run or a new app version: drop copies made from an older APK.
            root.deleteRecursively()
            root.mkdirs()
            stamp.writeText(appVersion())
        }
        val target = File(root, assetPath)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            context.assets.open(assetPath).use { input ->
                partial.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (!partial.renameTo(target)) error("Could not install $assetPath")
        }
        return target.absolutePath
    }

    private fun appVersion(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.longVersionCode}-${info.lastUpdateTime}"
    }
}
