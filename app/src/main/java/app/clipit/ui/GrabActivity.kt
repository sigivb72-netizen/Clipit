package app.clipit.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.clipit.service.Links

/**
 * An invisible activity. It is the share-sheet target ("Download with Clip
 * It") and, because only a focused window may read the clipboard, also what
 * the notification button and quick settings tile launch to grab a copied link.
 */
class GrabActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            finishWith(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) finishWith(Links.clipboardText(this))
    }

    private fun finishWith(text: CharSequence?) {
        handled = true
        val message = when (Links.handle(this, text, auto = false)) {
            Links.Outcome.QUEUED -> "Clip It: download started"
            Links.Outcome.DUPLICATE -> "Clip It: already downloading that link"
            else -> "Clip It: no link found"
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val ACTION_CLIPBOARD = "app.clipit.GRAB_CLIPBOARD"
    }
}
