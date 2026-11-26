package app.tgplayer.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgplayer.data.LibraryEpisode
import app.tgplayer.data.LibraryMovie
import app.tgplayer.data.LibraryShow
import app.tgplayer.data.MediaKind
import app.tgplayer.data.MediaLibrary
import app.tgplayer.data.Meta
import app.tgplayer.data.Settings
import app.tgplayer.data.Store
import app.tgplayer.data.Tmdb
import app.tgplayer.local.NetworkDrive
import app.tgplayer.data.nextUp
import app.tgplayer.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.playAll(uris: List<String>, titles: List<String>, index: Int) =
    startActivity(PlayerActivity.intent(this, uris, titles, index))

/** Plays [episodes] from [index] on, so the season keeps going. */
private fun Context.playEpisodes(show: LibraryShow, episodes: List<LibraryEpisode>, index: Int) =
    playAll(episodes.map { it.uri }, episodes.map { "${show.title} · ${it.code} · ${it.title}" }, index)

// ─── Library home ─────────────────────────────────────────────────────────────

/** Movies and shows from the Library folders, in shelves by kind and genre, like Jellyfin's home. */
@Composable
fun LibraryTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MediaLibrary.load() }
    val catalog by MediaLibrary.catalog.collectAsState()
    val scanning by MediaLibrary.scanning.collectAsState()
    val unreachable by MediaLibrary.unreachable.collectAsState()
    val resumes by AppEvents.resumed.collectAsState()
    val storeVersion by Store.version.collectAsState() // bumps on Mark watched/unwatched
    val history by produceState(emptyList<Store.HistoryEntry>(), resumes, storeVersion) { value = Store.recentHistory(500) }
    val progress = remember(history) { history.associate { it.uri to it.fraction } }
    val watched = remember(history) { history.filter { it.watched }.map { it.uri }.toSet() }
    val folders = remember(scanning) { Settings.libraryFolders }
    var openMovie by rememberSaveable { mutableStateOf<String?>(null) }
    var openShow by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val c = catalog
    c?.movies?.firstOrNull { it.uri == openMovie }?.let { movie ->
        BackHandler { openMovie = null }
        MovieDetail(movie, progress[movie.uri], movie.uri in watched, onBack = { openMovie = null }, modifier)
        return
    }
    c?.shows?.firstOrNull { it.key == openShow }?.let { show ->
        BackHandler { openShow = null }
        ShowDetail(show, progress, watched, onBack = { openShow = null }, modifier)
        return
    }
    val onItem: (Any) -> Unit = { item ->
        when (item) {
            is LibraryMovie -> openMovie = item.uri
            is LibraryShow -> openShow = item.key
        }
    }

    LazyColumn(modifier, contentPadding = bottomPad) {
        item {
            val counts = c?.let { "${it.movies.size} movies · ${it.shows.size} shows" } ?: "Movies and shows from your folders"
            BrandHeader("Library", if (scanning) "Scanning… $counts" else counts) {
                if (scanning) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                else if (folders.isNotEmpty()) IconButton(onClick = { MediaLibrary.scan(full = true) }) { Icon(Icons.Rounded.Refresh, "Scan again") }
            }
        }
        if (folders.isEmpty()) {
            item {
                EmptyState(
                    "Your library is empty",
                    "In Browse, open a folder of movies or shows, on this phone or a network drive, and tap the add-to-library button at the top. " +
                        "TG Player sorts what's inside into movies and shows.",
                )
            }
            return@LazyColumn
        }
        if (!Tmdb.available) item { Hint("Posters and details need a TMDb API key (tmdb.apiKey in local.properties). Titles still come from file names.") }
        if (unreachable.isNotEmpty()) item {
            val names = unreachable.joinToString { Uri.parse(it).let { u -> if (u.scheme == MediaLibrary.TG_CHAT) "Telegram: ${u.lastPathSegment}" else Uri.decode(it) } }
            Hint("Couldn't reach $names; showing what was found there last time.")
        }

        if (c != null && (c.movies.isNotEmpty() || c.shows.isNotEmpty())) item {
            SearchField(query, { query = it }, "Search movies and shows", Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        if (c != null && query.isNotBlank()) {
            val q = query.trim()
            fun hit(vararg names: String?) = names.any { it?.contains(q, ignoreCase = true) == true }
            val found: List<Any> = c.movies.filter { hit(it.title, it.name) } +
                c.shows.filter { s -> hit(s.title, s.name) || s.episodes.any { hit(it.meta?.name) } }
            if (found.isEmpty()) item { EmptyState("Nothing found", "No movie or show matches “$q”.") }
            else item { Shelf("Results", found) { ItemCard(it, progress, watched, onItem) } }
        } else if (c != null) {
            val continuing = history.filter { it.positionMs > 5_000 && it.fraction < 0.95f }.mapNotNull { h ->
                c.movies.firstOrNull { it.uri == h.uri }?.let { Triple(h, it as Any, null as LibraryEpisode?) }
                    ?: c.shows.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.uri == h.uri }?.let { Triple(h, s as Any, it) } }
            }.take(15)
            if (continuing.isNotEmpty()) item {
                Shelf("Continue watching", continuing) { (h, item, episode) ->
                    when (item) {
                        is LibraryMovie -> PosterCard(item.title, item.year?.toString(), item.meta?.poster, item.uri, h.fraction) {
                            context.playAll(listOf(item.uri), listOf(item.title), 0)
                        }
                        is LibraryShow -> PosterCard(item.title, episode?.code, item.meta?.poster, episode?.uri, h.fraction) {
                            val season = item.episodes.filter { it.season == episode?.season }
                            context.playEpisodes(item, season, season.indexOf(episode).coerceAtLeast(0))
                        }
                    }
                }
            }
            val upNext = nextUp(c, history).take(15)
            if (upNext.isNotEmpty()) item {
                Shelf("Next up", upNext) { (show, episode) ->
                    PosterCard(show.title, "${episode.code} · ${episode.title}", show.meta?.poster, episode.uri, null) {
                        val season = show.episodes.filter { it.season == episode.season }
                        context.playEpisodes(show, season, season.indexOf(episode))
                    }
                }
            }
            if (c.recentlyAdded.isNotEmpty()) item { Shelf("Recently added", c.recentlyAdded) { ItemCard(it, progress, watched, onItem) } }
            if (c.movies.isNotEmpty()) item { Shelf("Movies", c.movies) { ItemCard(it, progress, watched, onItem) } }
            if (c.shows.isNotEmpty()) item { Shelf("Shows", c.shows) { ItemCard(it, progress, watched, onItem) } }
            c.genres.forEach { (genre, list) -> item(key = "genre:$genre") { Shelf(genre, list) { ItemCard(it, progress, watched, onItem) } } }
            if (c.movies.isEmpty() && c.shows.isEmpty() && !scanning) item { EmptyState("No videos found", "The Library folders have no video files yet.") }
        }

        item { SectionTitle("Folders", Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp)) }
        items(folders, key = { it }) { f ->
            val uri = Uri.parse(f)
            val telegram = uri.scheme == MediaLibrary.TG_CHAT
            ListRow(
                uri.lastPathSegment ?: f, if (telegram) "Telegram" else Uri.decode(f), {},
                trailing = { IconButton(onClick = { MediaLibrary.removeFolders(listOf(f)) }) { Icon(Icons.Rounded.Close, "Remove from Library", tint = Cinema.TextDim) } },
            ) {
                when {
                    telegram -> IconTile(Icons.AutoMirrored.Rounded.Send, Brush.linearGradient(listOf(Color(0xFF2AABEE), Color(0xFF229ED9))), 44.dp)
                    uri.scheme != "file" -> IconTile(Icons.Rounded.Lan, paletteFor(f), 44.dp)
                    else -> IconTile(Icons.Rounded.Folder, paletteFor(f), 44.dp)
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: Any, progress: Map<String, Float>, watched: Set<String>, onClick: (Any) -> Unit) {
    when (item) {
        is LibraryMovie -> PosterCard(
            item.title, item.year?.toString(), item.meta?.poster, item.uri, progress[item.uri].takeIf { item.uri !in watched },
            badge = if (item.uri in watched) "✓" else null,
        ) { onClick(item) }
        is LibraryShow -> {
            // Like Jellyfin: how many episodes are left, or a tick when all are watched.
            val left = item.episodes.count { it.uri !in watched }
            PosterCard(
                item.title, "${item.episodes.size} episode${if (item.episodes.size == 1) "" else "s"}",
                item.meta?.poster, item.episodes.firstOrNull()?.uri, null,
                badge = if (left == 0) "✓" else left.toString(),
            ) { onClick(item) }
        }
    }
}

@Composable
internal fun <T> Shelf(title: String, items: List<T>, card: @Composable (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        SectionTitle(title, Modifier.padding(start = 8.dp, bottom = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(items) { card(it) }
        }
    }
}

/** 2:3 poster: TMDb artwork, else a frame from the file, else a colour placeholder. */
@Composable
internal fun PosterCard(
    title: String,
    subtitle: String?,
    poster: String?,
    fileUri: String?,
    progress: Float?,
    badge: String? = null,
    /** A full image URL (Jellyfin) instead of a TMDb [poster] path. */
    posterUrl: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(118.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium).background(Cinema.PanelHigh)) {
            WebImage(posterUrl ?: Tmdb.image(poster, "w342"), Modifier.fillMaxSize()) { FileThumb(fileUri, title) }
            ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            if (badge != null) {
                Text(
                    badge,
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Cinema.Accent)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .semantics { contentDescription = if (badge == "✓") "Watched" else "$badge unwatched" },
                    style = MaterialTheme.typography.labelMedium, color = Cinema.OnAccent,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1)
    }
}

/** A frame from the file (on the phone or a network drive); Telegram items get the colour placeholder. */
@Composable
internal fun FileThumb(fileUri: String?, seed: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val uri = fileUri?.let(Uri::parse)
    when (uri?.scheme) {
        "file" -> LocalThumb(uri.path.orEmpty(), MediaKind.VIDEO, modifier) { GradientPlaceholder(seed, MediaKind.VIDEO) }
        in NetworkDrive.PROTOCOLS -> NetThumb(fileUri!!, modifier) { GradientPlaceholder(seed, MediaKind.VIDEO) }
        else -> Box(modifier) { GradientPlaceholder(seed, MediaKind.VIDEO) }
    }
}

@Composable
internal fun ProgressBar(progress: Float?, modifier: Modifier) {
    if (progress == null || progress <= 0f) return
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Cinema.Accent))
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
}

