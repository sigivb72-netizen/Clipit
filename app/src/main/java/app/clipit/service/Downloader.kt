package app.clipit.service

import android.content.Context
import android.net.ConnectivityManager
import app.clipit.data.DownloadItem
import app.clipit.data.Prefs
import app.clipit.data.Progress
import app.clipit.data.Repo
import app.clipit.data.Status
import app.clipit.engine.CookieStore
import app.clipit.engine.Engine
import app.clipit.engine.Sites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The download queue: runs up to [Prefs.parallel] engine processes at once. */
object Downloader {
    private class Running {
        @Volatile var process: Process? = null
        @Volatile var canceled = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Running>()
    private val activeCount = MutableStateFlow(0)

    /** Number of downloads currently running. */
    val active: StateFlow<Int> = activeCount

    /** True while queued items are held back for Wi-Fi. */
    @Volatile
    var waitingForWifi = false
        private set

    fun enqueue(context: Context, url: String): Long {
        val id = Repo.insert(url, Prefs.quality)
        ClipService.start(context)
        return id
    }

    /** Runs a finished or failed item again with the current quality setting. */
    fun redownload(context: Context, id: Long) {
        if (running.containsKey(id)) return
        Repo.update(id) {
            put("status", Status.QUEUED)
            put("quality", Prefs.quality)
            putNull("error")
            putNull("error_code")
        }
        Notify.cancel(context, id)
        ClipService.start(context)
    }

    fun cancel(context: Context, id: Long) {
        val run = running[id]
        if (run != null) {
            run.canceled = true
            run.process?.destroy()
        } else {
            Repo.update(id) { put("status", Status.CANCELED) }
            Notify.cancel(context, id)
        }
    }

    private fun networkAllows(context: Context): Boolean {
        if (!Prefs.wifiOnly) return true
        return !context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }

    /** Starts queued items while there are free slots. Safe to call any time. */
    @Synchronized
    fun pump(context: Context) {
        val app = context.applicationContext
        val queued = Repo.queued().filter { !running.containsKey(it.id) }
        waitingForWifi = queued.isNotEmpty() && !networkAllows(app)
        if (waitingForWifi) return
        for (item in queued) {
            if (running.size >= Prefs.parallel) break
            val run = Running()
            running[item.id] = run
            activeCount.value = running.size
            scope.launch {
                try {
                    execute(app, item, run)
                } finally {
                    running.remove(item.id)
                    Repo.setProgress(item.id, null)
                    activeCount.value = running.size
                    pump(app)
                }
            }
        }
    }

    private suspend fun execute(context: Context, item: DownloadItem, run: Running) {
        val id = item.id
        Repo.update(id) { put("status", Status.RUNNING) }
        var progress = Progress()
        Repo.setProgress(id, progress)
        Notify.progress(context, item, progress)

        val work = File(context.getExternalFilesDir(null) ?: context.filesDir, "work/$id")
        work.deleteRecursively()
        work.mkdirs()

        var current = item
        var files: List<String> = emptyList()
        var engine: String? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var lastNotified = 0L

        val job = JSONObject()
            .put("mode", "download")
            .put("url", item.url)
            .put("out_dir", work.absolutePath)
            .put("quality", item.quality)
            .put("max_items", Prefs.maxItems)
            .put("engine", "auto")
            .put("cookies", CookieStore.mergedFile(context)?.absolutePath ?: JSONObject.NULL)
            .put("credentials", Sites.credentials(context))

        val result = try {
            Engine.run(context, job, onProcess = { run.process = it; if (run.canceled) it.destroy() }) { event ->
                when (event.optString("t")) {
                    "engine" -> engine = event.optString("name")
                    "meta" -> {
                        fun text(key: String) = event.optString(key).takeIf { it.isNotEmpty() && !event.isNull(key) }
                        Repo.update(id) {
                            put("title", text("title"))
                            put("uploader", text("uploader"))
                            put("uploader_url", text("uploader_url"))
                            put("site", text("site"))
                            put("post_url", text("post_url"))
                            put("meta", event.optJSONObject("extra")?.toString())
                        }
                        current = current.copy(title = text("title"), uploader = text("uploader"))
                    }
                    "progress" -> {
                        progress = Progress(
                            event.optLong("downloaded"), event.optLong("total"), event.optLong("speed"),
                            event.optLong("eta"), event.optInt("index"), event.optInt("count"), "downloading",
                        )
                        Repo.setProgress(id, progress)
                        val now = System.currentTimeMillis()
                        if (now - lastNotified > 900) {
                            lastNotified = now
                            Notify.progress(context, current, progress)
                        }
                    }
                    "stage" -> {
                        progress = progress.copy(stage = event.optString("name"))
                        Repo.setProgress(id, progress)
                        Notify.progress(context, current, progress)
                    }
                    "done" -> {
                        val array = event.optJSONArray("files")
                        files = List(array?.length() ?: 0) { array!!.getString(it) }
                        engine = event.optString("engine", engine ?: "")
                    }
                    "error" -> {
                        errorCode = event.optString("code")
                        errorMessage = event.optString("message")
                    }
                }
            }
        } catch (e: Exception) {
            errorCode = "engine"
            errorMessage = "Could not start the download engine: ${e.message ?: e.javaClass.simpleName}"
            null
        }

        if (run.canceled) {
            work.deleteRecursively()
            Repo.update(id) { put("status", Status.CANCELED); put("finished", System.currentTimeMillis()) }
            Notify.cancel(context, id)
            return
        }

        val existing = files.map(::File).filter { it.isFile && it.length() > 0 }
        if (existing.isEmpty()) {
            val message = errorMessage?.takeIf { it.isNotBlank() }
                ?: result?.log?.lastOrNull()
                ?: "The download engine stopped unexpectedly"
            val site = Sites.forUrl(context, item.url)
            val needsLogin = errorCode == "auth"
            if (needsLogin && site != null) Sites.setStale(context, site.id, true)
            work.deleteRecursively()
            Repo.update(id) {
                put("status", Status.FAILED)
                put("engine", engine)
                put("error", message.take(600))
                put("error_code", errorCode ?: "other")
                put("finished", System.currentTimeMillis())
            }
            Repo.get(id)?.let { Notify.failed(context, it, needsLogin, site?.name) }
            return
        }

        progress = progress.copy(stage = "saving")
        Repo.setProgress(id, progress)
        Notify.progress(context, current, progress)
        try {
            val saved = existing.map { MediaSaver.save(context, it) }
            Repo.update(id) {
                put("status", Status.DONE)
                put("engine", engine)
                put("files", Repo.filesJson(saved))
                put("size", saved.sumOf { it.size })
                put("finished", System.currentTimeMillis())
                putNull("error")
                putNull("error_code")
            }
            Sites.forUrl(context, item.url)?.let { Sites.setStale(context, it.id, false) }
            Repo.get(id)?.let { Notify.done(context, it) }
        } catch (e: Exception) {
            Repo.update(id) {
                put("status", Status.FAILED)
                put("error", "Could not save to Download/${MediaSaver.FOLDER}: ${e.message}")
                put("error_code", "storage")
                put("finished", System.currentTimeMillis())
            }
            Repo.get(id)?.let { Notify.failed(context, it, false, null) }
        } finally {
            work.deleteRecursively()
        }
    }
}
