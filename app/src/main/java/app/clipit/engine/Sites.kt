package app.clipit.engine

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * A site Clip It can log in to. Logging in happens in a web view; the session
 * cookies it ends up with are exported as a cookies.txt for gallery-dl/yt-dlp.
 */
data class Site(
    val id: String,
    val name: String,
    val loginUrl: String,
    /** URLs whose cookies make up the session. */
    val cookieUrls: List<String>,
    /** Domains the cookies are written for in cookies.txt. */
    val domains: List<String>,
    /** Cookies that only exist once signed in; all must be present. */
    val required: List<String>,
    val hosts: List<String>,
    val note: String? = null,
    val custom: Boolean = false,
)

object Sites {
    val builtIn = listOf(
        Site(
            "twitter", "X / Twitter", "https://x.com/i/flow/login",
            listOf("https://x.com", "https://twitter.com"), listOf(".x.com", ".twitter.com"),
            listOf("auth_token", "ct0"), listOf("x.com", "twitter.com", "t.co"),
            "Needed for most posts: X hides videos from signed-out visitors.",
        ),
        Site(
            "instagram", "Instagram", "https://www.instagram.com/accounts/login/",
            listOf("https://www.instagram.com"), listOf(".instagram.com"),
            listOf("sessionid"), listOf("instagram.com", "instagr.am"),
            "Needed for reels, posts and stories.",
        ),
        Site(
            "tiktok", "TikTok", "https://www.tiktok.com/login",
            listOf("https://www.tiktok.com"), listOf(".tiktok.com"),
            listOf("sessionid"), listOf("tiktok.com"),
            "Usually optional; helps with private or restricted videos.",
        ),
        Site(
            "reddit", "Reddit", "https://www.reddit.com/login/",
            listOf("https://www.reddit.com"), listOf(".reddit.com"),
            listOf("reddit_session"), listOf("reddit.com", "redd.it"),
            "Needed for NSFW and private communities.",
        ),
        Site(
            "facebook", "Facebook", "https://m.facebook.com/login/",
            listOf("https://m.facebook.com", "https://www.facebook.com"), listOf(".facebook.com"),
            listOf("c_user", "xs"), listOf("facebook.com", "fb.watch", "fb.com"),
            "Needed for anything that is not fully public.",
        ),
        Site(
            "youtube", "YouTube",
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F",
            listOf("https://www.youtube.com", "https://m.youtube.com"), listOf(".youtube.com"),
            listOf("LOGIN_INFO"), listOf("youtube.com", "youtu.be"),
            "Optional: only for age-restricted, private or members-only videos. Google sometimes refuses sign-in inside apps.",
        ),
        Site(
            "pinterest", "Pinterest", "https://www.pinterest.com/login/",
            listOf("https://www.pinterest.com"), listOf(".pinterest.com"),
            listOf("_pinterest_sess"), listOf("pinterest.com", "pin.it"),
            "Optional; needed for secret boards.",
        ),
        Site(
            "twitch", "Twitch", "https://www.twitch.tv/login",
            listOf("https://www.twitch.tv"), listOf(".twitch.tv"),
            listOf("auth-token"), listOf("twitch.tv"),
            "Optional; needed for subscriber-only videos.",
        ),
        Site(
            "bilibili", "Bilibili", "https://passport.bilibili.com/login",
            listOf("https://www.bilibili.com"), listOf(".bilibili.com"),
            listOf("SESSDATA"), listOf("bilibili.com", "b23.tv"),
            "Needed for resolutions above 480p.",
        ),
        Site(
            "deviantart", "DeviantArt", "https://www.deviantart.com/users/login",
            listOf("https://www.deviantart.com"), listOf(".deviantart.com"),
            listOf("auth"), listOf("deviantart.com"),
            "Needed for mature content.",
        ),
        Site(
            "weibo", "Weibo", "https://passport.weibo.com/sso/signin",
            listOf("https://weibo.com", "https://www.weibo.com"), listOf(".weibo.com"),
            listOf("SUB"), listOf("weibo.com", "weibo.cn"),
        ),
    )

