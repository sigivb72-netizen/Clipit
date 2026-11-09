package app.clipit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clipit.data.Prefs

/** Resumes watching after a restart or app update if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.watching && Prefs.startOnBoot) ClipService.start(context)
    }
}
