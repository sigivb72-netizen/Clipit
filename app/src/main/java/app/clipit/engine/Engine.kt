package app.clipit.engine

import android.content.Context
import app.clipit.data.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Runs gallery-dl and yt-dlp. The youtubedl-android library supplies a Python
 * interpreter, yt-dlp, QuickJS and ffmpeg; Clip It starts that interpreter on
 * its own driver script (assets/engine/clipit_engine.py), which imports both
 * tools and reports progress as JSON lines.
 */
object Engine {
    private const val MARK = "@@CLIPIT@@"

    class Paths(
        val python: File, val script: File, val ffmpeg: File, val qjs: File,
        val env: Map<String, String>, val cacheDir: File,
    )

    class Result(val exitCode: Int, val log: List<String>)

    @Volatile
    private var paths: Paths? = null

    /** Unpacks the interpreter and tools on first use (slow once, then instant). */
    @Synchronized
    fun ensureReady(context: Context): Paths {
        paths?.let { return it }
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)

        // Same layout the library uses when it launches yt-dlp itself.
        val base = File(app.noBackupFilesDir, "youtubedl-android")
        val pythonHome = File(base, "packages/python/usr")
        val ffmpegLib = File(base, "packages/ffmpeg/usr/lib")
        val native = File(app.applicationInfo.nativeLibraryDir)
        val ytdlp = File(base, "yt-dlp/yt-dlp")

        val engineDir = File(app.noBackupFilesDir, "engine")
        val script = File(engineDir, "clipit_engine.py")
        val wheelDir = File(engineDir, "pydeps")
        val stamp = File(engineDir, "stamp")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val version = "${info.longVersionCode}/${info.lastUpdateTime}"
        if (!stamp.exists() || stamp.readText() != version || !script.exists()) {
            wheelDir.deleteRecursively()
            wheelDir.mkdirs()
            app.assets.open("engine/clipit_engine.py").use { input ->
                script.outputStream().use { input.copyTo(it) }
            }
            for (name in app.assets.list("pydeps") ?: emptyArray()) {
                app.assets.open("pydeps/$name").use { input ->
                    File(wheelDir, name).outputStream().use { input.copyTo(it) }
                }
            }
            stamp.writeText(version)
        }

        // Wheels are zip files Python can import from directly. An updated
        // gallery-dl downloaded by the app takes the place of the bundled one.
        val updated = Prefs.gdlWheel?.let(::File)?.takeIf { it.exists() }
        val wheels = (wheelDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".whl") && !(updated != null && it.name.startsWith("gallery_dl")) }
        val pythonPath = (listOfNotNull(updated) + wheels + ytdlp).joinToString(":") { it.absolutePath }

        val certs = File(pythonHome, "etc/tls/cert.pem").absolutePath
        val env = mapOf(
            "LD_LIBRARY_PATH" to "${pythonHome.absolutePath}/lib:${ffmpegLib.absolutePath}",
            "SSL_CERT_FILE" to certs,
            "REQUESTS_CA_BUNDLE" to certs,
            "PYTHONHOME" to pythonHome.absolutePath,
            "PYTHONPATH" to pythonPath,
            "PYTHONUNBUFFERED" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "HOME" to engineDir.absolutePath,
            "TMPDIR" to app.cacheDir.absolutePath,
            "PATH" to (System.getenv("PATH") ?: "/system/bin") + ":" + native.absolutePath,
        )
        return Paths(
            python = File(native, "libpython.so"), script = script,
            ffmpeg = File(native, "libffmpeg.so"), qjs = File(native, "libqjs.so"),
            env = env, cacheDir = File(app.cacheDir, "engine").apply { mkdirs() },
        ).also { paths = it }
    }

    /**
     * Runs one job to completion. [onProcess] receives the process so the
     * caller can cancel it; [onEvent] receives each event the script reports.
     */
    fun run(
        context: Context, job: JSONObject, onProcess: (Process) -> Unit = {}, onEvent: (JSONObject) -> Unit,
    ): Result {
        val p = ensureReady(context)
        job.put("ffmpeg", p.ffmpeg.absolutePath)
        job.put("qjs", p.qjs.absolutePath)
        job.put("cache_dir", p.cacheDir.absolutePath)
        val jobFile = File.createTempFile("job", ".json", p.cacheDir)
        try {
            jobFile.writeText(job.toString())
            val process = ProcessBuilder(p.python.absolutePath, p.script.absolutePath, jobFile.absolutePath)
                .redirectErrorStream(true)
                .apply { environment().putAll(p.env) }
                .start()
            onProcess(process)
            val tail = ArrayDeque<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith(MARK)) {
                        runCatching { JSONObject(line.substring(MARK.length)) }.onSuccess(onEvent)
                    } else if (line.isNotBlank()) {
                        if (tail.size >= 25) tail.removeFirst()
                        tail.addLast(line)
                    }
                }
            }
            return Result(process.waitFor(), tail.toList())
        } finally {
            jobFile.delete()
        }
    }

    /** Versions of the bundled tools, e.g. "yt-dlp 2025.11.12 · gallery-dl 1.32.19". */
    fun versions(context: Context): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        run(context, JSONObject().put("mode", "versions")) { event ->
            if (event.optString("t") == "versions") {
                for (key in listOf("yt-dlp", "gallery-dl", "python")) {
                    event.optString(key).takeIf { it.isNotEmpty() }?.let { found[key] = it }
                }
            }
        }
        if (found.isNotEmpty()) {
            Prefs.engineVersions = "yt-dlp ${found["yt-dlp"] ?: "?"} · gallery-dl ${found["gallery-dl"] ?: "?"}"
        }
        return found
    }

    /**
     * Sites change constantly, so both tools can be refreshed without an app
     * update. Returns a line describing what happened to each.
     */
    fun update(context: Context): String {
        val app = context.applicationContext
        ensureReady(app)
        val notes = ArrayList<String>()
        notes += try {
            when (YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated"
                else -> "yt-dlp already current"
            }
        } catch (e: Exception) {
            "yt-dlp update failed: ${e.message ?: e.javaClass.simpleName}"
        }
        notes += try {
            updateGalleryDl(app)
        } catch (e: Exception) {
            "gallery-dl update failed: ${e.message ?: e.javaClass.simpleName}"
        }
        paths = null // rebuild PYTHONPATH on next use
        runCatching { versions(app) }
        return notes.joinToString("\n")
    }

    private fun updateGalleryDl(context: Context): String {
        val current = versions(context)["gallery-dl"]
        val info = JSONObject(fetch("https://pypi.org/pypi/gallery-dl/json").decodeToString())
        val latest = info.getJSONObject("info").getString("version")
        if (latest == current) return "gallery-dl already current"
        val files = info.getJSONArray("urls")
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val name = file.getString("filename")
            if (!name.endsWith("-py3-none-any.whl")) continue
            val bytes = fetch(file.getString("url"))
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == file.getJSONObject("digests").getString("sha256")) { "checksum mismatch" }
            val dir = File(context.noBackupFilesDir, "engine/update").apply {
                deleteRecursively()
                mkdirs()
            }
            val target = File(dir, name)
            target.writeBytes(bytes)
            Prefs.gdlWheel = target.absolutePath
            return "gallery-dl updated to $latest"
        }
        return "gallery-dl $latest has no installable package yet"
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
