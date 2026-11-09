package app.clipit

import android.app.Application
import app.clipit.data.Prefs
import app.clipit.data.Repo
import app.clipit.service.Notify

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Repo.init(this)
        Notify.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
