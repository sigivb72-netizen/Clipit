package app.clipit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object Status {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
    const val CANCELED = 4
}

data class SavedFile(val uri: String, val name: String, val mime: String, val size: Long)

data class DownloadItem(
    val id: Long,
    val url: String,
    val status: Int,
    val title: String?,
    val uploader: String?,
    val uploaderUrl: String?,
    val site: String?,
    val engine: String?,
    val postUrl: String?,
    /** JSON object holding every detail the extractor reported. */
    val meta: String?,
    val files: List<SavedFile>,
    val error: String?,
    val errorCode: String?,
    val quality: Int,
    val created: Long,
    val finished: Long,
    val size: Long,
) {
    val active get() = status == Status.QUEUED || status == Status.RUNNING
    val displayTitle get() = title?.takeIf { it.isNotBlank() } ?: files.firstOrNull()?.name ?: url
}

/** Live progress for a running download; kept in memory only. */
data class Progress(
    val downloaded: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val eta: Long = 0,
    val index: Int = 0,
    val count: Int = 0,
    val stage: String = "starting",
) {
    val fraction: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Download history. A plain SQLite table: the list is small, written rarely
 * (progress never touches it) and read as a whole.
 */
object Repo {
    private lateinit var db: SQLiteDatabase
    private val io = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = state

    val progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())

    fun init(context: Context) {
        db = Helper(context.applicationContext).writableDatabase
        // Anything still marked running belonged to a process that has died.
        db.execSQL("UPDATE downloads SET status=${Status.QUEUED} WHERE status=${Status.RUNNING}")
        refresh()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "clipit.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE downloads(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    url TEXT NOT NULL, status INTEGER NOT NULL,
                    title TEXT, uploader TEXT, uploader_url TEXT, site TEXT, engine TEXT,
                    post_url TEXT, meta TEXT, files TEXT, error TEXT, error_code TEXT,
                    quality INTEGER NOT NULL DEFAULT 0,
                    created INTEGER NOT NULL, finished INTEGER NOT NULL DEFAULT 0,
                    size INTEGER NOT NULL DEFAULT 0)"""
            )
            db.execSQL("CREATE INDEX idx_downloads_url ON downloads(url)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun refresh() {
        io.execute { state.value = query(null, null) }
    }

    private fun query(where: String?, args: Array<String>?): List<DownloadItem> =
        db.query("downloads", null, where, args, null, null, "id DESC", "500").use { c ->
            val out = ArrayList<DownloadItem>(c.count)
            while (c.moveToNext()) out += read(c)
            out
        }

    private fun read(c: Cursor): DownloadItem {
        fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name))
        fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        return DownloadItem(
            id = long("id"), url = str("url"), status = long("status").toInt(),
            title = str("title"), uploader = str("uploader"), uploaderUrl = str("uploader_url"),
            site = str("site"), engine = str("engine"), postUrl = str("post_url"),
            meta = str("meta"), files = parseFiles(str("files")),
            error = str("error"), errorCode = str("error_code"),
            quality = long("quality").toInt(), created = long("created"),
            finished = long("finished"), size = long("size"),
        )
    }

    private fun parseFiles(json: String?): List<SavedFile> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) {
                val o = array.getJSONObject(it)
                SavedFile(o.getString("uri"), o.getString("name"), o.optString("mime"), o.optLong("size"))
            }
        }.getOrDefault(emptyList())
    }

    fun filesJson(files: List<SavedFile>): String = JSONArray().apply {
        files.forEach {
            put(JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("size", it.size))
        }
    }.toString()

    fun insert(url: String, quality: Int): Long {
        val id = db.insert("downloads", null, ContentValues().apply {
            put("url", url)
            put("status", Status.QUEUED)
            put("quality", quality)
            put("created", System.currentTimeMillis())
        })
        refresh()
        return id
    }

    fun update(id: Long, block: ContentValues.() -> Unit) {
        db.update("downloads", ContentValues().apply(block), "id=?", arrayOf(id.toString()))
        refresh()
    }

    fun delete(id: Long) {
        db.delete("downloads", "id=?", arrayOf(id.toString()))
        refresh()
    }

    fun clearFinished() {
        db.delete("downloads", "status NOT IN (${Status.QUEUED},${Status.RUNNING})", null)
        refresh()
    }

    fun get(id: Long): DownloadItem? = query("id=?", arrayOf(id.toString())).firstOrNull()

    fun queued(): List<DownloadItem> = query("status=${Status.QUEUED}", null).asReversed()

    /** A download of this link that is finished or still in flight. */
    fun findLive(url: String): DownloadItem? =
        query("url=? AND status IN (${Status.QUEUED},${Status.RUNNING},${Status.DONE})", arrayOf(url)).firstOrNull()

    fun setProgress(id: Long, value: Progress?) {
        progress.update { if (value == null) it - id else it + (id to value) }
    }
}
