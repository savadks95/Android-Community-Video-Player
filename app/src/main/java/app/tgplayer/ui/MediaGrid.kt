package app.tgplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tgplayer.Telegram
import app.tgplayer.data.Library
import app.tgplayer.data.MediaFilter
import app.tgplayer.data.MediaItem
import app.tgplayer.data.Page
import kotlinx.coroutines.CancellationException
import org.drinkless.tdlib.TdApi

/**
 * Keeps [pager] live, like Telegram's own apps: messages posted while it's on screen are added on top and deleted
 * ones removed, from TDLib's updates, with no requests to Telegram (older ones still load only on scroll).
 * [chatId] null = every chat. The chat must be open ([TdApi.OpenChat]) for a channel's updates to arrive.
 */
@Composable
fun LiveMessages(pager: Pager<*>, filter: MediaFilter, chatId: Long? = null) {
    LaunchedEffect(pager) {
        Telegram.messageUpdates.collect { update ->
            when (update) {
                is TdApi.UpdateNewMessage -> {
                    val m = update.message
                    // A message still being sent has a temporary id; it arrives again once sent.
                    if ((chatId == null || m.chatId == chatId) && m.sendingState == null && filter.matches(m.content)) {
                        Library.message(m.chatId, m.id)?.let(pager::prepend) // already in TDLib's memory: no request
                    }
                }
                is TdApi.UpdateDeleteMessages ->
                    if ((chatId == null || update.chatId == chatId) && update.isPermanent) {
                        pager.remove(update.messageIds.mapTo(HashSet()) { "${update.chatId}/$it" })
                    }
            }
        }
    }
}

/** Cursor-based paging over Telegram results. [fetch] gets null for the first page. */
@Stable
class Pager<C : Any>(private val fetch: suspend (C?) -> Page<C>) {
    var items by mutableStateOf(emptyList<MediaItem>()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var finished by mutableStateOf(false); private set
    private var cursor: C? = null

    suspend fun loadMore() {
        if (loading || finished) return
        loading = true
        error = null
        try {
            val page = fetch(cursor)
            val seen = items.mapTo(HashSet()) { it.key }
            items = items + page.items.filter { seen.add(it.key) }
            cursor = page.next
            finished = page.next == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load"
        } finally {
            loading = false
        }
    }

    /** A newly posted item goes on top (the list is newest first); one already listed is skipped. */
    fun prepend(item: MediaItem) {
        if (items.none { it.key == item.key }) items = listOf(item) + items
    }

    fun remove(keys: Set<String>) {
        items = items.filterNot { it.key in keys }
    }

    /** Re-fetch the first page and swap it in without blanking the grid (for live download progress). */
    suspend fun refresh() {
        if (loading) return
        try {
            val page = fetch(null)
            items = page.items
            cursor = page.next
            finished = page.next == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep showing the last good list
        }
    }
}

/**
 * Adaptive grid of [MediaCard]s that loads the next page near the end.
 * [header] items go above the grid at full width.
 */
@Composable
fun MediaGrid(
    pager: Pager<*>,
    actions: MediaActions,
    modifier: Modifier = Modifier,
    empty: @Composable () -> Unit = { EmptyState("Nothing here", "No playable media found.") },
    subtitle: (MediaItem) -> String = { it.chatTitle },
    header: LazyGridScope.() -> Unit = {},
) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(pager) { pager.loadMore() }
    LaunchedEffect(nearEnd, pager.items.size) { if (nearEnd) pager.loadMore() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        header()
        items(pager.items, key = { it.key }) { item ->
            MediaCard(
                item,
                actions.progressOf(item),
                onClick = { actions.play(item) },
                onLongClick = { actions.more(item) },
                subtitle = actions.downloadStatusOf(item) ?: subtitle(item),
            )
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    pager.loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    pager.error != null -> Text(pager.error!!, color = MaterialTheme.colorScheme.error)
                    pager.finished && pager.items.isEmpty() -> empty()
                }
            }
        }
    }
}

/**
 * What a media tile can do; provided by the app shell. @Stable: when the shell hands the same instance
 * (no active download churning it), Compose may skip tiles whose item is unchanged (OPT-1).
 */
@Stable
interface MediaActions {
    fun play(item: MediaItem)
    fun more(item: MediaItem)
    fun progressOf(item: MediaItem): Float?
    /** "Downloading 42% · 1.2 GB" / "Downloaded · 1.2 GB" for offline downloads, shown in place of the subtitle; else null. */
    fun downloadStatusOf(item: MediaItem): String?
}
