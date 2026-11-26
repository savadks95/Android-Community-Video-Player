package app.tgplayer.ui

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import app.tgplayer.Telegram
import org.drinkless.tdlib.TdApi
import app.tgplayer.data.Storage
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tgplayer.data.ChatItem
import app.tgplayer.data.ChatKind
import app.tgplayer.data.Library
import app.tgplayer.data.MediaLibrary
import app.tgplayer.data.MediaFilter
import app.tgplayer.data.MediaItem
import app.tgplayer.data.Page
import app.tgplayer.data.Ref
import app.tgplayer.data.SavedList
import app.tgplayer.data.Store
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// ─── Home ─────────────────────────────────────────────────────────────────────

/**
 * Telegram home: a search box over every chat's videos; when it is empty, the user's shelves, then one shelf
 * per channel (newest first) built from the [fresh] feed, which pages in more as you scroll down.
 */
@Composable
fun HomeScreen(
    fresh: Pager<String>,
    actions: MediaActions,
    onOpenChat: (ChatItem) -> Unit,
    modifier: Modifier = Modifier,
    onAccount: (() -> Unit)? = null,
) {
    val version by Store.version.collectAsState()
    val continuing by produceState(emptyList<MediaItem>(), version) {
        value = resolve(Store.continueWatching().map { it.ref })
    }
    val later by produceState(emptyList<MediaItem>(), version) { value = resolve(Store.saved(SavedList.WATCH_LATER)) }
    val favourites by produceState(emptyList<MediaItem>(), version) { value = resolve(Store.saved(SavedList.FAVOURITES)) }
    // For each shelf's kind (channel or group) when it opens; a chat past the list's limit opens as a plain chat.
    val chats by produceState(emptyMap<Long, ChatItem>()) {
        value = runCatching { Library.chats().associateBy { it.id } }.getOrDefault(emptyMap())
    }
    var query by rememberSaveable { mutableStateOf("") }
    val search = debounced(query)
    // Search results fill the grid; with no search the grid stays empty and the channel shelves show instead.
    val results = remember(search) {
        Pager<String> { if (search.isEmpty()) Page(emptyList(), null) else Library.search(search, MediaFilter.VIDEOS, it ?: "") }
    }
    val byChat = remember(fresh.items) { fresh.items.groupBy { it.chatId } } // keeps feed order: newest channel first

    MediaGrid(
        results, actions, modifier,
        empty = { if (search.isNotEmpty()) EmptyState("No results", "No video matched “$search”.") },
    ) {
        fullSpan("hero") { HomeHero(onAccount) }
        fullSpan("search") { SearchField(query, { query = it }, "Search videos in all chats", Modifier.padding(bottom = 8.dp)) }
        if (search.isEmpty()) {
            if (continuing.isNotEmpty()) fullSpan("continue") { Shelf("Continue watching", continuing, actions) }
            if (later.isNotEmpty()) fullSpan("later") { Shelf("Watch later", later, actions) }
            if (favourites.isNotEmpty()) fullSpan("favourites") { Shelf("Favourites", favourites, actions) }
            byChat.forEach { (chatId, items) ->
                fullSpan("chat:$chatId") {
                    val title = items.first().chatTitle
                    Shelf(
                        title, items, actions,
                        onOpen = { onOpenChat(chats[chatId] ?: ChatItem(chatId, title, ChatKind.GROUP)) },
                        subtitle = { formatDate(it.date) },
                    )
                }
            }
            // Composed only near the bottom, so the feed pages in more channels as you reach the end.
            fullSpan("channels-more") {
                LaunchedEffect(fresh.items.size) { fresh.loadMore() }
                when {
                    fresh.error != null -> EmptyState("Couldn't load videos", fresh.error!!)
                    fresh.finished && fresh.items.isEmpty() -> EmptyState("No videos yet", "Videos posted in your chats and channels show up here.")
                    !fresh.finished -> Loading()
                }
            }
        }
    }
}

@Composable
private fun HomeHero(onAccount: (() -> Unit)?) {
    BrandHeader("Telegram", "Everything playable in your chats, ready to stream") {
        if (onAccount != null) {
            IconButton(onClick = onAccount) { Icon(Icons.Rounded.AccountCircle, "Account, password lock and log out", Modifier.size(30.dp), tint = Cinema.TextDim) }
        }
    }
}

/** A titled row of cards; with [onOpen] the title row reads "See all" and opens the whole list. */
@Composable
private fun Shelf(
    title: String,
    items: List<MediaItem>,
    actions: MediaActions,
    onOpen: (() -> Unit)? = null,
    subtitle: (MediaItem) -> String = { it.chatTitle },
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        if (onOpen == null) {
            SectionTitle(title)
        } else {
            Row(Modifier.fillMaxWidth().pressRing(onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(title, Modifier.weight(1f))
                Text("See all", style = CaptionStyle.copy(fontSize = 13.sp), color = Cinema.AccentText)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(14.dp), tint = Cinema.AccentText)
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.key }) { item ->
                MediaCard(item, actions.progressOf(item), { actions.play(item) }, { actions.more(item) }, Modifier.width(250.dp), actions.downloadStatusOf(item) ?: subtitle(item))
            }
        }
    }
}

