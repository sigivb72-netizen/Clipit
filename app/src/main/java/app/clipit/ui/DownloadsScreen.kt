package app.clipit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clipit.R
import app.clipit.data.DownloadItem
import app.clipit.data.Prefs
import app.clipit.data.Progress
import app.clipit.data.Repo
import app.clipit.data.SavedFile
import app.clipit.data.Status
import app.clipit.engine.Sites
import app.clipit.service.Downloader
import app.clipit.service.Links
import app.clipit.service.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable
fun DownloadsScreen(snackbar: SnackbarHostState, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    val items by Repo.items.collectAsState()
    val progress by Repo.progress.collectAsState()
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var detailsFor by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (items.any { !it.active }) {
                    item(key = "header") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { confirmClear = true }) { Text("Clear finished") }
                        }
                    }
                }
                items(items, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        progress = progress[item.id],
                        onClick = {
                            if (item.status == Status.DONE) openFile(context, item.files.firstOrNull())
                            else menuFor = item.id
                        },
                        onLongClick = { menuFor = item.id },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Add link") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    items.firstOrNull { it.id == menuFor }?.let { item ->
        ActionsSheet(
            item = item,
            onDismiss = { menuFor = null },
            onDetails = { detailsFor = item.id },
            onOpenAccounts = onOpenAccounts,
        )
    }
    items.firstOrNull { it.id == detailsFor }?.let { item ->
        DetailsSheet(item) { detailsFor = null }
    }
    if (adding) {
        val scope = rememberCoroutineScope()
        AddLinkDialog(onDismiss = { adding = false }) { text ->
            adding = false
            val message = when (Links.handle(context, text, auto = false)) {
                Links.Outcome.QUEUED -> "Download started"
                Links.Outcome.DUPLICATE -> "Already downloading that link"
                else -> "That does not look like a link"
            }
            scope.launch { snackbar.showSnackbar(message) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear finished downloads?") },
            text = { Text("They are removed from this list only. The files stay in Download/${MediaSaver.FOLDER}.") },
            confirmButton = {
                TextButton(onClick = { Repo.clearFinished(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_clip), null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Switch Clip It on, then copy a link to a video or post. You can also share a link to " +
                "Clip It from any app, or paste one with Add link.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(item: DownloadItem, progress: Progress?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                item.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            )
            val source = listOfNotNull(item.uploader, item.site ?: Uri.parse(item.url).host).joinToString(" · ")
            if (source.isNotEmpty()) {
                Text(
                    source, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            when (item.status) {
                Status.RUNNING -> RunningStatus(progress ?: Progress())
                Status.QUEUED -> StatusText(if (Downloader.waitingForWifi) "Waiting for Wi-Fi" else "Queued")
                Status.DONE -> StatusText(
                    buildString {
                        append(formatBytes(item.size))
                        if (item.files.size > 1) append(" · ${item.files.size} files")
                        append(" · ${formatDate(item.finished)}")
                    }
                )
                Status.FAILED -> Text(
                    if (item.errorCode == "auth") "Login needed. Hold for options." else item.error ?: "Failed",
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                else -> StatusText("Canceled")
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RunningStatus(p: Progress) {
    val fraction = p.fraction
    if (fraction != null && p.stage == "downloading") {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(4.dp))
    StatusText(
        when (p.stage) {
            "merging" -> "Finishing up…"
            "saving" -> "Saving…"
            "starting" -> "Starting…"
            else -> buildString {
                if (p.count > 1) append("${p.index}/${p.count} · ")
                if (fraction != null) append("${(fraction * 100).toInt()}% of ${formatBytes(p.total)}")
                else append(formatBytes(p.downloaded))
                if (p.speed > 0) append(" · ${formatBytes(p.speed)}/s")
                if (p.eta > 0) append(" · ${formatDuration(p.eta)} left")
            }
        }
    )
}

private val thumbnails = LruCache<String, ImageBitmap>(80)

private fun loadThumbnail(context: Context, file: SavedFile): ImageBitmap? {
    val uri = Uri.parse(file.uri)
    val size = Size(192, 192)
    val bitmap: Bitmap? = runCatching {
        if (uri.scheme == "file") {
            val path = File(uri.path!!)
            if (file.mime.startsWith("video")) ThumbnailUtils.createVideoThumbnail(path, size, null)
            else ThumbnailUtils.createImageThumbnail(path, size, null)
        } else {
            context.contentResolver.loadThumbnail(uri, size, null)
        }
    }.getOrNull()
    return bitmap?.asImageBitmap()
}

@Composable
private fun Thumbnail(item: DownloadItem) {
    val context = LocalContext.current
    val file = item.files.firstOrNull()?.takeIf { it.mime.startsWith("video") || it.mime.startsWith("image") }
    val image by produceState(initialValue = file?.let { thumbnails.get(it.uri) }, key1 = file?.uri) {
        if (value == null && file != null) {
            value = withContext(Dispatchers.IO) { loadThumbnail(context, file) }?.also { thumbnails.put(file.uri, it) }
        }
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (file?.mime?.startsWith("video") == true) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        } else {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            when (item.status) {
                Status.FAILED -> Icon(
                    if (item.errorCode == "auth") Icons.Default.Lock else Icons.Default.Warning, null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(painterResource(R.drawable.ic_download), null, tint = tint)
            }
        }
    }
}

// ── actions ──

private fun openFile(context: Context, file: SavedFile?) {
    if (file == null) return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        val missing = !MediaSaver.exists(context, file)
        Toast.makeText(
            context, if (missing) "The file was moved or deleted. Hold to redownload." else "No app can open this file",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun shareFiles(context: Context, item: DownloadItem) {
    val uris = ArrayList(item.files.map { Uri.parse(it.uri) })
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(item.files[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(item: DownloadItem, onDismiss: () -> Unit, onDetails: () -> Unit, onOpenAccounts: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(
                item.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            @Composable
            fun action(label: String, icon: ImageVector, run: () -> Unit) {
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(icon, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onDismiss(); run() }.padding(horizontal = 8.dp),
                )
            }
            if (item.status == Status.DONE) {
                action("Open", Icons.Default.PlayArrow) { openFile(context, item.files.firstOrNull()) }
                action("Share", Icons.Default.Share) { shareFiles(context, item) }
            }
            if (item.status == Status.FAILED && item.errorCode == "auth") {
                val site = Sites.forUrl(context, item.url)
                action("Log in to ${site?.name ?: "this site"}", Icons.Default.Lock) {
                    if (site != null) {
                        context.startActivity(
                            Intent(context, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SITE, site.id)
                        )
                    } else onOpenAccounts()
                }
            }
            if (item.active) {
                action("Cancel", Icons.Default.Clear) { Downloader.cancel(context, item.id) }
            } else {
                action(if (item.status == Status.DONE) "Redownload" else "Retry", Icons.Default.Refresh) {
                    Downloader.redownload(context, item.id)
                }
            }
            action("Details", Icons.Default.Info, onDetails)
            action("Open original post", Icons.Default.Share) { openLink(context, item.postUrl ?: item.url) }
            action("Copy link", Icons.Default.Add) {
                copyText(context, item.url)
                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            }
            if (!item.active) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                action("Remove from list", Icons.Default.Clear) { Repo.delete(item.id) }
                if (item.files.isNotEmpty()) {
                    action(if (item.files.size > 1) "Delete files" else "Delete file", Icons.Default.Delete) {
                        val removed = item.files.count { MediaSaver.delete(context, it) }
                        Repo.delete(item.id)
                        if (removed < item.files.size) {
                            Toast.makeText(context, "Some files were already gone or could not be deleted", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── details ──

private val hiddenMeta = setOf("title", "thumbnail", "webpage_url", "category", "subcategory", "extractor_key")

private fun prettyKey(key: String): String =
    key.replace('_', ' ').replace('.', ' ').trim().replaceFirstChar { it.uppercase() }

private fun prettyValue(key: String, value: Any): String = when {
    key == "duration" && value is Number -> formatDuration(value.toLong())
    key == "timestamp" && value is Number -> formatDate(value.toLong() * 1000)
    key.startsWith("filesize") && value is Number -> formatBytes(value.toLong())
    key == "upload_date" && value is String && value.length == 8 ->
        "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6)}"
    else -> value.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(item: DownloadItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val extra = remember(item.meta) {
        val json = runCatching { JSONObject(item.meta ?: "{}") }.getOrDefault(JSONObject())
        json.keys().asSequence().filter { it !in hiddenMeta }
            .map { prettyKey(it) to prettyValue(it, json.get(it)) }.toList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SelectionContainer {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
            ) {
                Text(item.displayTitle, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))

                Detail("Original link", item.url) { openLink(context, item.url) }
                if (item.postUrl != null && item.postUrl != item.url) {
                    Detail("Post", item.postUrl) { openLink(context, item.postUrl) }
                }
                item.uploader?.let { name ->
                    Detail("Uploaded by", name, item.uploaderUrl?.let { url -> { openLink(context, url) } })
                }
                item.site?.let { Detail("Site", it) }
                Detail(
                    "Status",
                    when (item.status) {
                        Status.DONE -> "Downloaded"
                        Status.FAILED -> "Failed"
                        Status.CANCELED -> "Canceled"
                        Status.RUNNING -> "Downloading"
                        else -> "Queued"
                    },
                )
                item.error?.let { Detail("Error", it) }
                item.engine?.takeIf { it.isNotEmpty() }?.let { Detail("Downloaded with", it) }
                Detail("Quality setting", Prefs.qualityLabel(item.quality))
                Detail("Added", formatDate(item.created))
                if (item.finished > 0) Detail("Finished", formatDate(item.finished))
                if (item.size > 0) Detail("Size", formatBytes(item.size))
                item.files.forEach { file ->
                    Detail("Saved file", "Download/${MediaSaver.FOLDER}/${file.name}") { openFile(context, file) }
                }

                if (extra.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("From the post", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    extra.forEach { (key, value) -> Detail(key, value) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        )
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Links.firstUrl(Links.clipboardText(context)) ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add link") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Link") },
                placeholder = { Text("https://…") }, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
