package app.clipit

import android.app.Application
import android.os.Build
import app.clipit.data.Prefs
import app.clipit.data.Repo
import app.clipit.service.Notify
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        Prefs.init(this)
        Repo.init(this)
        Notify.createChannels(this)
    }

    /**
     * Writes the stack trace of a fatal error to a file before the app dies,
     * so the next launch can show what happened and let the user send it on.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                crashFile(this).writeText(
                    "Clip It $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n${Date()} · thread ${thread.name}\n\n$trace"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun crashFile(app: Application) = File(app.filesDir, "last-crash.txt")
    }
}
