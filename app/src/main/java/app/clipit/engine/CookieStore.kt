package app.clipit.engine

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * Keeps one Netscape-format cookies.txt per logged-in site in private storage
 * and hands the download engine a single merged file.
 */
object CookieStore {
    private const val HEADER = "# Netscape HTTP Cookie File"
    const val IMPORTED = "imported"

    private fun dir(context: Context) = File(context.filesDir, "cookies").apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "$id.txt")

    fun isLoggedIn(context: Context, id: String) = file(context, id).let { it.exists() && it.length() > 0 }

    /** Cookies the web view currently holds for [site], by name. */
    private fun webViewCookies(site: Site): Map<String, String> {
        val manager = CookieManager.getInstance()
        val cookies = LinkedHashMap<String, String>()
        for (url in site.cookieUrls) {
            val raw = manager.getCookie(url) ?: continue
            for (pair in raw.split(';')) {
                val cut = pair.indexOf('=')
                if (cut <= 0) continue
                val name = pair.substring(0, cut).trim()
                if (name.isNotEmpty()) cookies[name] = pair.substring(cut + 1).trim()
            }
        }
        return cookies
    }

    /** True once the web view holds the cookies that mark a signed-in session. */
    fun sessionPresent(site: Site): Boolean {
        val cookies = webViewCookies(site)
        return if (site.required.isEmpty()) cookies.isNotEmpty()
        else site.required.all { !cookies[it].isNullOrEmpty() }
    }

    /**
     * Exports the web view's session for [site]. Returns false, saving nothing,
     * when the cookies that prove a login are missing.
     */
    fun capture(context: Context, site: Site): Boolean {
        if (!sessionPresent(site)) return false
        CookieManager.getInstance().flush()
        val cookies = webViewCookies(site)
        // The web view does not expose expiry dates; a year is how long these
        // sessions typically last, and a dead one is reported at download time.
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val text = buildString {
            append(HEADER).append('\n')
            for (domain in site.domains) for ((name, value) in cookies) {
                append(domain).append("\tTRUE\t/\tTRUE\t").append(expiry).append('\t')
                append(name).append('\t').append(value).append('\n')
            }
        }
        file(context, site.id).writeText(text)
        Sites.setStale(context, site.id, false)
        invalidate(context)
        return true
    }

    /** Adds a cookies.txt exported elsewhere. Returns the number of cookies read. */
    fun import(context: Context, uri: Uri): Int {
        val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
            ?: return 0
        val cookies = lines.filter(::isCookieLine)
        if (cookies.isEmpty()) return 0
        val target = file(context, IMPORTED)
        val existing = if (target.exists()) target.readLines().filter(::isCookieLine) else emptyList()
        target.writeText((listOf(HEADER) + (existing + cookies).distinct()).joinToString("\n", postfix = "\n"))
        invalidate(context)
        return cookies.size
    }

    private fun isCookieLine(line: String): Boolean {
        if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) return false
        return line.split('\t').size >= 7
    }

    fun logout(context: Context, site: Site?, id: String) {
        file(context, id).delete()
        Sites.setStale(context, id, false)
        invalidate(context)
        site ?: return
        val manager = CookieManager.getInstance()
        for (url in site.cookieUrls) {
            val names = manager.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() } ?: continue
            for (name in names) for (domain in site.domains + "") {
                val scope = if (domain.isEmpty()) "" else "; Domain=$domain"
                manager.setCookie(url, "$name=; Max-Age=0; Path=/$scope")
            }
        }
        manager.flush()
    }

    private fun merged(context: Context) = File(context.filesDir, "cookies-merged.txt")

    private fun invalidate(context: Context) {
        merged(context).delete()
    }

    /** One cookies.txt covering every logged-in site, or null if there is none. */
    @Synchronized
    fun mergedFile(context: Context): File? {
        val sources = dir(context).listFiles { f -> f.name.endsWith(".txt") && f.length() > 0 }
            ?.sortedBy { it.name } ?: emptyList()
        val target = merged(context)
        if (sources.isEmpty()) {
            target.delete()
            return null
        }
        if (target.exists() && sources.all { it.lastModified() <= target.lastModified() }) return target
        target.bufferedWriter().use { out ->
            out.write(HEADER)
            out.newLine()
            for (source in sources) source.forEachLine { line ->
                if (isCookieLine(line)) {
                    out.write(line)
                    out.newLine()
                }
            }
        }
        return target
    }
}
