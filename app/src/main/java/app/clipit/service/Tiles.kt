package app.clipit.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.clipit.data.Prefs
import app.clipit.ui.GrabActivity

/** Quick settings toggle: switches link watching on and off. */
class WatchTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        ClipService.setWatching(this, !Prefs.watching)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = Prefs.watching
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "Watching for links" else "Off"
        tile.updateTile()
    }
}

/** Quick settings button: downloads whatever link is on the clipboard right now. */
class GrabTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = Intent(this, GrabActivity::class.java).setAction(GrabActivity.ACTION_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
