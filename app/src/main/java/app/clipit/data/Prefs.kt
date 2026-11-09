package app.clipit.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings. [changes] ticks on every write so the UI can re-read. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val tick = MutableStateFlow(0)
    val changes: StateFlow<Int> = tick

    const val DEFAULT_QUALITY = 720
    val QUALITIES = listOf(0, 2160, 1440, 1080, 720, 480, 360)

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        tick.value++
    }

    /** Whether copied links are being watched for (the quick settings toggle). */
    var watching: Boolean
        get() = sp.getBoolean("watching", false)
        set(v) = put { putBoolean("watching", v) }

    /** Maximum video resolution; 0 means best available. */
    var quality: Int
        get() = sp.getInt("quality", DEFAULT_QUALITY)
        set(v) = put { putInt("quality", v) }

    /** Auto-grab every copied link rather than only well-known media sites. */
    var allLinks: Boolean
        get() = sp.getBoolean("all_links", false)
        set(v) = put { putBoolean("all_links", v) }

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", false)
        set(v) = put { putBoolean("wifi_only", v) }

    var parallel: Int
        get() = sp.getInt("parallel", 2)
        set(v) = put { putInt("parallel", v.coerceIn(1, 4)) }

    /** Cap on files taken from a single link, so a profile link stays small. */
    var maxItems: Int
        get() = sp.getInt("max_items", 10)
        set(v) = put { putInt("max_items", v.coerceIn(1, 200)) }

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = put { putBoolean("start_on_boot", v) }

    /** A gallery-dl wheel downloaded by the in-app updater, if any. */
    var gdlWheel: String?
        get() = sp.getString("gdl_wheel", null)
        set(v) = put { putString("gdl_wheel", v) }

    var engineVersions: String?
        get() = sp.getString("engine_versions", null)
        set(v) = put { putString("engine_versions", v) }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(v) = put { putBoolean("asked_notifications", v) }

    fun qualityLabel(q: Int) = if (q == 0) "Best available" else "${q}p"
}
