package app.clipit.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.clipit.engine.CookieStore
import app.clipit.engine.Site
import app.clipit.engine.Sites

/**
 * Signs in to a site in a web view. Once the session cookies appear they are
 * exported for the downloaders and the site is marked ready.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var site: Site
    private var webView: WebView? = null
    private var signedIn by mutableStateOf(false)
    private var loading by mutableFloatStateOf(0f)

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_SITE)?.let { Sites.byId(this, it) }
            ?: intent.getStringExtra(EXTRA_URL)?.let { Sites.customSite(it) }
            ?: return finish()

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Several sites (Google above all) turn away embedded web views;
            // presenting as the ordinary mobile browser avoids that.
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s"""), "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = check()
                override fun onPageFinished(view: WebView?, url: String?) = check()
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = check()
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loading = newProgress / 100f
                    if (newProgress == 100) check()
                }
            }
            loadUrl(site.loginUrl)
        }
        webView = view

        setContent {
            ClipItTheme {
                BackHandler { if (view.canGoBack()) view.goBack() else finish() }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Log in to ${site.name}") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(Icons.Default.Close, "Close") }
                            },
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp).imePadding()) {
                                Text(
                                    when {
                                        signedIn && site.required.isEmpty() ->
                                            "When you have finished logging in, save the session."
                                        signedIn -> "You are logged in. Save to make ${site.name} ready."
                                        else -> "Log in as usual. Clip It keeps the session on this device only."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Button(
                                    onClick = ::save, enabled = signedIn,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                ) { Text("Save login") }
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (loading < 1f) LinearProgressIndicator(progress = { loading }, modifier = Modifier.fillMaxWidth())
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun check() {
        signedIn = CookieStore.sessionPresent(site)
    }

    private fun save() {
        if (CookieStore.capture(this, site)) {
            if (site.custom) Sites.addCustom(this, site.loginUrl)
            Toast.makeText(this, "${site.name} is ready", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            signedIn = false
            Toast.makeText(this, "Not logged in yet", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_URL = "url"
    }
}
