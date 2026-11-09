package app.clipit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                MainActivity.TAB_ACCOUNTS -> AccountsScreen(resumes)
                MainActivity.TAB_SETTINGS -> SettingsScreen(resumes)
                else -> DownloadsScreen(snackbar, onOpenAccounts = { onTab(MainActivity.TAB_ACCOUNTS) })
            }
        }
    }
}
