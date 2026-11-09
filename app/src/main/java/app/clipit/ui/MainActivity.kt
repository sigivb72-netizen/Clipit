package app.clipit.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clipit.App
import app.clipit.R
import app.clipit.data.Prefs
import app.clipit.data.Repo
import app.clipit.service.ClipService

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(TAB_DOWNLOADS)

    /** Bumped on every return to the app so screens re-check outside state. */
    private var resumes by mutableIntStateOf(0)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_TAB)?.let { tab = it }
        setContent {
            ClipItTheme { Root(tab, resumes) { tab = it } }
        }
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Pick up anything left queued by an earlier session.
        if (Prefs.watching || Repo.queued().isNotEmpty()) ClipService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_DOWNLOADS = "downloads"
        const val TAB_ACCOUNTS = "accounts"
        const val TAB_SETTINGS = "settings"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(tab: String, resumes: Int, onTab: (String) -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val prefsTick by Prefs.changes.collectAsState()
    val watching = remember(prefsTick) { Prefs.watching }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            MainActivity.TAB_ACCOUNTS -> "Accounts"
                            MainActivity.TAB_SETTINGS -> "Settings"
                            else -> "Clip It"
                        }
                    )
                },
                actions = {
                    Text(if (watching) "On" else "Off", modifier = Modifier.padding(end = 8.dp))
                    Switch(
                        checked = watching,
                        onCheckedChange = { ClipService.setWatching(context, it) },
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_DOWNLOADS,
                    onClick = { onTab(MainActivity.TAB_DOWNLOADS) },
                    icon = { Icon(painterResource(R.drawable.ic_download), null) },
                    label = { Text("Downloads") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_ACCOUNTS,
                    onClick = { onTab(MainActivity.TAB_ACCOUNTS) },
                    icon = { Icon(Icons.Default.AccountCircle, null) },
                    label = { Text("Accounts") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_SETTINGS,
                    onClick = { onTab(MainActivity.TAB_SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CrashReport()
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                MainActivity.TAB_ACCOUNTS -> AccountsScreen(resumes)
                MainActivity.TAB_SETTINGS -> SettingsScreen(resumes)
                else -> DownloadsScreen(snackbar, onOpenAccounts = { onTab(MainActivity.TAB_ACCOUNTS) })
            }
        }
    }
}

/** After a crash, shows what went wrong on the next launch so it can be passed on. */
@Composable
private fun CrashReport() {
    val context = LocalContext.current
    val file = remember { App.crashFile(context.applicationContext as Application) }
    var report by remember { mutableStateOf(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()) }
    val text = report ?: return
    fun dismiss() {
        file.delete()
        report = null
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Clip It crashed last time") },
        text = {
            Column {
                Text("Send this report so the problem can be fixed.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text.take(6000), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "Send crash report")) }
            }) { Text("Share") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Clip It crash report", text))
                }) { Text("Copy") }
                TextButton(onClick = ::dismiss) { Text("Close") }
            }
        },
    )
}
