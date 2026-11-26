package app.tgplayer.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import app.tgplayer.R
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.animation.Crossfade
import app.tgplayer.data.Jellyfin
import app.tgplayer.data.MediaKind
import app.tgplayer.data.MediaLibrary
import app.tgplayer.data.Meta
import app.tgplayer.data.Settings
import app.tgplayer.data.Store
import app.tgplayer.data.Tmdb
import app.tgplayer.local.Entry
import app.tgplayer.local.FoundServer
import app.tgplayer.local.LocalItem
import app.tgplayer.local.LocalMedia
import app.tgplayer.local.NetEntry
import app.tgplayer.local.NetworkDrive
import app.tgplayer.local.NetworkDrives
import app.tgplayer.local.ServerDiscovery
import app.tgplayer.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun Context.play(items: List<LocalItem>, index: Int) =
    startActivity(PlayerActivity.intent(this, items.map { it.uri }, items.map { it.title }, index))

private fun Context.playUri(uri: String, title: String?) =
    startActivity(PlayerActivity.intent(this, listOf(uri), title?.let { listOf(it) }, 0))

internal val bottomPad = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp)

// ─── Video ────────────────────────────────────────────────────────────────────

/**
 * Every video on the phone as a thumbnail grid, newest first, under the Continue-watching hero.
 * The grid is lazy, so a thumbnail is only made when its tile scrolls into view.
 */
