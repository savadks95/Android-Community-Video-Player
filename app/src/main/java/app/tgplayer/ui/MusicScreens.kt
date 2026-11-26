package app.tgplayer.ui

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.tgplayer.data.Store
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tgplayer.data.MediaKind
import app.tgplayer.data.Settings
import app.tgplayer.data.playableKind
import app.tgplayer.local.LocalItem
import app.tgplayer.local.LocalMedia
import app.tgplayer.player.PlaybackService
import app.tgplayer.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Clean-room music library UI. Design only follows common music players (Songs / Albums / Artists, album and
// artist pages, a mini player); no code is taken from any other app. The data comes from MediaStore.

private enum class MusicView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists") }

private class AlbumGroup(val key: String, val name: String, val artist: String, val songs: List<LocalItem>)
private class ArtistGroup(val name: String, val songs: List<LocalItem>)

private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"

private fun List<LocalItem>.albums(): List<AlbumGroup> =
    groupBy { if (it.albumId > 0) "id:${it.albumId}" else "${it.album}|${it.artist}" }
        .map { (key, songs) ->
            val ordered = songs.sortedWith(compareBy({ it.track.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }, { it.title.lowercase() }))
            val artist = songs.mapNotNull { it.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: UNKNOWN_ARTIST
            AlbumGroup(key, songs.first().album ?: UNKNOWN_ALBUM, artist, ordered)
        }
        .sortedBy { it.name.lowercase() }

private fun List<LocalItem>.artists(): List<ArtistGroup> =
    groupBy { it.artist ?: UNKNOWN_ARTIST }
        .map { (name, songs) -> ArtistGroup(name, songs) }
        .sortedBy { it.name.lowercase() }

private fun Context.playSongs(songs: List<LocalItem>, index: Int) =
    startActivity(PlayerActivity.intent(this, songs.map { it.uri }, songs.map { it.title }, index))

/** The same item the player builds for a local song (media id = file URI, which history keys on). */
private fun LocalItem.toMediaItem(): MediaItem =
    MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/** A song's ⋮ menu target; [playlistId]/[index] are set on a playlist page, where it can also be moved or removed. */
private data class SongTarget(val song: LocalItem, val playlistId: Long? = null, val index: Int = 0, val count: Int = 0)

// ─── Music tab ────────────────────────────────────────────────────────────────

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access = rememberFileAccess()
    val resumes by AppEvents.resumed.collectAsState()
    val storeVersion by Store.version.collectAsState()
    var hideChat by remember { mutableStateOf(Settings.hideChatAudio) }
    val songs by produceState<List<LocalItem>?>(null, resumes, access, hideChat) {
        value = if (access) runCatching { LocalMedia.songs(context, hideChat) }.getOrDefault(emptyList()) else emptyList()
    }
    val playlists by produceState(emptyList<Store.Playlist>(), storeVersion) { value = Store.playlists() }
    val controller = rememberMusicController()

    var view by rememberSaveable { mutableStateOf(MusicView.SONGS) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlaylist by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf<SongTarget?>(null) }
    var pickPlaylistFor by remember { mutableStateOf<List<LocalItem>?>(null) }
    // (playlist being renamed, or null for a new one; songs to put in a new one)
    var naming by remember { mutableStateOf<Pair<Store.Playlist?, List<LocalItem>>?>(null) }
    var deleting by remember { mutableStateOf<Store.Playlist?>(null) }

    val all = songs.orEmpty()
    val byPath = remember(songs) { all.associateBy { it.path } }
    val albums = remember(songs) { all.albums() }
    val artists = remember(songs) { all.artists() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    /** Play next / add to the end of what's playing; with nothing playing, just play them. */
    fun enqueue(list: List<LocalItem>, next: Boolean) {
        if (list.isEmpty()) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) return context.playSongs(list, 0)
        val items = list.map { it.toMediaItem() }
        if (next) c.addMediaItems(c.currentMediaItemIndex + 1, items) else c.addMediaItems(items)
        toast(if (next) "Plays next" else "Added to queue")
    }

    fun addToPlaylist(p: Store.Playlist, list: List<LocalItem>) = scope.launch {
        Store.addToPlaylist(p.id, list.map { it.path })
        toast("Added to ${p.name}")
    }
    val onMenu: (SongTarget) -> Unit = { menu = it }

    Box(modifier) {
        val album = albums.firstOrNull { it.key == openAlbum }
        val artist = artists.firstOrNull { it.name == openArtist }
        val playlist = playlists.firstOrNull { it.id == openPlaylist }
        when {
            album != null -> {
                BackHandler { openAlbum = null }
                SongsPage(
                    album.name, "${album.artist} · ${plural(album.songs.size, "song")}", album.songs, controller, { openAlbum = null },
                    onQueueAll = { enqueue(album.songs, next = false) }, onPlaylistAll = { pickPlaylistFor = album.songs }, onMenu = onMenu,
                )
            }
            artist != null -> {
                BackHandler { openArtist = null }
                val list = artist.songs.sortedBy { it.title.lowercase() }
                SongsPage(
                    artist.name, plural(list.size, "song"), list, controller, { openArtist = null },
                    onQueueAll = { enqueue(list, next = false) }, onPlaylistAll = { pickPlaylistFor = list }, onMenu = onMenu,
                )
            }
            playlist != null -> {
                BackHandler { openPlaylist = null }
                // Songs deleted from the phone since are skipped (they come back if the file returns).
                val list = playlist.paths.mapNotNull(byPath::get)
                SongsPage(
                    playlist.name, plural(list.size, "song"), list, controller, { openPlaylist = null },
                    onQueueAll = { enqueue(list, next = false) }, onPlaylistAll = null, onMenu = onMenu,
                    playlistId = playlist.id,
                    headerActions = {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                            DropdownMenu(open, { open = false }) {
                                DropdownMenuItem({ Text("Rename") }, { open = false; naming = playlist to emptyList() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                DropdownMenuItem({ Text("Delete playlist") }, { open = false; deleting = playlist }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                            }
                        }
                    },
                    empty = if (list.isEmpty()) "Add songs with the ⋮ button beside any song." else null,
                )
            }
            else -> LibraryGrid(
                access, songs, all, albums, artists, playlists, controller,
                view, { view = it }, recent, { recent = !recent }, query, { query = it },
                hideChat, { hideChat = !hideChat; Settings.hideChatAudio = hideChat },
                onAlbum = { openAlbum = it }, onArtist = { openArtist = it }, onPlaylist = { openPlaylist = it },
                onNewPlaylist = { naming = null to emptyList() }, onMenu = onMenu, byPath = byPath,
            )
        }
    }

    menu?.let { t ->
        ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = Cinema.Panel) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SongArt(t.song, Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(t.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        t.song.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1) }
                    }
                }
                MenuLine(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { menu = null; enqueue(listOf(t.song), next = true) }
                MenuLine(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { menu = null; enqueue(listOf(t.song), next = false) }
                MenuLine(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist…") { menu = null; pickPlaylistFor = listOf(t.song) }
                t.playlistId?.let { id ->
                    if (t.index > 0) MenuLine(Icons.Rounded.ArrowUpward, "Move up") { menu = null; scope.launch { Store.movePlaylistSong(id, t.index, t.index - 1) } }
                    if (t.index < t.count - 1) MenuLine(Icons.Rounded.ArrowDownward, "Move down") { menu = null; scope.launch { Store.movePlaylistSong(id, t.index, t.index + 1) } }
                    MenuLine(Icons.Rounded.RemoveCircleOutline, "Remove from playlist") { menu = null; scope.launch { Store.removeFromPlaylist(id, t.song.path) } }
                }
            }
        }
    }

    pickPlaylistFor?.let { list ->
        ModalBottomSheet(onDismissRequest = { pickPlaylistFor = null }, containerColor = Cinema.Panel) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Add ${plural(list.size, "song")} to", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                MenuLine(Icons.Rounded.Add, "New playlist") { pickPlaylistFor = null; naming = null to list }
                playlists.forEach { p ->
                    MenuLine(Icons.AutoMirrored.Rounded.QueueMusic, "${p.name}  ·  ${plural(p.paths.size, "song")}") {
                        pickPlaylistFor = null
                        addToPlaylist(p, list)
                    }
                }
            }
        }
    }

    naming?.let { (renaming, list) ->
        var name by remember(renaming) { mutableStateOf(renaming?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (renaming == null) "New playlist" else "Rename playlist") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, placeholder = { Text("Playlist name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = null
                    val clean = name.trim()
                    scope.launch {
                        if (renaming != null) Store.renamePlaylist(renaming.id, clean)
                        else {
                            val id = Store.createPlaylist(clean)
                            if (list.isNotEmpty()) {
                                Store.addToPlaylist(id, list.map { it.path })
                                toast("Added to $clean")
                            }
                        }
                    }
                }) { Text(if (renaming == null) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The playlist goes away; the songs stay on your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    openPlaylist = null
                    scope.launch { Store.deletePlaylist(p.id) }
                }) { Text("Delete", color = Cinema.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** The tab's main list: header, now-playing card, view chips, search and the chosen view. */
@Composable
private fun LibraryGrid(
    access: Boolean, songs: List<LocalItem>?, all: List<LocalItem>, albums: List<AlbumGroup>, artists: List<ArtistGroup>,
    playlists: List<Store.Playlist>, controller: MediaController?,
    view: MusicView, onView: (MusicView) -> Unit, recent: Boolean, onRecent: () -> Unit, query: String, onQuery: (String) -> Unit,
    hideChat: Boolean, onHideChat: () -> Unit,
    onAlbum: (String) -> Unit, onArtist: (String) -> Unit, onPlaylist: (Long) -> Unit, onNewPlaylist: () -> Unit,
    onMenu: (SongTarget) -> Unit, byPath: Map<String, LocalItem>,
) {
    val context = LocalContext.current
    val q = query.trim()
    fun LocalItem.matches() = title.contains(q, true) || artist?.contains(q, true) == true || album?.contains(q, true) == true
    val shownSongs = all.filter { q.isEmpty() || it.matches() }.let { if (recent) it.sortedByDescending { s -> s.modified } else it }
    val shownAlbums = albums.filter { q.isEmpty() || it.name.contains(q, true) || it.artist.contains(q, true) }
    val shownArtists = artists.filter { q.isEmpty() || it.name.contains(q, true) }
    val shownPlaylists = playlists.filter { q.isEmpty() || it.name.contains(q, true) }

    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        full { BrandHeader("Music", if (songs == null) "Looking for music…" else plural(all.size, "song") + " on this phone") }
        full { MiniPlayer(controller, Modifier.padding(horizontal = 8.dp)) }
        if (!access) {
            full { AccessGate() }
            return@LazyVerticalGrid
        }
        full {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicView.entries.forEach { MusicChip(it.label, view == it) { onView(it) } }
            }
        }
        full {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, onQuery, "Song, album, artist or playlist", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Cinema.Accent)
                        .clickable { if (shownSongs.isNotEmpty()) context.playSongs(shownSongs.shuffled(), 0) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Shuffle, "Shuffle all", tint = Cinema.OnAccent) }
            }
        }
        if (view == MusicView.SONGS || view == MusicView.PLAYLISTS) full {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view == MusicView.SONGS) {
                    MusicChip(if (recent) "Recently added" else "A–Z", false, onRecent)
                    MusicChip(if (hideChat) "Chat audio hidden" else "Chat audio shown", false, onHideChat)
                } else MusicChip("+ New playlist", false, onNewPlaylist)
            }
        }
        when {
            songs == null -> full { LoadingRow() }
            view == MusicView.PLAYLISTS -> {
                if (shownPlaylists.isEmpty()) full {
                    if (q.isEmpty()) EmptyState("No playlists yet", "Tap “New playlist”, or add songs from any song's ⋮ menu.")
                    else EmptyState("No playlists", "Nothing matches “$q”.")
                }
                itemsIndexed(shownPlaylists, key = { _, p -> "pl:${p.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { _, p ->
                    val first = p.paths.firstNotNullOfOrNull(byPath::get)
                    ListRow(
                        p.name, plural(p.paths.count { it in byPath }, "song"), { onPlaylist(p.id) },
                        leading = {
                            if (first != null) SongArt(first, Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
                            else Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(paletteFor(p.name)), contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = Color.White.copy(alpha = 0.7f))
                            }
                        },
                    )
                }
            }
            all.isEmpty() -> full { EmptyState("No music found", "Songs on this phone appear here. Voice notes and ringtones are left out.") }
            view == MusicView.SONGS -> {
                if (shownSongs.isEmpty()) full { EmptyState("No songs", "Nothing matches “$q”.") }
                itemsIndexed(shownSongs, key = { _, s -> s.path }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, s ->
                    SongRow(s, { onMenu(SongTarget(s)) }) { context.playSongs(shownSongs, i) }
                }
            }
            view == MusicView.ALBUMS -> {
                if (shownAlbums.isEmpty()) full { EmptyState("No albums", "Nothing matches “$q”.") }
                itemsIndexed(shownAlbums, key = { _, a -> a.key }) { _, a -> AlbumCard(a) { onAlbum(a.key) } }
            }
            else -> {
                if (shownArtists.isEmpty()) full { EmptyState("No artists", "Nothing matches “$q”.") }
                itemsIndexed(shownArtists, key = { _, a -> a.name }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { _, a ->
                    ListRow(
                        a.name, plural(a.songs.size, "song"), { onArtist(a.name) },
                        leading = {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(paletteFor(a.name)), contentAlignment = Alignment.Center) {
                                Text(a.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color.White)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** A song: tap plays, long-press or ⋮ opens its menu. */
@Composable
private fun SongRow(song: LocalItem, onMenu: () -> Unit, onClick: () -> Unit) {
    val line = listOfNotNull(
        song.artist, song.album,
        song.durationMs.takeIf { it > 0 }?.let { formatDuration((it / 1000).toInt()) },
    ).joinToString(" · ")
    ListRow(
        song.title, line, onClick, onLongClick = onMenu,
        trailing = { IconButton(onClick = onMenu) { Icon(Icons.Rounded.MoreVert, "Song options", tint = Cinema.TextDim) } },
        leading = { SongArt(song, Modifier.size(52.dp).clip(MaterialTheme.shapes.small)) },
    )
}

@Composable
private fun MenuLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cinema.TextDim)
        Spacer(Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        SongArt(album.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(6.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MusicChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Cinema.AccentFill else Cinema.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Cinema.OnAccent else Cinema.Text,
    )
}

/**
 * An album's, artist's or playlist's songs, with Play, Shuffle, Queue and (not for a playlist) Add to playlist.
 * On a playlist page ([playlistId]) each song's menu can also move it or take it out.
 */
@Composable
private fun SongsPage(
    title: String, subtitle: String, songs: List<LocalItem>, controller: MediaController?, onBack: () -> Unit,
    onQueueAll: () -> Unit, onPlaylistAll: (() -> Unit)?, onMenu: (SongTarget) -> Unit,
    playlistId: Long? = null, headerActions: @Composable () -> Unit = {}, empty: String? = null,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)) {
        item { BackHeader(title, subtitle, onBack, headerActions) }
        item { MiniPlayer(controller, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        if (songs.isNotEmpty()) item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PageButton("Play", Icons.Rounded.PlayArrow, primary = true) { context.playSongs(songs, 0) }
                PageButton("Shuffle", Icons.Rounded.Shuffle, primary = false) { context.playSongs(songs.shuffled(), 0) }
                PageButton("Queue", Icons.AutoMirrored.Rounded.QueueMusic, primary = false, onClick = onQueueAll)
                onPlaylistAll?.let { PageButton("Playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, primary = false, onClick = it) }
            }
        }
        empty?.let { item { EmptyState("Empty playlist", it) } }
        itemsIndexed(songs, key = { _, s -> s.path }) { i, s ->
            SongRow(s, { onMenu(SongTarget(s, playlistId, i, songs.size)) }) { context.playSongs(songs, i) }
        }
    }
}

@Composable
private fun PageButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (primary) Cinema.AccentFill else Cinema.Panel)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (primary) Cinema.OnAccent else Cinema.Text
        Icon(icon, null, Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

// ─── Artwork ──────────────────────────────────────────────────────────────────

private val artCache = LruCache<Long, ImageBitmap>(150)

/** Album art from MediaStore's album thumbnail (Android 10+), else the picture embedded in the file. */
@Composable
private fun SongArt(song: LocalItem, modifier: Modifier) {
    val context = LocalContext.current
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(paletteFor(song.album ?: song.title)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.6f))
        }
    }
    if (Build.VERSION.SDK_INT < 29 || song.albumId <= 0) {
        LocalThumb(song.path, MediaKind.AUDIO, modifier, placeholder)
        return
    }
    var art by remember(song.albumId) { mutableStateOf(artCache.get(song.albumId)) }
    var failed by remember(song.albumId) { mutableStateOf(false) }
    LaunchedEffect(song.albumId) {
        if (art == null) {
            art = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, song.albumId)
                    context.contentResolver.loadThumbnail(uri, Size(300, 300), null).asImageBitmap()
                }.getOrNull()
            }
            art?.let { artCache.put(song.albumId, it) } ?: run { failed = true }
        }
    }
    Box(modifier) {
        val a = art
        when {
            a != null -> Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            failed -> LocalThumb(song.path, MediaKind.AUDIO, Modifier.fillMaxSize(), placeholder)
            else -> placeholder()
        }
    }
}