    /** Other media sites worth grabbing automatically; none need a login by default. */
    private val otherKnownHosts = listOf(
        "vimeo.com", "dailymotion.com", "dai.ly", "streamable.com", "tumblr.com", "bsky.app",
        "imgur.com", "soundcloud.com", "threads.net", "threads.com", "artstation.com", "pixiv.net",
        "flickr.com", "9gag.com", "newgrounds.com", "kick.com", "rumble.com", "odysee.com",
        "bitchute.com", "ok.ru", "vk.com", "vk.ru", "snapchat.com", "linkedin.com", "redgifs.com",
        "gfycat.com", "twitch.tv", "bandcamp.com", "mixcloud.com", "ted.com", "archive.org",
        "catbox.moe", "danbooru.donmai.us", "gelbooru.com", "kemono.cr", "coomer.st", "nicovideo.jp",
        "douyin.com", "kuaishou.com", "likee.video", "triller.co", "loom.com", "medal.tv",
        "mastodon.social", "lemon8-app.com", "xiaohongshu.com", "xhslink.com", "tenor.com", "giphy.com",
    )

    private fun hostOf(url: String): String =
        (runCatching { Uri.parse(url).host }.getOrNull() ?: "").lowercase().removePrefix("www.")

    private fun matches(host: String, pattern: String) = host == pattern || host.endsWith(".$pattern")

    fun all(context: Context): List<Site> = builtIn + custom(context)

    fun byId(context: Context, id: String): Site? = all(context).firstOrNull { it.id == id }

    fun forUrl(context: Context, url: String): Site? {
        val host = hostOf(url)
        if (host.isEmpty()) return null
        return all(context).firstOrNull { site -> site.hosts.any { matches(host, it) } }
    }

    /** Whether a copied link looks like media Clip It should fetch unprompted. */
    fun isKnownMedia(context: Context, url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        return otherKnownHosts.any { matches(host, it) } || forUrl(context, url) != null
    }

    // ── sites the user added by address ──

    private fun prefs(context: Context) = context.getSharedPreferences("sites", Context.MODE_PRIVATE)

    fun custom(context: Context): List<Site> {
        val array = runCatching { JSONArray(prefs(context).getString("custom", "[]")) }.getOrDefault(JSONArray())
        return List(array.length()) { customSite(array.getString(it)) }
    }

    fun customSite(loginUrl: String): Site {
        val host = hostOf(loginUrl)
        return Site(
            id = "custom-" + host.replace(Regex("[^a-z0-9]+"), "-"), name = host, loginUrl = loginUrl,
            cookieUrls = listOf("https://$host", "https://www.$host"), domains = listOf(".$host"),
            required = emptyList(), hosts = listOf(host), custom = true,
        )
    }

    fun addCustom(context: Context, loginUrl: String) {
        val site = customSite(loginUrl)
        val kept = custom(context).filter { it.id != site.id }.map { it.loginUrl }
        prefs(context).edit().putString("custom", JSONArray(kept + loginUrl).toString()).apply()
    }

    fun removeCustom(context: Context, id: String) {
        val kept = custom(context).filter { it.id != id }.map { it.loginUrl }
        prefs(context).edit().putString("custom", JSONArray(kept).toString()).apply()
    }

    // ── username/password logins, keyed by gallery-dl site name ──

    fun credentials(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString("credentials", "{}")!!) }.getOrDefault(JSONObject())

    fun setCredentials(context: Context, site: String, username: String?, password: String?) {
        val all = credentials(context)
        if (username.isNullOrBlank()) all.remove(site)
        else all.put(site, JSONObject().put("username", username).put("password", password ?: ""))
        prefs(context).edit().putString("credentials", all.toString()).apply()
    }

    /** Sites whose last download was refused for lack of a (valid) login. */
    fun stale(context: Context): Set<String> = prefs(context).getStringSet("stale", emptySet()) ?: emptySet()

    fun setStale(context: Context, id: String, stale: Boolean) {
        val current = stale(context)
        val next = if (stale) current + id else current - id
        if (next != current) prefs(context).edit().putStringSet("stale", next).apply()
    }
}
