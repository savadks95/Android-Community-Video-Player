package app.tgplayer.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tgplayer.data.Jellyfin
import app.tgplayer.data.JellyfinItem
import app.tgplayer.data.MediaKind
import app.tgplayer.data.Store
import app.tgplayer.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * Plays [items] from [index], queueing the rest (a season keeps going). The server's resume point is
 * written to local history first, so playback picks up where any Jellyfin app left it.
 */
private suspend fun Context.playJellyfin(items: List<JellyfinItem>, index: Int) {
    items[index].takeIf { it.positionMs > 0 && !it.played }?.let { Store.saveHistory(it.uri, it.label, it.positionMs, it.runTimeMs) }
    startActivity(PlayerActivity.intent(this, items.map { it.uri }, items.map { it.label }, index))
}

/** The signed-in Jellyfin server: Continue watching and Next up from the server, then its libraries and folders. */
@Composable
fun JellyfinScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val server = Jellyfin.server ?: return onExit()
    val scope = rememberCoroutineScope()
    // Folder path as "id|name" entries; empty = the server's home.
    var path by rememberSaveable { mutableStateOf(listOf<String>()) }
    val up: () -> Unit = { if (path.isEmpty()) onExit() else path = path.dropLast(1) }
    BackHandler(onBack = up)
    val open: (JellyfinItem) -> Unit = { path = path + "${it.id}|${it.name}" }

    val folder = path.lastOrNull()
    if (folder == null) {
        JellyfinHome(server.name, "Signed in as ${server.userName}", up, open, modifier, onSignOut = { scope.launch { Jellyfin.signOut(); onExit() } })
    } else {
        JellyfinFolder(folder.substringBefore('|'), folder.substringAfter('|'), up, open, modifier)
    }
}

@Composable
private fun JellyfinHome(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpen: (JellyfinItem) -> Unit,
    modifier: Modifier,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home by produceState<Result<Triple<List<JellyfinItem>, List<JellyfinItem>, List<JellyfinItem>>>?>(null) {
        value = runCatching { Triple(Jellyfin.resume(), Jellyfin.nextUp(), Jellyfin.views()) }
    }
    LazyColumn(modifier, contentPadding = bottomPad) {
        item {
            BackHeader(title, subtitle, onBack) {
                IconButton(onClick = onSignOut) { Icon(Icons.AutoMirrored.Rounded.Logout, "Sign out of Jellyfin") }
            }
        }
        val r = home
        val data = r?.getOrNull()
        when {
            r == null -> item { LoadingRow() }
            data == null -> item { EmptyState("Couldn't reach the server", r.exceptionOrNull()?.message ?: "Check that it's on and on this network.") }
            else -> {
                val (resume, nextUp, views) = data
                if (resume.isNotEmpty()) item {
                    Shelf("Continue watching", resume) { item ->
                        JellyfinPoster(item) { scope.launch { context.playJellyfin(listOf(item), 0) } }
                    }
                }
                if (nextUp.isNotEmpty()) item {
                    Shelf("Next up", nextUp) { item ->
                        JellyfinPoster(item) { scope.launch { context.playJellyfin(listOf(item), 0) } }
                    }
                }
                item { SectionTitle("Libraries", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)) }
                items(views, key = { it.id }) { v ->
                    ListRow(v.name, null, { onOpen(v) }) { IconTile(Icons.Rounded.VideoLibrary, paletteFor(v.name)) }
                }
            }
        }
    }
}

/** A library or folder: posters in a grid; a season (all episodes) as a list with stills. */
@Composable
private fun JellyfinFolder(id: String, name: String, onBack: () -> Unit, onOpen: (JellyfinItem) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<JellyfinItem>>?>(null, id, reload) { value = null; value = runCatching { Jellyfin.children(id) } }
    val list = result?.getOrNull()
    val playable = list.orEmpty().filter { it.playable }
    val episodes = list != null && list.isNotEmpty() && list.all { it.type == "Episode" }

    if (episodes) {
        LazyColumn(modifier, contentPadding = bottomPad) {
            item { BackHeader(name, "${list!!.size} episodes", onBack) }
            itemsIndexed(list!!, key = { _, e -> e.id }) { i, e ->
                ListRow(
                    "${e.episode ?: (i + 1)}. ${e.name}", e.overview.ifBlank { null }, { scope.launch { context.playJellyfin(list, i) } },
                    trailing = { if (e.played) Icon(Icons.Rounded.CheckCircle, "Watched", tint = Cinema.AccentText) },
                    leading = {
                        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Cinema.PanelHigh)) {
                            WebImage(Jellyfin.imageUrl(e, 270), Modifier.fillMaxSize()) { GradientPlaceholder(e.name, MediaKind.VIDEO) }
                            ProgressBar(e.fraction.takeIf { !e.played }, Modifier.align(Alignment.BottomStart))
                        }
                    },
                )
            }
        }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(118.dp), modifier, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 120.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { BackHeader(name, list?.let { "${it.size} items" }.orEmpty(), onBack) }
        val r = result
        when {
            r == null -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
            list == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    EmptyState("Couldn't open this", r.exceptionOrNull()?.message ?: "The server didn't answer.")
                    PrimaryButton("Try again") { reload++ }
                }
            }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Empty", "Nothing in here yet.") }
            else -> items(list, key = { it.id }) { item ->
                Box(Modifier.padding(bottom = 12.dp)) {
                    JellyfinPoster(item) {
                        if (item.playable) scope.launch { context.playJellyfin(playable, playable.indexOf(item)) } else onOpen(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun JellyfinPoster(item: JellyfinItem, onClick: () -> Unit) {
    PosterCard(
        if (item.type == "Episode") item.seriesName ?: item.name else item.name,
        if (item.type == "Episode") "S${item.season ?: 0}E${item.episode ?: 0} · ${item.name}" else item.year?.toString(),
        null, null, item.fraction.takeIf { !item.played },
        badge = if (item.played) "✓" else null,
        posterUrl = Jellyfin.imageUrl(item),
        onClick = onClick,
    )
}

/** Signs in to a Jellyfin server; only the access token it returns is kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinSignInSheet(onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun signIn() {
        if (address.isBlank() || user.isBlank()) {
            error = "Enter the server address and your user name"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                Jellyfin.signIn(address, user.trim(), password)
                onSignedIn()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't sign in"
            } finally {
                busy = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Text("Jellyfin server", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your server's libraries, artwork and watched status. Progress is sent back, so other Jellyfin apps stay in step.",
                style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
            )
            Spacer(Modifier.height(14.dp))
            DriveField(address, { address = it; error = null }, "Address (192.168.1.20:8096)", KeyboardType.Uri)
            DriveField(user, { user = it; error = null }, "User name")
            DriveField(password, { password = it; error = null }, "Password", KeyboardType.Password, ImeAction.Go, onGo = ::signIn)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Spacer(Modifier.height(6.dp))
            if (busy) LoadingRow() else PrimaryButton("Sign in", Modifier.fillMaxWidth(), onClick = ::signIn)
        }
    }
}
