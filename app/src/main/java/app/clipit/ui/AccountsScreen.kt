package app.clipit.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.clipit.engine.CookieStore
import app.clipit.engine.Site
import app.clipit.engine.Sites

private class SiteState(val site: Site, val loggedIn: Boolean, val stale: Boolean)

private fun login(context: Context, site: Site) {
    context.startActivity(Intent(context, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SITE, site.id))
}

@Composable
fun AccountsScreen(resumes: Int) {
    val context = LocalContext.current
    var edits by remember { mutableIntStateOf(0) }
    val sites = remember(resumes, edits) {
        val stale = Sites.stale(context)
        Sites.all(context).map { SiteState(it, CookieStore.isLoggedIn(context, it.id), it.id in stale) }
    }
    val imported = remember(resumes, edits) { CookieStore.isLoggedIn(context, CookieStore.IMPORTED) }
    val credentials = remember(resumes, edits) {
        val all = Sites.credentials(context)
        all.keys().asSequence().map { it to all.getJSONObject(it).optString("username") }.toList()
    }
    var manage by remember { mutableStateOf<Site?>(null) }
    var addingSite by remember { mutableStateOf(false) }
    var addingPassword by remember { mutableStateOf(false) }

    val pickCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val count = runCatching { CookieStore.import(context, uri) }.getOrDefault(0)
            Toast.makeText(
                context,
                if (count > 0) "Imported $count cookies" else "That file is not a cookies.txt export",
                Toast.LENGTH_LONG,
            ).show()
            edits++
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Some sites only show videos to logged-in users. Log in once and Clip It hands the " +
                    "session to the downloader. Logins are stored on this device only.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(sites, key = { it.site.id }) { state ->
            val site = state.site
            ListItem(
                headlineContent = { Text(site.name) },
                supportingContent = {
                    Text(
                        when {
                            state.stale -> "Login expired or was rejected. Log in again."
                            state.loggedIn -> "Ready"
                            else -> site.note ?: "Not logged in"
                        },
                        color = when {
                            state.stale -> MaterialTheme.colorScheme.error
                            state.loggedIn -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = {
                    when {
                        state.stale -> Icon(Icons.Default.Warning, "Needs attention", tint = MaterialTheme.colorScheme.error)
                        state.loggedIn -> Icon(Icons.Default.CheckCircle, "Ready", tint = MaterialTheme.colorScheme.primary)
                        else -> Text("Log in", color = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.clickable {
                    if (state.loggedIn) manage = site else login(context, site)
                },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Other site…") },
                supportingContent = { Text("Log in to any site by its address") },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable { addingSite = true },
            )
            ListItem(
                headlineContent = { Text("Import cookies.txt") },
                supportingContent = {
                    Text(if (imported) "Imported cookies in use. Tap to add more." else "Use a cookies file exported from a browser")
                },
                leadingContent = { Icon(Icons.Default.Add, null) },
                trailingContent = if (imported) {
                    {
                        TextButton(onClick = { CookieStore.logout(context, null, CookieStore.IMPORTED); edits++ }) {
                            Text("Remove")
                        }
                    }
                } else null,
                modifier = Modifier.clickable { pickCookies.launch(arrayOf("text/*", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Username and password…") },
                supportingContent = { Text("For sites gallery-dl signs in to directly, such as Bluesky") },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable { addingPassword = true },
            )
        }
        items(credentials, key = { "cred-" + it.first }) { (site, username) ->
            ListItem(
                headlineContent = { Text(site) },
                supportingContent = { Text("Signs in as $username") },
                trailingContent = {
                    TextButton(onClick = { Sites.setCredentials(context, site, null, null); edits++ }) { Text("Remove") }
                },
            )
        }
    }

    manage?.let { site ->
        AlertDialog(
            onDismissRequest = { manage = null },
            title = { Text(site.name) },
            text = { Text("Clip It is logged in to ${site.name}.") },
            confirmButton = {
                TextButton(onClick = { manage = null; login(context, site) }) { Text("Log in again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CookieStore.logout(context, site, site.id)
                    if (site.custom) Sites.removeCustom(context, site.id)
                    manage = null
                    edits++
                }) { Text("Log out") }
            },
        )
    }

    if (addingSite) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingSite = false },
            title = { Text("Log in to another site") },
            text = {
                OutlinedTextField(
                    value = address, onValueChange = { address = it.trim() }, singleLine = true,
                    label = { Text("Login page address") }, placeholder = { Text("example.com/login") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = address.contains('.'),
                    onClick = {
                        val url = if (address.startsWith("http")) address else "https://$address"
                        addingSite = false
                        context.startActivity(
                            Intent(context, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_URL, url)
                        )
                    },
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { addingSite = false }) { Text("Cancel") } },
        )
    }

    if (addingPassword) {
        var site by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingPassword = false },
            title = { Text("Username and password") },
            text = {
                Column {
                    OutlinedTextField(
                        value = site, onValueChange = { site = it.trim().lowercase() }, singleLine = true,
                        label = { Text("Site name") }, placeholder = { Text("bluesky") },
                        supportingText = { Text("The site's name as gallery-dl knows it") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username, onValueChange = { username = it }, singleLine = true,
                        label = { Text("Username") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = site.isNotEmpty() && username.isNotBlank(),
                    onClick = {
                        Sites.setCredentials(context, site, username.trim(), password)
                        addingPassword = false
                        edits++
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addingPassword = false }) { Text("Cancel") } },
        )
    }
}