/** Saved refs -> fresh messages from Telegram (dropping deleted ones), in order. */
suspend fun resolve(refs: List<Ref>): List<MediaItem> = coroutineScope {
    refs.map { async { runCatching { Library.message(it.chatId, it.messageId) }.getOrNull() } }.awaitAll().filterNotNull()
}

/** A full-width header row; [key] keeps each row's own state (like a shelf's scroll) when rows above it appear. */
private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) =
    item(key = "header:$key", span = { GridItemSpan(maxLineSpan) }) { content() }

// ─── Chats ────────────────────────────────────────────────────────────────────

@Composable
fun ChatsScreen(onOpen: (ChatItem) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val chats by produceState<List<ChatItem>?>(null, attempt) {
        value = try {
            Library.chats()
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
    }
    var kind by rememberSaveable { mutableStateOf<ChatKind?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = chats.orEmpty().filter { (kind == null || it.kind == kind) && it.title.contains(query.trim(), ignoreCase = true) }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)) {
        item { ScreenHeader("Chats", "Pick a chat to see its videos, files and music", Modifier.padding(horizontal = 4.dp)) }
        item { SearchField(query, { query = it }, "Filter chats", Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) {
                PillTabs(
                    listOf(null, ChatKind.CHANNEL, ChatKind.GROUP, ChatKind.PERSON), kind,
                    label = { when (it) { null -> "All"; ChatKind.CHANNEL -> "Channels"; ChatKind.GROUP -> "Groups"; ChatKind.PERSON -> "People" } },
                    onSelect = { kind = it },
                )
            }
        }
        when {
            chats == null -> item { Loading() }
            error != null && chats!!.isEmpty() -> item { EmptyState("Couldn't load chats", error!!) }
            shown.isEmpty() -> item { EmptyState("No chats", "Nothing matches this filter.") }
            else -> items(shown, key = { it.id }) { ChatRow(it) { onOpen(it) } }
        }
    }
}

// ─── One chat ─────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(chat: ChatItem, onBack: () -> Unit, actions: MediaActions, modifier: Modifier = Modifier) {
    var filter by rememberSaveable(chat.id) { mutableStateOf(MediaFilter.VIDEOS) }
    var query by rememberSaveable(chat.id) { mutableStateOf("") }
    val search = debounced(query)
    val pager = remember(chat.id, filter, search) {
        Pager<Long> { Library.chatMedia(chat.id, filter, it ?: 0L, search) }
    }
    // Open while on screen, as Telegram's apps do: TDLib then gets the chat's new posts live (a channel sends
    // none otherwise) and counts sponsored-post views.
    LaunchedEffect(chat.id) {
        runCatching { Telegram.send(TdApi.OpenChat(chat.id)) }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runCatching { Telegram.send(TdApi.CloseChat(chat.id)) } }
        }
    }
    if (search.isEmpty()) LiveMessages(pager, filter, chat.id)
    MediaGrid(
        pager, actions, modifier,
        empty = { EmptyState("No ${filter.label.lowercase()} here", "Try another tab, or a different search.") },
        // Every card here is from this chat, so show when it was posted and how big it is instead.
        subtitle = { listOf(formatDate(it.date), formatSize(it.sizeBytes)).filter(String::isNotEmpty).joinToString(" · ") },
    ) {
        fullSpan("hero") { ChatHero(chat, onBack) }
        fullSpan("filters") {
            Column(Modifier.padding(bottom = 8.dp)) {
                PillTabs(MediaFilter.entries, filter, { it.label }, { filter = it })
                SearchField(query, { query = it }, "Search in ${chat.title}", Modifier.padding(top = 12.dp))
            }
        }
        // Required by the Telegram API terms for apps that show channel content.
        if (chat.kind == ChatKind.CHANNEL) fullSpan("sponsored") { SponsoredPost(chat.id, Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun ChatHero(chat: ChatItem, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.radialGradient(listOf(Cinema.AccentText.copy(alpha = 0.18f), Color.Transparent), radius = 650f),
                RoundedCornerShape(32.dp),
            )
            .padding(bottom = 16.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Avatar(chat, 76.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(chat.kind), null, Modifier.size(16.dp), tint = Cinema.AccentText)
                    Spacer(Modifier.width(6.dp))
                    Text(chat.kind.label, style = MaterialTheme.typography.labelLarge, color = Cinema.AccentText)
                }
            }
            // Adds the chat's movies and episodes to the Library tab.
            LibraryToggle(MediaLibrary.telegramFolder(chat.id, chat.title))
        }
    }
}

// ─── Search ───────────────────────────────────────────────────────────────────

