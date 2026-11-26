package app.tgplayer.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.tgplayer.local.DropFile
import app.tgplayer.local.DropIt
import app.tgplayer.local.DropItSession
import app.tgplayer.local.FoundServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drop It: pick files, tap a phone nearby, it asks its owner, the files go over the Wi-Fi. While this screen is
 * open the phone is visible to others and can receive. [initial] are file URIs to start with (a long-pressed file).
 */
@Composable
fun DropItScreen(initial: List<String>, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onExit)
    val me = remember { DropIt.deviceName(context) }
    val session = remember { DropItSession(context) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.stop() }
    }
    val peers by session.peers.collectAsState()
    // Transfers stop if the phone sleeps mid-way, so the screen stays on while this is open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Android 17+ asks before an app talks to other devices on the local network.
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 37) askLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }

    val files = remember { mutableStateListOf<DropFile>() }
    suspend fun add(uris: List<Uri>) {
        val found = withContext(Dispatchers.IO) { uris.mapNotNull { DropIt.describe(context, it) } }
        files += found.filter { f -> files.none { it.uri == f.uri } }
        if (found.size < uris.size) Toast.makeText(context, "Some files couldn't be read", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) { add(initial.map(Uri::parse)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch { add(uris) } }

    /** What each phone shows under its name while sending to it, by address. */
    val status = remember { mutableStateMapOf<String, String>() }
    val progress = remember { mutableStateMapOf<String, Float>() }
    fun send(peer: FoundServer) {
        if (files.isEmpty()) return Toast.makeText(context, "Choose files to send first", Toast.LENGTH_SHORT).show()
        if (peer.address in progress) return
        val batch = files.toList()
        status[peer.address] = "Waiting for ${peer.name} to accept…"
        progress[peer.address] = 0f
        scope.launch {
            var shown = -1 // last percent put on screen: the callback runs for every 64 KB
            status[peer.address] = try {
                val accepted = DropIt.send(context, peer, me, batch) { sent, total ->
                    // Held at 99% until send() returns: these are bytes handed to the network, which outrun the
                    // receiver writing them to disk, so showing 100% here while the other phone is still at 70%
                    // looked broken. "Sent" below is the real done signal.
                    val pct = (if (total > 0) (sent * 100 / total).toInt() else 99).coerceAtMost(99)
                    if (pct != shown) {
                        shown = pct
                        scope.launch { status[peer.address] = "Sending · $pct%"; progress[peer.address] = pct / 100f }
                    }
                }
                if (accepted) "Sent ${batch.size} file${if (batch.size == 1) "" else "s"}" else "Declined"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message ?: "connection lost"}"
            } finally {
                progress.remove(peer.address)
            }
        }
    }

    val others = peers
    val receiving by session.receiving.collectAsState()
    LazyColumn(modifier, contentPadding = bottomPad) {
        item { BackHeader("Drop It", "Visible to nearby phones as $me", onExit) }
        receiving?.let { r ->
            item {
                ListRow(
                    when {
                        r.failed -> "Transfer from ${r.from} stopped"
                        r.finished -> "Received ${r.count} file${if (r.count == 1) "" else "s"} from ${r.from}"
                        else -> "Receiving from ${r.from} · ${(r.doneBytes * 100 / r.totalBytes.coerceAtLeast(1)).toInt()}%"
                    },
                    if (r.finished) "Saved in Download/${DropIt.FOLDER}" else "${formatSize(r.doneBytes)} of ${formatSize(r.totalBytes)}",
                    {},
                ) { IconTile(Icons.Rounded.Download, Cinema.Accent, tint = Cinema.OnAccent) }
            }
        }
        item { SectionTitle("Files to send", Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)) }
        items(files, key = { it.uri.toString() }) { f ->
            ListRow(f.name, formatSize(f.size), {}, trailing = {
                IconButton(onClick = { files.remove(f) }) { Icon(Icons.Rounded.Close, "Remove ${f.name}") }
            }) { IconTile(Icons.Rounded.InsertDriveFile, paletteFor(f.name), 44.dp) }
        }
        item {
            ListRow(
                if (files.isEmpty()) "Choose files" else "Add more files",
                "Or long-press a video or song in a folder", { pick.launch(arrayOf("*/*")) },
            ) { IconTile(Icons.Rounded.Add, Cinema.Accent, 44.dp, tint = Cinema.OnAccent) }
        }
        item { SectionTitle("Nearby", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)) }
        if (others.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                    LinearProgressIndicator(Modifier.padding(bottom = 10.dp))
                    Text("Looking for phones with Drop It open on this Wi-Fi…", color = Cinema.TextDim)
                }
            }
        }
        items(others, key = { it.address }) { peer ->
            Column {
                ListRow(peer.name, status[peer.address] ?: "Tap to send", { send(peer) }) {
                    IconTile(Icons.Rounded.PhoneAndroid, paletteFor(peer.name))
                }
                progress[peer.address]?.takeIf { it > 0f }?.let { LinearProgressIndicator({ it }, Modifier.padding(start = 67.dp, end = 8.dp)) }
            }
        }
    }

    val offer by session.offer.collectAsState()
    offer?.let { o ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${o.from} wants to send you ${if (o.files.size == 1) "a file" else "${o.files.size} files"}") },
            text = {
                val names = o.files.take(5).joinToString("\n") { it.name } + if (o.files.size > 5) "\n…and ${o.files.size - 5} more" else ""
                Text("$names\n\n${formatSize(o.totalBytes)} · saved in Download/${DropIt.FOLDER}")
            },
            confirmButton = { TextButton(onClick = { session.answer(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { session.answer(false) }) { Text("Decline") } },
            containerColor = Cinema.PanelHigh,
        )
    }
}