// ─── Details ──────────────────────────────────────────────────────────────────

/**
 * Console game page: the backdrop fills the top and fades into the stage, a small all-caps line ([eyebrow])
 * over the big title, then a row of big-number [stats] and the genres.
 */
@Composable
private fun DetailHeader(
    title: String,
    eyebrow: String,
    genres: List<String>,
    backdrop: String?,
    fileUri: String?,
    onBack: () -> Unit,
    stats: @Composable RowScope.() -> Unit,
) {
    ConsoleHero(
        art = { WebImage(Tmdb.image(backdrop, "w1280"), Modifier.fillMaxSize()) { FileThumb(fileUri, title) } },
        top = {
            IconButton(onClick = onBack, Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
        },
        headline = {
            if (eyebrow.isNotEmpty()) Text(eyebrow, style = CaptionStyle, color = Cinema.AccentText)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        },
        narrowAspect = 4f / 3f,
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp), content = stats)
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(genres.joinToString(", "), style = CaptionStyle, color = Cinema.TextDim)
        }
    }
}

/** Year and TMDb score as console stats; nothing when unknown. */
@Composable
private fun YearAndRatingStats(year: Int?, rating: Double?) {
    if (year != null) Stat("Released", "$year" to "")
    if (rating != null && rating > 0) Stat("Rating", "%.1f".format(rating) to "/10")
}