@Composable
fun SearchScreen(actions: MediaActions, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.VIDEOS) }
    val search = debounced(query)
    val pager = remember(search, filter) {
        if (search.isEmpty()) null else Pager<String> { Library.search(search, filter, it ?: "") }
    }
    Column(modifier) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ScreenHeader("Search", "Every chat and channel at once")
            SearchField(query, { query = it }, "Movie, episode, song…", Modifier.padding(vertical = 8.dp))
            PillTabs(MediaFilter.entries, filter, { it.label }, { filter = it }, Modifier.padding(bottom = 12.dp))
        }
        if (pager == null) {
            EmptyState("Find anything", "Search by title, caption or file name across all your chats.")
        } else {
            MediaGrid(pager, actions, empty = { EmptyState("No results", "Nothing matched “$search”.") })
        }
    }
}

// ─── Library ──────────────────────────────────────────────────────────────────

private enum class LibraryShelf(val label: String) {
    CONTINUE("Continue"), FAVOURITES("Favourites"), LATER("Watch later"), DOWNLOADS("Downloads")
}

@Composable
fun LibraryScreen(actions: MediaActions, onAccount: () -> Unit, modifier: Modifier = Modifier) {
    var shelf by rememberSaveable { mutableStateOf(LibraryShelf.CONTINUE) }
    var showStorage by remember { mutableStateOf(false) }
    val version by Store.version.collectAsState()
    val pager = remember(shelf, version) {
        Pager<Unit> {
            val items = when (shelf) {
                LibraryShelf.CONTINUE -> resolve(Store.continueWatching(100).map { it.ref })
                LibraryShelf.FAVOURITES -> resolve(Store.saved(SavedList.FAVOURITES))
                LibraryShelf.LATER -> resolve(Store.saved(SavedList.WATCH_LATER))
                LibraryShelf.DOWNLOADS -> Library.downloads()
            }
            Page(items, null)
        }
    }
    if (shelf == LibraryShelf.DOWNLOADS) {
        LaunchedEffect(pager) { // live progress while this tab is open
            while (true) {
                delay(2_000)
                pager.refresh()
            }
        }
    }
    MediaGrid(
        pager, actions, modifier,
        empty = {
            when (shelf) {
                LibraryShelf.CONTINUE -> EmptyState("Nothing in progress", "Videos you start show up here so you can pick up where you left off.")
                LibraryShelf.FAVOURITES -> EmptyState("No favourites yet", "Long-press any video and tap Favourite.")
                LibraryShelf.LATER -> EmptyState("Watch later is empty", "Long-press any video and tap Watch later.")
                LibraryShelf.DOWNLOADS -> EmptyState("No downloads", "Long-press any video and tap Download to watch it offline.")
            }
        },
    ) {
        fullSpan("header") {
            ScreenHeader("Library", "Your lists live on this phone") {
                IconButton(onClick = { showStorage = true }) { Icon(Icons.Rounded.Storage, "Storage", tint = Cinema.TextDim) }
                IconButton(onClick = onAccount) { Icon(Icons.Rounded.AccountCircle, "Account, password lock and log out", tint = Cinema.TextDim) }
            }
        }
        fullSpan("tabs") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                PillTabs(LibraryShelf.entries, shelf, { it.label }, { shelf = it })
            }
        }
    }
    if (showStorage) StorageSheet(onDismiss = { showStorage = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableIntStateOf(Storage.limitGb) }
    var reload by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    val usage by produceState<Storage.Usage?>(null, reload) { value = runCatching { Storage.usage() }.getOrNull() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Storage", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            val u = usage
            if (u == null) {
                Text("Measuring…", color = Cinema.TextDim)
            } else {
                StorageRow("Streaming cache", formatSize(u.streamedBytes).ifEmpty { "Empty" }, "Videos and songs you played. Trimmed to the limit below, oldest first.")
                StorageRow("Offline downloads", formatSize(u.offlineBytes).ifEmpty { "None" }, "Kept until you remove them.")
            }
            Spacer(Modifier.height(20.dp))
            Text("Streaming cache limit", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            PillTabs(Storage.limitChoicesGb, limit, { "$it GB" }, {
                limit = it
                Storage.limitGb = it
                scope.launch {
                    runCatching { Storage.trim() }
                    reload++
                }
            })
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Cinema.PanelHigh)
                    .clickable(enabled = !clearing) {
                        clearing = true
                        scope.launch {
                            runCatching { Storage.trim(0) }
                            clearing = false
                            reload++
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (clearing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Clear streaming cache", style = MaterialTheme.typography.labelLarge, color = Cinema.Danger)
            }
        }
    }
}

@Composable
private fun StorageRow(title: String, value: String, note: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
    }
}

// ─── Shared bits ──────────────────────────────────────────────────────────────

@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Cinema.PanelHigh,
            unfocusedContainerColor = Cinema.Panel,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Cinema.AccentText,
        ),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** [value] after the user stops typing for a moment. */
@Composable
private fun debounced(value: String): String {
    var settled by remember { mutableStateOf(value.trim()) }
    LaunchedEffect(value) {
        delay(400)
        settled = value.trim()
    }
    return settled
}

