package app.clipit.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clipit.R
import app.clipit.data.Prefs
import app.clipit.engine.Engine
import app.clipit.service.ClipAccessibilityService
import app.clipit.service.ClipService
import app.clipit.service.MediaSaver
import app.clipit.service.WatchTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(resumes: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by Prefs.changes.collectAsState()
    val instant = remember(resumes) { ClipAccessibilityService.isEnabled(context) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }

    // Ask the engine for its versions once, the first time settings are opened.
    LaunchedEffect(Unit) {
        if (Prefs.engineVersions == null) withContext(Dispatchers.IO) { runCatching { Engine.versions(context) } }
    }

    // `tick` is read so every switch below reflects the stored value.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Automatic downloads")
        SwitchRow(
            "Watch for copied links", "The same switch as the quick settings tile. Off means nothing runs in the background.",
            Prefs.watching.also { tick },
        ) { ClipService.setWatching(context, it) }

        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (instant) "Instant grab is on" else "Instant grab is off",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (instant) {
                        "Copying a link in another app starts the download by itself."
                    } else {
                        "Android only lets an app read the clipboard while it is on screen. To start " +
                            "downloads the moment you copy, allow Clip It as an accessibility service. " +
                            "Without it, copy the link and then tap \"Grab copied link\" in the " +
                            "notification or quick settings, or share the link to Clip It."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!instant) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "If the switch there is greyed out: open App info for Clip It, tap the three dots " +
                            "and choose \"Allow restricted settings\". Android asks this of apps installed from a file.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    }) { Text(if (instant) "Accessibility settings" else "Turn on") }
                    if (!instant) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                )
                            }
                        }) { Text("App info") }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ListItem(
                headlineContent = { Text("Add the quick settings tile") },
                supportingContent = { Text("Puts the Clip It switch next to torch and aeroplane mode") },
                modifier = Modifier.clickable {
                    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, WatchTileService::class.java), "Clip It",
                        Icon.createWithResource(context, R.drawable.ic_clip), context.mainExecutor,
                    ) { }
                },
            )
        }
        SwitchRow(
            "Grab every copied link", "Off: only links from known video and social sites start by themselves.",
            Prefs.allLinks,
        ) { Prefs.allLinks = it }
        SwitchRow("Resume after restart", "Keep watching after the phone reboots, if it was on.", Prefs.startOnBoot) {
            Prefs.startOnBoot = it
        }

        Section("Downloads")
        ChoiceRow(
            "Video quality", "Applies to every site, for both gallery-dl and yt-dlp. A smaller version is used when this one does not exist.",
            Prefs.QUALITIES, Prefs.quality, Prefs::qualityLabel,
        ) { Prefs.quality = it }
        SwitchRow("Wi-Fi only", "Hold downloads while on mobile data.", Prefs.wifiOnly) {
            Prefs.wifiOnly = it
            ClipService.start(context)
        }
        ChoiceRow("Downloads at once", null, listOf(1, 2, 3, 4), Prefs.parallel, { it.toString() }) { Prefs.parallel = it }
        ChoiceRow(
            "Files per link", "A link to a profile or playlist stops after this many.",
            listOf(1, 5, 10, 25, 50, 100), Prefs.maxItems, { it.toString() },
        ) { Prefs.maxItems = it }
        ListItem(
            headlineContent = { Text("Save location") },
            supportingContent = { Text("Download/${MediaSaver.FOLDER}") },
        )

        Section("Download engines")
        ListItem(
            headlineContent = { Text(Prefs.engineVersions ?: "Checking versions…") },
            supportingContent = {
                Text(updateResult ?: "Sites change often. Update when downloads from a site stop working.")
            },
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !updating,
                onClick = {
                    updating = true
                    updateResult = null
                    scope.launch {
                        updateResult = withContext(Dispatchers.IO) {
                            runCatching { Engine.update(context) }.getOrElse { "Update failed: ${it.message}" }
                        }
                        updating = false
                        Toast.makeText(context, "Engine update finished", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("Update engines") }
            if (updating) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String, subtitle: String?, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Text(label(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = option == selected) {
                                onPick(option)
                                open = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label(option))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