// ─── Mini player ──────────────────────────────────────────────────────────────

/**
 * A now-playing card at the top of the Music tab: tap to open the full player, or pause and skip from here.
 * It connects to the playback service only when that is already running, so opening the app never starts it.
 */
@Composable
private fun MiniPlayer(controller: MediaController?, modifier: Modifier = Modifier) {
    controller?.let { MiniBar(it, modifier) }
}

/** A controller for the playback service, or null while it isn't running (connecting would start it). */
@Composable
private fun rememberMusicController(): MediaController? {
    val context = LocalContext.current
    val resumes by AppEvents.resumed.collectAsState()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(resumes) {
        if (!PlaybackService.running) return@DisposableEffect onDispose {}
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

@Composable
private fun MiniBar(player: Player, modifier: Modifier) {
    val context = LocalContext.current
    var version by remember(player) { mutableIntStateOf(0) }
    var positionMs by remember(player) { mutableLongStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                version++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(500)
        }
    }
    version // re-read the player's state after every change
    val item = player.currentMediaItem ?: return
    val tracks = player.currentTracks
    val isAudio = if (tracks.isEmpty) playableKind(null, item.localConfiguration?.uri?.lastPathSegment) == MediaKind.AUDIO
    else !tracks.containsType(C.TRACK_TYPE_VIDEO)
    if (!isAudio || player.playbackState == Player.STATE_IDLE && !player.playWhenReady) return

    val title = item.mediaMetadata.title?.toString() ?: "Playing"
    val path = item.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path
    val duration = player.duration.takeIf { it > 0 } ?: 0L
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cinema.PanelHigh)
            .clickable { context.startActivity(PlayerActivity.openCurrent(context)) },
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val artModifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            if (path != null) LocalThumb(path, MediaKind.AUDIO, artModifier) { MiniArt(title, artModifier) } else MiniArt(title, artModifier)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.mediaMetadata.artist?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = player.hasPreviousMediaItem()) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", Modifier.size(30.dp))
            }
            IconButton(onClick = { player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Cinema.Line)) {
            if (duration > 0) Box(Modifier.fillMaxWidth((positionMs.toFloat() / duration).coerceIn(0f, 1f)).height(2.dp).background(Cinema.AccentFill))
        }
    }
}

@Composable
private fun MiniArt(title: String, modifier: Modifier) {
    Box(modifier.background(paletteFor(title)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.6f))
    }
}