@Composable
fun VideoTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access = rememberFileAccess()
    val resumes by AppEvents.resumed.collectAsState()
    // Bumps on every history write: the player saves its position as it closes, often after we resume.
    val storeVersion by Store.version.collectAsState()
    // Enough history to mark progress on any recently played tile, not just the hero's few.
    val history by produceState(emptyList<Store.HistoryEntry>(), resumes, storeVersion) { value = Store.recentHistory(2_000) }
    val progress = remember(history) { history.associate { it.uri to it.fraction } }
    val videos by produceState<List<LocalItem>?>(null, resumes, access) {
        value = if (access) runCatching { LocalMedia.allVideos(context) }.getOrDefault(emptyList()) else emptyList()
    }
    val continuing = history.filter { it.positionMs > 5_000 && it.fraction < 0.95f }.take(15)
    // TMDb art: Library videos from the catalog; videos in progress outside it looked up by name (cached), so the
    // hero and rail get art too. The rest keep a frame from the file.
    val catalog by MediaLibrary.catalog.collectAsState()
    val named by produceState(emptyMap<String, Meta>(), continuing.map { it.uri }) {
        value = withContext(Dispatchers.IO) { continuing.mapNotNull { e -> MediaLibrary.metaFor(e.uri, e.title)?.let { e.uri to it } }.toMap() }
    }
    val art = named + catalog?.metaByUri.orEmpty() // the Library's match (or Fix match) wins
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Like the console, the white frame stays on the last video you opened (at first, the last one played).
    val framed = remember(videos, history, selected) {
        val uris = videos.orEmpty().mapTo(HashSet()) { it.uri }
        selected ?: history.firstOrNull { it.uri in uris }?.uri ?: videos?.firstOrNull()?.uri
    }
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(videos, query) {
        val q = query.trim()
        if (q.isEmpty()) videos else videos?.filter { it.title.contains(q, ignoreCase = true) }
    }

    LazyVerticalGrid(
        GridCells.Adaptive(110.dp), modifier, contentPadding = bottomPad,
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hero", span = full) {
            if (continuing.isNotEmpty()) VideoHero(continuing, videos.orEmpty(), art)
            else BrandHeader("Videos", "Every video on this phone, newest first")
        }
        val list = videos
        when {
            !access -> item(span = full) { AccessGate() }
            list == null -> item(span = full) { LoadingRow() }
            list.isEmpty() -> item(span = full) { EmptyState("No videos found", "Videos you record or download appear here.") }
            else -> {
                // Keyed, so the field keeps focus and the keyboard while the grid below it filters.
                item(key = "search", span = full) {
                    SearchField(query, { query = it }, "Search videos", Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
                val matches = shown.orEmpty()
                item(key = "all", span = full) {
                    SectionTitle(
                        if (query.isBlank()) "All videos · ${list.size}" else "Matches · ${matches.size}",
                        Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
                if (matches.isEmpty()) item(key = "none", span = full) { EmptyState("No matches", "No video title contains “${query.trim()}”.") }
                gridItems(matches, key = { it.path }) { v ->
                    VideoTile(v, art[v.uri]?.poster, progress[v.uri], v.uri == framed) {
                        selected = v.uri
                        // Plays on through the rest of its folder, like opening it from the folder did.
                        val folder = list.filter { File(it.path).parent == File(v.path).parent }.sortedBy { it.title.lowercase() }
                        context.play(folder, folder.indexOf(v))
                    }
                }
            }
        }
    }
}

/**
 * A grid tile: a 2:3 poster like the Library's (TMDb [poster] for a Library video, else a frame from the file)
 * with the blue resume bar if it was played, the console's white frame when [framed]; title below, then size
 * on the left and length on the right.
 */
@Composable
private fun VideoTile(item: LocalItem, poster: String?, progress: Float?, framed: Boolean, onClick: () -> Unit) {
    Column(Modifier.pressRing(onClick).padding(1.dp)) {
        Box(Modifier.focusRing(framed)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(Cinema.PanelHigh)) {
                WebImage(Tmdb.image(poster, "w342"), Modifier.fillMaxSize()) {
                    LocalThumb(item.path, item.kind, Modifier.fillMaxSize()) { GradientPlaceholder(item.title, item.kind) }
                }
                ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Column(Modifier.padding(horizontal = 3.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(formatSize(item.sizeBytes), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1)
                if (item.durationMs > 0) {
                    Text(formatDuration((item.durationMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Console home: the focused video fills the top with its stats, progress and a Resume box; below, a rail of
 * everything in progress. Tapping a tile focuses it (white frame, hero swaps), tapping the focused tile plays it.
 */
@Composable
private fun VideoHero(entries: List<Store.HistoryEntry>, videos: List<LocalItem>, art: Map<String, Meta>) {
    val context = LocalContext.current
    var focused by rememberSaveable { mutableStateOf(entries.first().uri) }
    val e = entries.firstOrNull { it.uri == focused } ?: entries.first()
    Column(Modifier.bleed(12.dp)) {
        ConsoleHero(
            // tails: a video with no art falls back to the 480x270 local thumbnail, soft on big screens; a full-size
            // frame grab would fix it.
            art = { Crossfade(e, label = "hero") { shown -> WideArt(shown.uri, shown.title, art[shown.uri], 1280) } },
            top = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("TG Player", style = MaterialTheme.typography.titleSmall)
                }
            },
            headline = {
                Text("Continue watching", style = CaptionStyle, color = Cinema.AccentText)
                Spacer(Modifier.height(4.dp))
                Text(e.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
        ) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Stat("Watched", *durationParts(e.positionMs))
                Stat("Left", *durationParts(e.durationMs - e.positionMs))
                if (videos.isNotEmpty()) {
                    Stat("Videos", "${videos.size}" to "")
                    Stat("Folders", "${remember(videos) { videos.distinctBy { File(it.path).parent }.size }}" to "")
                }
            }
            Spacer(Modifier.height(20.dp))
            LabeledProgress("Watch progress", e.fraction)
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Resume", icon = Icons.Rounded.PlayArrow) { context.playUri(e.uri, e.title) }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("In progress")
            Spacer(Modifier.height(6.dp))
        }
        ContinueRail(entries, e.uri, art, onFocus = { focused = it.uri }, onPlay = { context.playUri(it.uri, it.title) })
    }
}

/**
 * Wide art for a played video, best first: Jellyfin's backdrop, then its poster (episodes: their still); TMDb's
 * backdrop, then poster ([meta]: a Library or name match); else a frame from the file. [width] in pixels.
 */
@Composable
private fun WideArt(uri: String, title: String, meta: Meta?, width: Int) {
    val urls = listOfNotNull(
        Jellyfin.imageUrl(uri, "Backdrop", width), Jellyfin.imageUrl(uri, "Primary", width),
        Tmdb.image(meta?.backdrop, if (width > 780) "w1280" else "w780"), Tmdb.image(meta?.poster, "w780"),
    )
    ArtChain(urls) { FileThumb(uri, title) }
}

/** The first of [urls] that loads, else [fallback]: each image's placeholder tries the next. */
@Composable
private fun ArtChain(urls: List<String>, fallback: @Composable () -> Unit) {
    if (urls.isEmpty()) fallback() else WebImage(urls.first(), Modifier.fillMaxSize()) { ArtChain(urls.drop(1), fallback) }
}

/**
 * Cover-flow rail: the focused video sits big in the middle in its own blurred glow, its neighbours shrink and
 * fade the further out they are, keeping an even gap. Swipe or tap a side card to bring it to the middle
 * (that focuses it); tap the middle card to play. With three or more videos it loops round both ways.
 */
@Composable
private fun ContinueRail(
    entries: List<Store.HistoryEntry>,
    focusedUri: String,
    art: Map<String, Meta>,
    onFocus: (Store.HistoryEntry) -> Unit,
    onPlay: (Store.HistoryEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Three or more loop round: the last sits left of the first. Pages then run far both ways and map back by index.
    val loop = entries.size > 2
    val latest by rememberUpdatedState(entries)
    val pager = rememberPagerState(
        entries.indexOfFirst { it.uri == focusedUri }.coerceAtLeast(0).let { if (loop) nearestLoopPage(LOOP_PAGES / 2, entries.size, it) else it },
    ) { if (latest.size > 2) LOOP_PAGES else latest.size }
    val focus by rememberUpdatedState(onFocus)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { latest.getOrNull(it.mod(latest.size))?.let(focus) } }
    // The list reorders after a play (the played one moves to the front): bring the focused video back to the middle.
    val uris = entries.map { it.uri }
    LaunchedEffect(uris, loop) {
        val i = uris.indexOf(focusedUri).takeIf { it >= 0 } ?: return@LaunchedEffect
        // Coming from a short, non-looping list the page is near 0, so restart from the middle of the loop.
        val target = if (loop) nearestLoopPage(pager.currentPage.takeIf { it >= LOOP_PAGES / 4 } ?: (LOOP_PAGES / 2), uris.size, i) else i
        if (target != pager.currentPage) pager.scrollToPage(target)
    }
    WheelClicks(pager)

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).drawBehind {
            if (Build.VERSION.SDK_INT >= 31) return@drawBehind // the focused video's own glow lights the rail instead
            // An ellipse of projector light behind the middle card: a radial gradient stretched sideways.
            val r = size.height * 0.85f
            scale(scaleX = size.width / size.height * 0.55f, scaleY = 1f) {
                drawCircle(Brush.radialGradient(0f to Cinema.AccentFill.copy(alpha = 0.35f), 0.5f to Cinema.AccentFill.copy(alpha = 0.1f), 1f to Color.Transparent, center = center, radius = r), r)
            }
        },
    ) {
        val cardWidth = minOf(maxWidth * 0.42f, 260.dp)
        if (Build.VERSION.SDK_INT >= 31) {
            // Ambient light: the middle video, blurred and widened, glowing out from behind its card. Android cuts a
            // blur off at its layer's edge, so the layer is [margin] bigger than the picture all round for the blur
            // to fade out in; matchParentSize keeps that overflow from growing the rail.
            val lit = entries.firstOrNull { it.uri == focusedUri } ?: entries.first()
            val margin = 40.dp
            Box(
                Modifier.matchParentSize()
                    .requiredSize(cardWidth * 1.7f + margin * 2, cardWidth * 9f / 16f + margin * 2)
                    .graphicsLayer { alpha = 0.75f }
                    .blur(32.dp, BlurredEdgeTreatment.Unbounded)
                    .padding(margin),
            ) { Crossfade(lit, label = "glow") { WideArt(it.uri, it.title, art[it.uri], 780) } }
        }
        val gap = 12.dp
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
            pageSize = PageSize.Fixed(cardWidth),
            pageSpacing = gap,
            beyondViewportPageCount = 2,
        ) { page ->
            val item = entries[page.mod(entries.size)]
            Box(
                Modifier.width(cardWidth).aspectRatio(16f / 9f)
                    .graphicsLayer {
                        // Signed distance from the middle in pages: 0 = middle, +1 = next card to the right.
                        val o = page - pager.currentPage - pager.currentPageOffsetFraction
                        val a = abs(o)
                        val s = railKnot(RAIL_SCALES, a)
                        scaleX = s; scaleY = s
                        alpha = railKnot(RAIL_ALPHAS, a)
                        // Pull shrunken cards inward so the visual gap stays [gap] instead of growing with the shrink.
                        val w = cardWidth.toPx(); val g = gap.toPx()
                        translationX = sign(o) * (w * railSpan(a) + g * a) - o * (w + g)
                        shadowElevation = (1f - a).coerceAtLeast(0f) * 24.dp.toPx()
                        shape = RAIL_SHAPE; clip = true
                    }
                    .background(Cinema.PanelHigh)
                    .border(1.5.dp, Cinema.Focus.copy(alpha = if (page == pager.currentPage) 0.9f else 0f), RAIL_SHAPE)
                    .clickable(onClickLabel = if (page == pager.settledPage) "Play" else "Focus") {
                        if (page == pager.settledPage) onPlay(item)
                        else scope.launch { pager.animateScrollToPage(page, animationSpec = tween(450, easing = FastOutSlowInEasing)) }
                    },
            ) {
                WideArt(item.uri, item.title, art[item.uri], 780)
                Box(Modifier.fillMaxSize().background(Cinema.Scrim))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 9.dp)) {
                    Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.durationMs > item.positionMs) {
                        Text(
                            "${formatDuration(((item.durationMs - item.positionMs) / 1000).toInt())} left",
                            style = MaterialTheme.typography.bodySmall, color = Cinema.Text.copy(alpha = 0.7f), maxLines = 1,
                        )
                    }
                }
                ProgressBar(item.fraction, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/**
 * The old click wheel's feel: a short tick and a light buzz each time a new card reaches the middle, as you swipe
 * or as a tapped card slides in. The sound plays as system sonification, so it follows the phone's system volume
 * and silent mode. The buzz is tagged as media vibration: as touch feedback (the View haptics call, or a bare
 * tick effect, which Android files as touch) it is dropped whenever the phone's touch feedback is off.
 */
@Composable
private fun WheelClicks(pager: PagerState) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    val buzz = remember {
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(8, 60)
    }
    val sounds = remember {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
    }
    val tick = remember(sounds) { sounds.load(context, R.raw.wheel_tick, 1) }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    LaunchedEffect(pager) {
        // drop(1): not on first show. A jump by code (re-centring after a play) has already finished scrolling
        // by the time it is seen here, so only swipes and tap animations tick.
        snapshotFlow { pager.currentPage }.drop(1).collect {
            if (!pager.isScrollInProgress) return@collect
            sounds.play(tick, 0.5f, 0.5f, 1, 0, 1f)
            if (Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(buzz, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            else vibrator?.vibrate(buzz)
        }
    }
}

private val RAIL_SHAPE = RoundedCornerShape(14.dp)
/** Page count of a looping rail: room to swipe tens of thousands of cards either way from the middle. */
private const val LOOP_PAGES = 100_000

/** The page nearest [current] that shows item [index] of a looping rail of [size] items (page mod size = index). */
internal fun nearestLoopPage(current: Int, size: Int, index: Int): Int {
    val p = current - current.mod(size) + index
    return when {
        p - current > size / 2 -> p - size
        current - p > size / 2 -> p + size
        else -> p
    }
}
/** Card scale and opacity at 0, 1, 2 and 3+ pages from the middle; linear in between. */
internal val RAIL_SCALES = floatArrayOf(1f, 0.74f, 0.6f, 0.52f)
internal val RAIL_ALPHAS = floatArrayOf(1f, 0.55f, 0.3f, 0.15f)

/** [knots] read at [a] pages from the middle, linear between whole pages, flat past the last knot. */
internal fun railKnot(knots: FloatArray, a: Float): Float {
    val i = a.toInt()
    return if (i >= knots.lastIndex) knots.last() else knots[i] + (knots[i + 1] - knots[i]) * (a - i)
}

/**
 * How far, in unscaled card widths, the centre of a card [a] pages out sits from the middle card's centre once
 * cards are shrunk by [RAIL_SCALES] (gaps not included): the integral of the scale from 0 to [a]. Being exact
 * for the piecewise-linear scale, neighbouring cards touch edge to edge at every whole and in-between offset.
 */
internal fun railSpan(a: Float): Float {
    var sum = 0f
    var t = 0
    while (t + 1 <= a && t < RAIL_SCALES.lastIndex) { sum += (RAIL_SCALES[t] + RAIL_SCALES[t + 1]) / 2; t++ }
    return sum + (a - t) * (railKnot(RAIL_SCALES, t.toFloat()) + railKnot(RAIL_SCALES, a)) / 2
}

/** Lets a full-bleed block ignore the list's side padding of [by] on each side. */
internal fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

// ─── Browse ───────────────────────────────────────────────────────────────────

@Composable
fun BrowseTab(onOpenTelegram: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access = rememberFileAccess()
    var dir by rememberSaveable { mutableStateOf<String?>(null) }
    var network by remember { mutableStateOf(false) }
    var netDir by rememberSaveable { mutableStateOf<String?>(null) }
    var jellyfin by rememberSaveable { mutableStateOf(false) }
    var dropIt by rememberSaveable { mutableStateOf<List<String>?>(null) } // files to start with when open
    var jellyfinSignIn by remember { mutableStateOf(false) }
    var drives by remember { mutableStateOf(NetworkDrives.all()) }
    var addDrive by remember { mutableStateOf(false) }
    var removeDrive by remember { mutableStateOf<NetworkDrive?>(null) }
    val roots = remember { LocalMedia.roots(context) }

    val drop = dropIt
    if (drop != null) {
        DropItScreen(drop, onExit = { dropIt = null }, modifier)
        return
    }
    val current = dir
    if (current != null && access) {
        FileBrowser(File(current), roots.map { it.second.path }, onNavigate = { dir = it }, onDrop = { dropIt = listOf(it) }, modifier)
        return
    }
    if (jellyfin) {
        JellyfinScreen(onExit = { jellyfin = false }, modifier)
        return
    }
    val net = netDir
    if (net != null) {
        NetworkBrowser(Uri.parse(net), onNavigate = { netDir = it }, modifier)
        return
    }
    LazyColumn(modifier, contentPadding = bottomPad) {
        item { BrandHeader("Browse", "Telegram, links and every folder on your phone") }
        item { SectionTitle("Sources", Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) }
        item {
            ListRow("Telegram", "Videos and music from your chats and channels", onOpenTelegram) {
                IconTile(Icons.AutoMirrored.Rounded.Send, Brush.linearGradient(listOf(Color(0xFF2AABEE), Color(0xFF229ED9))))
            }
        }
        item {
            ListRow("Network stream", "Play a link: MP4, MKV, HLS (.m3u8), DASH (.mpd)", { network = true }) {
                IconTile(Icons.Rounded.Language, Cinema.AccentWarm)
            }
        }
        item {
            val server = Jellyfin.server
            ListRow(
                "Jellyfin", server?.let { "${it.name} · ${it.userName}" } ?: "Sign in to your Jellyfin server",
                { if (Jellyfin.server != null) jellyfin = true else jellyfinSignIn = true },
            ) { IconTile(Icons.Rounded.Dns, Brush.linearGradient(listOf(Color(0xFFAA5CC3), Color(0xFF00A4DC)))) }
        }
        item {
            ListRow("Drop It", "Send files to phones nearby on the same Wi-Fi", { dropIt = emptyList() }) {
                IconTile(Icons.Rounded.WifiTethering, Brush.linearGradient(listOf(Color(0xFFFF8A3D), Color(0xFFE8457A))))
            }
        }
        item { SectionTitle("Storage", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)) }
        if (!access) item { AccessGate() }
        else items(roots, key = { it.second.path }) { (name, file) ->
            ListRow(name, file.path, { dir = file.path }) {
                IconTile(if (name == "Internal storage") Icons.Rounded.PhoneAndroid else Icons.Rounded.SdCard, paletteFor(name))
            }
        }
        item { SectionTitle("Network drives", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)) }
        items(drives, key = { it.root.toString() }) { d ->
            ListRow(d.title, "${d.protocol.uppercase()} · ${d.user.ifEmpty { "Guest" }}", { netDir = d.root.toString() }, onLongClick = { removeDrive = d }) {
                IconTile(Icons.Rounded.Lan, paletteFor(d.host))
            }
        }
        item {
            ListRow("Add network drive", "A shared folder on a PC or NAS (SMB), or an FTP server", { addDrive = true }) { IconTile(Icons.Rounded.Add, Cinema.Accent, tint = Cinema.OnAccent) }
        }
    }
    if (network) NetworkSheet(onDismiss = { network = false })
    if (jellyfinSignIn) JellyfinSignInSheet(onDismiss = { jellyfinSignIn = false }, onSignedIn = { jellyfinSignIn = false; jellyfin = true })
    if (addDrive) AddDriveSheet(onDismiss = { addDrive = false }, onAdded = { d -> addDrive = false; drives = NetworkDrives.all(); netDir = d.root.toString() })
    removeDrive?.let { d ->
        AlertDialog(
            onDismissRequest = { removeDrive = null },
            title = { Text("Remove ${d.title}?") },
            text = { Text("TG Player forgets this drive and its password, and its folders leave the Library. Nothing on the drive is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    removeDrive = null
                    NetworkDrives.remove(d)
                    MediaLibrary.removeFolders(Settings.libraryFolders.filter { d.owns(Uri.parse(it)) })
                    drives = NetworkDrives.all()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeDrive = null }) { Text("Cancel") } },
            containerColor = Cinema.PanelHigh,
        )
    }
}

/** Adds an SMB share or FTP server: connects first, so a wrong address, folder or password shows here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDriveSheet(onDismiss: () -> Unit, onAdded: (NetworkDrive) -> Unit) {
    val scope = rememberCoroutineScope()
    var protocol by remember { mutableStateOf(NetworkDrive.SMB) }
    val context = LocalContext.current
    var servers by remember { mutableStateOf(emptyList<FoundServer>()) }
    DisposableEffect(Unit) {
        val discovery = ServerDiscovery(context) { servers = it }.apply { start() }
        onDispose { discovery.stop() }
    }
    val ftp = protocol != NetworkDrive.SMB // FTP, FTPS and SFTP all take a server and an optional folder
    var address by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect() {
        // Accept a pasted \\host\share, smb://host/share or ftp://host/folder in the address field.
        val parts = address.trim().replace(Regex("^[a-z]+://", RegexOption.IGNORE_CASE), "").trim('\\', '/').split('\\', '/').filter { it.isNotBlank() }
        val host = parts.firstOrNull().orEmpty()
        val folder = share.trim().trim('\\', '/').ifEmpty { parts.drop(1).joinToString("/") }
        val shareName = if (ftp) folder else folder.substringBefore('/')
        if (host.isEmpty() || (!ftp && shareName.isEmpty())) {
            error = if (ftp) "Enter the server's address" else "Enter the computer's address and the shared folder's name"
            return
        }
        if (protocol == NetworkDrive.SFTP && user.isBlank()) {
            error = "SFTP needs a user name"
            return
        }
        // "DOMAIN\user" splits into domain and user.
        val domain = user.substringBefore('\\', "").trim()
        val drive = if (ftp) NetworkDrive(host, shareName, user.trim(), password, protocol = protocol)
        else NetworkDrive(host, shareName, user.substringAfter('\\').trim(), password, domain)
        busy = true
        error = null
        scope.launch {
            try {
                onAdded(NetworkDrives.add(drive))
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect"
            } finally {
                busy = false
            }
        }
    }
    // Fully open: half height would hide the Connect button under the form.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Text("Add network drive", style = MaterialTheme.typography.headlineSmall)
            Text(
                when (protocol) {
                    NetworkDrive.FTP -> "An FTP server on a PC, NAS, router or phone. Leave the user name empty to log in as a guest (anonymous)."
                    NetworkDrive.FTPS -> "FTP with encryption (explicit TLS). Self-signed certificates, common on NAS boxes, are accepted."
                    NetworkDrive.SFTP -> "Files over SSH, on Linux, macOS or a NAS with SSH on. The server's key is remembered on the first connection."
                    else -> "A folder shared from Windows, macOS, Linux (Samba) or a NAS. Leave the user name empty for guest access."
                },
                style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
            )
            if (servers.isNotEmpty()) {
                Text("On your network", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = Cinema.TextDim)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    servers.forEach { s ->
                        Text(
                            "${s.name} · ${s.protocol.uppercase()}",
                            Modifier.clip(RoundedCornerShape(50)).background(Cinema.PanelHigh)
                                .clickable { protocol = s.protocol; address = s.address; error = null }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            PillTabs(
                listOf(NetworkDrive.SMB, NetworkDrive.FTP, NetworkDrive.FTPS, NetworkDrive.SFTP), protocol, { it.uppercase() },
                { protocol = it; error = null }, Modifier.padding(vertical = 12.dp),
            )
            DriveField(address, { address = it; error = null }, if (ftp) "Address (192.168.1.20 or with :port)" else "Address (192.168.1.20 or NAS-NAME)", KeyboardType.Uri)
            DriveField(share, { share = it; error = null }, if (ftp) "Folder (optional)" else "Shared folder (Movies)")
            DriveField(user, { user = it; error = null }, if (protocol == NetworkDrive.SFTP) "User name" else "User name (optional)")
            DriveField(password, { password = it; error = null }, "Password", KeyboardType.Password, ImeAction.Go, onGo = ::connect)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Spacer(Modifier.height(6.dp))
            if (busy) LoadingRow() else PrimaryButton("Connect", onClick = ::connect)
        }
    }
}

@Composable
internal fun DriveField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    type: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onGo: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (type == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cinema.AccentText, unfocusedBorderColor = Cinema.Line, cursorColor = Cinema.AccentText),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

/** A folder on a network drive: subfolders, then playable files. Playing a file queues the rest of the folder. */
@Composable
private fun NetworkBrowser(dir: Uri, onNavigate: (String?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val segments = dir.pathSegments // SMB: [share, folder, …]; FTP: [folder, …]
    val rootDepth = remember(dir) { NetworkDrives.forUri(dir)?.root?.pathSegments?.size ?: 1 }
    val up: () -> Unit = {
        onNavigate(
            if (segments.size <= rootDepth) null
            else Uri.Builder().scheme(dir.scheme).encodedAuthority(dir.encodedAuthority).apply { segments.dropLast(1).forEach { appendPath(it) } }.build().toString(),
        )
    }
    BackHandler(onBack = up)
    var attempt by remember { mutableStateOf(0) }
    val result by produceState<Result<List<NetEntry>>?>(null, dir, attempt) {
        value = null
        value = runCatching { NetworkDrives.list(dir, Settings.showHiddenFiles) }
    }
    val files = result?.getOrNull().orEmpty().filter { !it.isDir }
    LazyColumn(modifier, contentPadding = bottomPad) {
        item {
            BackHeader(segments.lastOrNull() ?: dir.host.orEmpty(), "${dir.encodedAuthority}/${segments.joinToString("/")}", up) {
                LibraryToggle(dir.toString())
            }
        }
        val r = result
        val list = r?.getOrNull()
        when {
            r == null -> item { LoadingRow() }
            list == null -> item {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    EmptyState("Couldn't open this folder", r.exceptionOrNull()?.message ?: "The drive didn't answer.")
                    PrimaryButton("Try again") { attempt++ }
                }
            }
            list.isEmpty() -> item { EmptyState("Nothing playable here", "This folder has no media files or subfolders.") }
            else -> items(list, key = { it.uri.toString() }) { e ->
                if (e.isDir) {
                    ListRow(e.name, null, { onNavigate(e.uri.toString()) }) { IconTile(Icons.Rounded.Folder, paletteFor(e.name), 44.dp) }
                } else {
                    ListRow(
                        e.name, formatSize(e.sizeBytes),
                        {
                            val titles = files.map { it.name.substringBeforeLast('.') }
                            context.startActivity(PlayerActivity.intent(context, files.map { it.uri.toString() }, titles, files.indexOf(e)))
                        },
                        leading = {
                            val tile = Modifier.size(width = 78.dp, height = 44.dp).clip(MaterialTheme.shapes.small)
                            val icon: @Composable () -> Unit = {
                                Box(Modifier.fillMaxSize().background(paletteFor(e.name)), contentAlignment = Alignment.Center) {
                                    Icon(if (e.kind == MediaKind.AUDIO) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.5f))
                                }
                            }
                            if (e.kind == MediaKind.VIDEO) NetThumb(e.uri.toString(), tile, icon) else Box(tile) { icon() }
                        },
                    )
                }
            }
        }
    }
}

/** Header button: adds the open folder to the Library (it's scanned for movies and shows), or takes it out. */
@Composable
internal fun LibraryToggle(folderUri: String) {
    val context = LocalContext.current
    var inLibrary by remember(folderUri) { mutableStateOf(folderUri in Settings.libraryFolders) }
    IconButton(onClick = {
        if (inLibrary) MediaLibrary.removeFolders(listOf(folderUri)) else MediaLibrary.addFolder(folderUri)
        inLibrary = !inLibrary
        Toast.makeText(context, if (inLibrary) "Added to Library. Finding movies and shows…" else "Removed from Library", Toast.LENGTH_SHORT).show()
    }) {
        Icon(
            if (inLibrary) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
            if (inLibrary) "Remove folder from Library" else "Add folder to Library",
            tint = if (inLibrary) Cinema.AccentText else Cinema.Text,
        )
    }
}

/** Folder view: subfolders, then playable files. Playing a file queues the rest of the folder; long-press sends it with Drop It. */
@Composable
private fun FileBrowser(dir: File, roots: List<String>, onNavigate: (String?) -> Unit, onDrop: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val up: () -> Unit = { onNavigate(if (dir.path in roots) null else dir.parent) }
    BackHandler(onBack = up)
    val entries by produceState<List<Entry>?>(null, dir.path) { value = LocalMedia.list(dir, Settings.showHiddenFiles) }
    val media = entries.orEmpty().mapNotNull { it.item }
    LazyColumn(modifier, contentPadding = bottomPad) {
        item { BackHeader(dir.name.ifEmpty { dir.path }, dir.path, up) { LibraryToggle(Uri.fromFile(dir).toString()) } }
        val list = entries
        when {
            list == null -> item { LoadingRow() }
            list.isEmpty() -> item { EmptyState("Nothing playable here", "This folder has no media files or subfolders.") }
            else -> items(list, key = { it.file.path }) { e ->
                val item = e.item
                if (item == null) {
                    ListRow(e.file.name, null, { onNavigate(e.file.path) }) { IconTile(Icons.Rounded.Folder, paletteFor(e.file.name), 44.dp) }
                } else {
                    ListRow(
                        e.file.name, formatSize(item.sizeBytes), { context.play(media, media.indexOf(item)) },
                        onLongClick = { onDrop(item.uri) },
                        leading = {
                            LocalThumb(item.path, item.kind, Modifier.size(width = 78.dp, height = 44.dp).clip(MaterialTheme.shapes.small)) {
                                Box(Modifier.fillMaxSize().background(paletteFor(item.title)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.5f))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Paste/type a link and play it; recent links are kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(Settings.recentUrls) }
    var error by remember { mutableStateOf<String?>(null) }
    fun play(link: String) {
        val u = link.trim()
        val scheme = Uri.parse(u).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            error = "Enter a link starting with http:// or https://"
            return
        }
        Settings.recentUrls = listOf(u) + Settings.recentUrls.filter { it != u }
        recent = Settings.recentUrls
        onDismiss()
        context.playUri(u, null)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Network stream", style = MaterialTheme.typography.headlineSmall)
            Text(
                "TG Player downloads the file to disk ahead of playback, so it keeps buffering while paused.",
                style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("https://…") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { play(url) }),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cinema.AccentText, unfocusedBorderColor = Cinema.Line, cursorColor = Cinema.AccentText),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Play") { play(url) }
            if (recent.isNotEmpty()) {
                SectionTitle("Recent", Modifier.padding(top = 18.dp, bottom = 4.dp))
                recent.take(8).forEach { link ->
                    ListRow(Uri.parse(link).lastPathSegment ?: link, link, { play(link) }) { IconTile(Icons.Rounded.Language, Cinema.AccentWarm, 40.dp) }
                }
            }
        }
    }
}

// ─── Shared bits ──────────────────────────────────────────────────────────────

/** Screen header: the small TG Player mark, then the screen's title as a heavy title card, then a dim line. */
@Composable
fun BrandHeader(title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(22.dp)
            Spacer(Modifier.width(8.dp))
            Text("TG Player", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable
internal fun BackHeader(title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
internal fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private fun Modifier.clickableCompat(onClick: () -> Unit) = this.clickable(onClick = onClick)
