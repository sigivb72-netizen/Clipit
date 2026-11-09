package app.clipit.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    private var defaultAgent = ""
    private var signedIn by mutableStateOf(false)
    private var loading by mutableFloatStateOf(0f)
    private var host by mutableStateOf("")

    /** Why the page could not be shown, or null while it is fine. */
    private var problem by mutableStateOf<String?>(null)

    /** How the web view presents itself; switchable when a site refuses one. */
    private var mode by mutableIntStateOf(MODE_APP)

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_SITE)?.let { Sites.byId(this, it) }
            ?: intent.getStringExtra(EXTRA_URL)?.let { Sites.customSite(it) }
            ?: return finish()
        // Google turns away anything that says it is an embedded web view.
        mode = if (site.id == "youtube") MODE_BROWSER else MODE_APP

        val view = WebView(this).apply {
            // Without an explicit size the view is measured as wrap-content,
            // and pages laid out against the viewport height collapse to nothing.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false) // "Sign in with…" popups load in place
            defaultAgent = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    problem = null
                    shown(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) = shown(url)
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = shown(url)

                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) problem = "Could not load the page (${error.description})."
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest, response: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        problem = "${request.url.host} refused to show the page (error ${response.statusCode})."
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loading = newProgress / 100f
                    if (newProgress == 100) check()
                }
            }
        }
        webView = view
        applyMode()
        view.loadUrl(site.loginUrl)

        setContent {
            ClipItTheme {
                BackHandler { if (view.canGoBack()) view.goBack() else finish() }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Log in to ${site.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (host.isNotEmpty()) {
                                        Text(
                                            host, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = ::finish) { Icon(Icons.Default.Close, "Close") }
                            },
                            actions = {
                                IconButton(onClick = { problem = null; view.reload() }) {
                                    Icon(Icons.Default.Refresh, "Reload")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            signedIn && site.required.isEmpty() -> "Finished logging in? Save the session."
                                            signedIn -> "You are logged in."
                                            else -> "Log in as usual. The session stays on this device."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    )
                                    Button(onClick = ::save, enabled = signedIn) { Text("Save login") }
                                }
                                if (!signedIn) {
                                    TextButton(onClick = ::nextMode, modifier = Modifier.align(Alignment.End)) {
                                        Text("Page blank or stuck? Try ${MODE_NAMES[(mode + 1) % MODE_NAMES.size]}")
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (loading < 1f) LinearProgressIndicator(progress = { loading }, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                            problem?.let { message ->
                                Surface(Modifier.fillMaxSize()) {
                                    Column(
                                        Modifier.fillMaxSize().padding(32.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(message, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "Try again, or try another mode below. If the site keeps refusing, " +
                                                "import a cookies.txt from the Accounts tab instead.",
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = { problem = null; view.loadUrl(site.loginUrl) }) { Text("Try again") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyMode() {
        val settings = webView?.settings ?: return
        settings.userAgentString = when (mode) {
            MODE_BROWSER -> defaultAgent.replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s"""), "")
            MODE_DESKTOP -> DESKTOP_AGENT
            else -> defaultAgent
        }
    }

    /** Reloads the login page presenting as a different kind of browser. */
    private fun nextMode() {
        mode = (mode + 1) % MODE_NAMES.size
        applyMode()
        problem = null
        webView?.loadUrl(site.loginUrl)
    }

    private fun shown(url: String?) {
        host = url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: host
        check()
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

        private const val MODE_APP = 0
        private const val MODE_BROWSER = 1
        private const val MODE_DESKTOP = 2
        private val MODE_NAMES = listOf("standard mode", "browser mode", "desktop mode")
        private const val DESKTOP_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