@Composable
private fun MovieDetail(movie: LibraryMovie, progress: Float?, watched: Boolean, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = movie.meta
    var identify by remember { mutableStateOf(false) }
    val resuming = !watched && progress != null && progress > 0f
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            DetailHeader(movie.title, "Movie", meta?.genres.orEmpty(), meta?.backdrop, movie.uri, onBack) {
                YearAndRatingStats(movie.year, meta?.rating)
                Stat("Status", (if (watched) "Seen" else if (resuming) "${(progress!! * 100).toInt()}" else "New") to (if (resuming) "%" else ""))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                if (resuming) {
                    LabeledProgress("Watch progress", progress!!)
                    Spacer(Modifier.height(14.dp))
                }
                PrimaryButton(if (resuming) "Resume" else "Play", icon = Icons.Rounded.PlayArrow) {
                    context.playAll(listOf(movie.uri), listOf(movie.title), 0)
                }
                Row {
                    TextButton(onClick = { scope.launch { setWatched(movie.uri, movie.title, !watched) } }) {
                        Text(if (watched) "Mark unwatched" else "Mark watched")
                    }
                    if (Tmdb.available) TextButton(onClick = { identify = true }) { Text("Fix match") }
                }
                if (!meta?.overview.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(meta!!.overview, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                Text(Uri.decode(movie.uri), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
            }
        }
    }
    if (identify) IdentifySheet("movie", movie.name, movie.parsedYear, onDismiss = { identify = false }) { MediaLibrary.setMatch(MediaLibrary.matchKey(movie), it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowDetail(show: LibraryShow, progress: Map<String, Float>, watched: Set<String>, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta = show.meta
    val seasons = remember(show) { show.episodes.map { it.season }.distinct() }
    var season by rememberSaveable(show.key) { mutableIntStateOf(seasons.first()) }
    var identify by remember { mutableStateOf(false) }
    val episodes = show.episodes.filter { it.season == season }
    val seen = show.episodes.count { it.uri in watched }
    // The first episode not yet watched, in order: what "Continue" plays.
    val next = show.episodes.firstOrNull { it.uri !in watched }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            DetailHeader(show.title, "Series", meta?.genres.orEmpty(), meta?.backdrop, show.episodes.first().uri, onBack) {
                YearAndRatingStats(show.year, meta?.rating)
                Stat("Seasons", "${seasons.count { it > 0 }.coerceAtLeast(1)}" to "")
                Stat("Episodes", "${show.episodes.size}" to "")
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                LabeledProgress("Series progress", seen.toFloat() / show.episodes.size)
                if (next != null) {
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(if (seen == 0) "Play ${next.code}" else "Continue ${next.code}", icon = Icons.Rounded.PlayArrow) {
                        val list = show.episodes.filter { it.season == next.season }
                        context.playEpisodes(show, list, list.indexOf(next))
                    }
                }
            }
        }
        if (!meta?.overview.isNullOrBlank()) item {
            Text(meta!!.overview, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = Cinema.Text.copy(alpha = 0.85f), maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        if (Tmdb.available) item {
            TextButton(onClick = { identify = true }, Modifier.padding(horizontal = 8.dp)) { Text("Fix match") }
        }
        if (seasons.size > 1) item {
            PillTabs(seasons, season, { if (it == 0) "Specials" else "Season $it" }, { season = it }, Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
        } else item { SectionTitle(if (season == 0) "Specials" else "Season $season", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)) }
        item { Hint("Long-press an episode to mark it watched or unwatched.") }
        itemsIndexed(episodes, key = { _, e -> e.uri }) { i, e ->
            val done = e.uri in watched
            ListRow(
                e.title, e.meta?.overview, { context.playEpisodes(show, episodes, i) },
                Modifier.padding(horizontal = 12.dp),
                eyebrow = "${show.title} · ${e.code}",
                onLongClick = { scope.launch { setWatched(e.uri, "${show.title} · ${e.code} · ${e.title}", !done) } },
                trailing = { if (done) Icon(Icons.Rounded.CheckCircle, "Watched", tint = Cinema.AccentText) },
                leading = {
                    Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Cinema.PanelHigh)) {
                        WebImage(Tmdb.image(e.meta?.still, "w300"), Modifier.fillMaxSize()) { FileThumb(e.uri, e.title) }
                        ProgressBar(progress[e.uri].takeIf { !done }, Modifier.align(Alignment.BottomStart))
                    }
                },
            )
        }
    }
    if (identify) IdentifySheet("tv", show.name, show.parsedYear, onDismiss = { identify = false }) { MediaLibrary.setMatch(MediaLibrary.matchKey(show), it) }
}

/** Watched = a history entry at the end; unwatched = no entry (so it also leaves Continue watching). */
private suspend fun setWatched(uri: String, title: String, watched: Boolean) =
    if (watched) Store.saveHistory(uri, title, 1, 1) else Store.removeHistory(uri)

/** Fix match, like Jellyfin's Identify: search TMDb by hand and pick the right title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentifySheet(kind: String, name: String, year: Int?, onDismiss: () -> Unit, onPick: (Meta) -> Unit) {
    var query by remember { mutableStateOf(name) }
    var yearText by remember { mutableStateOf(year?.toString().orEmpty()) }
    var search by remember { mutableIntStateOf(0) }
    val results by produceState<Result<List<Meta>>?>(null, search) {
        value = null
        value = runCatching { withContext(Dispatchers.IO) { Tmdb.candidates(kind, query.trim(), yearText.trim().toIntOrNull()) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Cinema.Panel) {
        LazyColumn(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("Fix match", style = MaterialTheme.typography.headlineSmall)
                Text("Search TMDb and pick the right ${if (kind == "movie") "movie" else "show"}.", style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.weight(1f), label = { Text("Title") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search++ }),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        yearText, { yearText = it.filter(Char::isDigit).take(4) }, Modifier.width(92.dp), label = { Text("Year") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search++ }),
                    )
                    IconButton(onClick = { search++ }) { Icon(Icons.Rounded.Search, "Search") }
                }
            }
            val r = results
            val list = r?.getOrNull()
            when {
                r == null -> item { LoadingRow() }
                list == null -> item { Hint("Couldn't reach TMDb: ${r.exceptionOrNull()?.message}") }
                list.isEmpty() -> item { Hint("Nothing found. Try another spelling or no year.") }
                else -> items(list, key = { it.tmdbId }) { m ->
                    ListRow(
                        m.title, listOfNotNull(m.year?.toString(), m.overview.takeIf { it.isNotBlank() }).joinToString(" · "),
                        { onPick(m); onDismiss() },
                        leading = {
                            WebImage(Tmdb.image(m.poster, "w154"), Modifier.width(46.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(Cinema.PanelHigh))
                        },
                    )
                }
            }
        }
    }
}
