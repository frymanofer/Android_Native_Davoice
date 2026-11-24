package io.davoice.caragent.tts

import io.davoice.caragent.util.Json
import io.davoice.caragent.util.asObj
import io.davoice.caragent.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// The voice of each language: a TTS model (every voice of the language is a speaker of it) and the voice-clone encoder, downloaded
// from baseUrl on first use (assets/language_packs.json: the same files and checksums as the React Native and Flutter car apps; none
// is bundled here). A file is checked against its sha256 before it is used.

data class PackFile(val file: String, val bytes: Long, val sha256: String)

class LanguagePacks(private val read: (String) -> String, filesDir: File) {
    private val root = File(filesDir, "language_packs")
    private val config = Json.obj(read("language_packs.json"))
    private val baseUrl = str(config["baseUrl"]).trimEnd('/')
    private val packs: Map<String, Map<String, PackFile>> = config["languages"].asObj().mapValues { (_, v) ->
        v.asObj().mapValues { (_, f) -> f.asObj().let { PackFile(str(it["file"]), (it["bytes"] as Number).toLong(), str(it["sha256"]).lowercase()) } }
    }
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val languages get() = packs.keys

    private fun versionDir(language: String): File {
        val p = packs[language]!!
        return File(root, "$language/${p["model"]!!.sha256.take(12)}-${p["embedder"]!!.sha256.take(12)}")
    }

    private fun path(language: String, kind: String) = File(versionDir(language), packs[language]!![kind]!!.file)

    /** Both files of the language on the phone (complete). */
    fun isReady(language: String): Boolean = packs[language]?.let { p -> listOf("model", "embedder").all { k -> path(language, k).length() == p[k]!!.bytes } } ?: false

    /** The TTS model of [language] (the English one for a language without a pack). */
    fun modelPath(language: String): String = path(if (language in packs) language else "en", "model").path

    /** The voice-clone encoder of [language]. */
    fun embedderPath(language: String): String = path(if (language in packs) language else "en", "embedder").path

    /** The download size still missing for [language], in bytes. */
    fun missingBytes(language: String): Long = packs[language]?.let { p -> listOf("model", "embedder").filter { path(language, it).length() != p[it]!!.bytes }.sumOf { p[it]!!.bytes } } ?: 0

    /** Downloads what is missing ([onProgress]: done, total bytes). Checks each file's sha256; a bad file is deleted. */
    suspend fun download(language: String, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean = { false }) = withContext(Dispatchers.IO) {
        val p = packs[language] ?: error("No voice for $language")
        val missing = listOf("model", "embedder").filter { path(language, it).length() != p[it]!!.bytes }
        val total = missing.sumOf { p[it]!!.bytes }
        var done = 0L
        for (kind in missing) {
            val f = p[kind]!!
            val dest = path(language, kind)
            dest.parentFile?.mkdirs()
            val temp = File(dest.path + ".download")
            try {
                http.newCall(Request.Builder().url("$baseUrl/${f.file}").build()).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var last = 0L
                    r.body!!.byteStream().use { input ->
                        temp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                if (cancelled()) error("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 100) {
                                    last = now
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hex != f.sha256) error("The downloaded file is damaged (checksum). Please try again.")
                }
                if (!temp.renameTo(dest)) error("Could not save ${f.file}")
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        onProgress(total, total)
    }
}
