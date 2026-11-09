package app.clipit.service

import android.content.ClipboardManager
import android.content.Context
import app.clipit.data.Prefs
import app.clipit.data.Repo
import app.clipit.engine.Sites

/** Turns copied or shared text into queued downloads. */
object Links {
    enum class Outcome { QUEUED, NO_LINK, DUPLICATE, IGNORED }

    private val urlPattern = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    @Volatile private var lastText: String? = null
    @Volatile private var lastAt = 0L

    fun firstUrl(text: CharSequence?): String? =
        text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';') }

    fun clipboardText(context: Context): CharSequence? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)
    }.getOrNull()

    /**
     * [auto] is true when the link was picked up without the user asking for
     * it: such links are limited to known media sites (unless the user opted
     * into everything) and never downloaded twice.
     */
    @Synchronized
    fun handle(context: Context, text: CharSequence?, auto: Boolean): Outcome {
        val url = firstUrl(text) ?: return Outcome.NO_LINK
        val now = System.currentTimeMillis()
        if (auto) {
            // The same copy is often reported several times in a row.
            if (url == lastText && now - lastAt < 15_000) return Outcome.DUPLICATE
            if (!Prefs.allLinks && !Sites.isKnownMedia(context, url)) return Outcome.IGNORED
            if (Repo.findLive(url) != null) {
                lastText = url
                lastAt = now
                return Outcome.DUPLICATE
            }
        } else if (Repo.findLive(url)?.active == true) {
            return Outcome.DUPLICATE
        }
        lastText = url
        lastAt = now
        Downloader.enqueue(context, url)
        return Outcome.QUEUED
    }
}
