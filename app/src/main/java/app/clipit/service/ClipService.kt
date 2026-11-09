package app.clipit.service

import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
import app.clipit.data.Prefs
import app.clipit.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The only long-lived component. It exists while the watcher is switched on
 * or a download is running, and stops itself otherwise so nothing lingers.
 */
class ClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false
    private var started = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Fires only while Clip It itself has focus on current Android; the
        // accessibility service covers copies made in other apps.
        if (Prefs.watching) Links.handle(this, Links.clipboardText(this), auto = true)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Downloader.pump(this@ClipService)
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
            if (Downloader.waitingForWifi) Downloader.pump(this@ClipService)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(0)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            combine(Downloader.active, Prefs.changes) { active, _ -> active }.collect { active ->
                // Wait for the first start command, which may carry work to do.
                if (started) sync(active)
            }
        }
    }

    private fun goForeground(active: Int) {
        val notification = Notify.service(this, Prefs.watching, active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notify.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notify.SERVICE_ID, notification)
        }
    }

    /** Brings the notification, clipboard listener and wake lock in line with the current state. */
    private fun sync(active: Int) {
        val watching = Prefs.watching
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (watching && !listening) clipboard.addPrimaryClipChangedListener(clipListener)
        if (!watching && listening) clipboard.removePrimaryClipChangedListener(clipListener)
        listening = watching

        // Keep the CPU awake only for as long as something is downloading.
        if (active > 0 && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipit:download").apply { acquire(6 * 60 * 60 * 1000L) }
        } else if (active == 0) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }

        if (!watching && active == 0 && (Downloader.waitingForWifi || Repo.queued().isEmpty())) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            goForeground(active)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this after every startForegroundService call, even if we stop right away.
        goForeground(Downloader.active.value)
        when (intent?.action) {
            ACTION_CANCEL -> Downloader.cancel(this, intent.getLongExtra(EXTRA_ID, 0))
            ACTION_RETRY -> Downloader.redownload(this, intent.getLongExtra(EXTRA_ID, 0))
            ACTION_WATCH_OFF -> setWatching(this, false)
        }
        started = true
        Downloader.pump(this)
        sync(Downloader.active.value)
        return if (Prefs.watching) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (listening) getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.clipit.CANCEL"
        const val ACTION_RETRY = "app.clipit.RETRY"
        const val ACTION_WATCH_OFF = "app.clipit.WATCH_OFF"
        const val EXTRA_ID = "id"

        /** Starts the service, falling back to running the queue directly if Android refuses. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ClipService::class.java))
            true
        } catch (e: Exception) {
            Downloader.pump(context)
            false
        }

        /** The single switch behind the quick settings tile, the app toggle and the notification. */
        fun setWatching(context: Context, on: Boolean) {
            Prefs.watching = on
            if (on) start(context)
            ClipAccessibilityService.refresh()
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, WatchTileService::class.java))
            }
        }
    }
}
