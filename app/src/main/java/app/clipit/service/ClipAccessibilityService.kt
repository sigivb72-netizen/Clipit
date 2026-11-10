package app.clipit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.clipit.data.Prefs
import app.clipit.ui.GrabActivity
import java.lang.ref.WeakReference

/**
 * Makes "copy a link and it downloads" possible.
 *
 * Android only lets the focused app read the clipboard, so a background
 * service never sees what was copied. This service notices the moment of
 * copying (a tap on something labelled "Copy", a "Copied" toast, or the
 * system's clipboard preview appearing) and then takes focus for an instant
 * with an invisible one-pixel window, which is enough to read the link.
 *
 * It asks for no window content and inspects only the label of the tapped
 * control. While the watcher is off it unsubscribes from all events.
 */
class ClipAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var probe: View? = null
    private val readClipboard = Runnable { read() }

    override fun onServiceConnected() {
        instance = WeakReference(this)
        applyState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeProbe()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    private fun applyState() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = if (Prefs.watching) EVENTS else 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!Prefs.watching || event.packageName == packageName) return
        val copied = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName == "com.android.systemui" &&
                    event.className?.contains("clipboard", ignoreCase = true) == true
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                mentionsCopy(event)
            else -> false
        }
        if (!copied) return
        Log.d(TAG, "copy noticed in ${event.packageName}")
        // Give the source app a moment to actually place the text.
        handler.removeCallbacks(readClipboard)
        handler.postDelayed(readClipboard, 350)
    }

    private fun mentionsCopy(event: AccessibilityEvent): Boolean {
        event.contentDescription?.let { if (COPY_WORDS.containsMatchIn(it)) return true }
        for (text in event.text) if (text != null && text.length < 80 && COPY_WORDS.containsMatchIn(text)) return true
        return false
    }

    private fun read() {
        // Works without the probe on the rare occasions we already have focus.
        Links.clipboardText(this)?.let {
            Links.handle(this, it, auto = true)
            return
        }
        if (probe != null) return
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) return
                post {
                    val text = Links.clipboardText(context)
                    Log.d(TAG, "probe focused, clipboard ${if (text == null) "unreadable" else "read"}")
                    handler.removeCallbacks(fallback)
                    removeProbe()
                    if (text != null) Links.handle(this@ClipAccessibilityService, text, auto = true)
                    else fallback.run()
                }
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (so the clipboard can be read) but never in the way of touches.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            probe = view
            handler.postDelayed(fallback, 700)
        } catch (e: Exception) {
            Log.w(TAG, "probe window refused", e)
            probe = null
            fallback.run()
        }
    }

    /**
     * If the probe window is not given focus, an invisible activity is: it
     * interrupts the foreground app for a moment but always works.
     */
    private val fallback = Runnable {
        removeProbe()
        Log.d(TAG, "probe got no focus, using the grab activity")
        runCatching {
            startActivity(
                Intent(this, GrabActivity::class.java).setAction(GrabActivity.ACTION_AUTO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onFailure { Log.w(TAG, "grab activity refused", it) }
    }

    private fun removeProbe() {
        val view = probe ?: return
        probe = null
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    companion object {
        private const val TAG = "ClipIt"
        private const val EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        /** "Copy"/"Copied" in the languages Android is most used in. */
        private val COPY_WORDS = Regex(
            "cop(y|ied|iar|iado|ier|ié|ia|iato)|kopi|копи|复制|複製|已复制|コピー|복사|نسخ|कॉपी|sao chép|salin|kopya|zkopírov|skopiow",
            RegexOption.IGNORE_CASE,
        )

        @Volatile
        private var instance: WeakReference<ClipAccessibilityService>? = null

        /** Re-subscribes or unsubscribes after the watcher is toggled. */
        fun refresh() {
            instance?.get()?.let { service -> service.handler.post { service.applyState() } }
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, ClipAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
