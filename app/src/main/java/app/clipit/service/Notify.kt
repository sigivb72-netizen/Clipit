package app.clipit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.clipit.R
import app.clipit.data.DownloadItem
import app.clipit.data.Progress
import app.clipit.ui.GrabActivity
import app.clipit.ui.MainActivity
import app.clipit.ui.formatBytes

object Notify {
    private const val CH_SERVICE = "service"
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val SERVICE_ID = 1
    private const val ITEM_BASE = 1000

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun createChannels(context: Context) {
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Link watcher", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Clip It is switched on"
                    setShowBadge(false)
                },
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openApp(context: Context, tab: String? = null): PendingIntent = PendingIntent.getActivity(
        context, tab.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        IMMUTABLE,
    )

    private fun serviceAction(context: Context, action: String, id: Long = 0): PendingIntent =
        PendingIntent.getService(
            context, (action.hashCode() * 31 + id).toInt(),
            Intent(context, ClipService::class.java).setAction(action).putExtra(ClipService.EXTRA_ID, id),
            IMMUTABLE,
        )

    fun service(context: Context, watching: Boolean, active: Int): Notification {
        val builder = Notification.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_clip)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val title = when {
            active > 0 -> "Downloading $active ${if (active == 1) "item" else "items"}"
            else -> "Clip It is on"
        }
        builder.setContentTitle(title)
        if (watching) {
            builder.setContentText("Copy a link to download it")
            builder.addAction(
                Notification.Action.Builder(
                    null, "Grab copied link",
                    PendingIntent.getActivity(
                        context, 1,
                        Intent(context, GrabActivity::class.java).setAction(GrabActivity.ACTION_CLIPBOARD)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        IMMUTABLE,
                    ),
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(null, "Turn off", serviceAction(context, ClipService.ACTION_WATCH_OFF)).build()
            )
        }
        return builder.build()
    }

    fun progress(context: Context, item: DownloadItem, progress: Progress) {
        val text = buildString {
            when (progress.stage) {
                "merging" -> append("Finishing up…")
                "saving" -> append("Saving…")
                else -> {
                    if (progress.count > 1) append("${progress.index}/${progress.count} · ")
                    if (progress.total > 0) append("${formatBytes(progress.downloaded)} of ${formatBytes(progress.total)}")
                    else if (progress.downloaded > 0) append(formatBytes(progress.downloaded))
                    else append("Starting…")
                    if (progress.speed > 0) append(" · ${formatBytes(progress.speed)}/s")
                }
            }
        }
        val fraction = progress.fraction
        val builder = Notification.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(item.displayTitle)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup("downloads")
            .setContentIntent(openApp(context))
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null || progress.stage != "downloading")
            .addAction(
                Notification.Action.Builder(null, "Cancel", serviceAction(context, ClipService.ACTION_CANCEL, item.id)).build()
            )
        manager(context).notify(ITEM_BASE + item.id.toInt(), builder.build())
    }

    fun done(context: Context, item: DownloadItem) {
        val first = item.primaryFile
        val tap = if (first != null) {
            PendingIntent.getActivity(
                context, item.id.toInt(),
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(first.uri), first.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                IMMUTABLE,
            )
        } else openApp(context)
        val detail = buildString {
            if (item.files.size > 1) append("${item.files.size} files · ")
            append(formatBytes(item.size))
            item.uploader?.let { append(" · $it") }
        }
        val builder = Notification.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(item.displayTitle)
            .setContentText("Saved to Download/${MediaSaver.FOLDER} · $detail")
            .setAutoCancel(true)
            .setContentIntent(tap)
        manager(context).notify(ITEM_BASE + item.id.toInt(), builder.build())
    }

    fun failed(context: Context, item: DownloadItem, needsLogin: Boolean, siteName: String?) {
        val builder = Notification.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_clip)
            .setContentTitle(if (needsLogin) "${siteName ?: "This site"} needs you to log in" else "Download failed")
            .setContentText(if (needsLogin) "Tap to log in, then retry" else item.error ?: item.url)
            .setStyle(Notification.BigTextStyle().bigText(item.error ?: item.url))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, if (needsLogin) MainActivity.TAB_ACCOUNTS else null))
            .addAction(
                Notification.Action.Builder(null, "Retry", serviceAction(context, ClipService.ACTION_RETRY, item.id)).build()
            )
        manager(context).notify(ITEM_BASE + item.id.toInt(), builder.build())
    }

    fun cancel(context: Context, id: Long) = manager(context).cancel(ITEM_BASE + id.toInt())
}
