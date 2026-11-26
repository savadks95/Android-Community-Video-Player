package app.tgplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tgplayer.data.ChatItem
import app.tgplayer.data.Library
import app.tgplayer.data.MediaFilter
import app.tgplayer.data.MediaItem
import app.tgplayer.data.Progress
import app.tgplayer.data.Ref
import app.tgplayer.data.SavedList
import app.tgplayer.data.Store
import app.tgplayer.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    CHATS("Chats", Icons.Rounded.Forum),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
}

/** Signed-in Telegram section: four tabs, chat detail on top, the long-press sheet. [onExit] returns to the player. */
@Composable
fun TelegramRoot(onExit: () -> Unit, onAccount: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openChat by remember { mutableStateOf<ChatItem?>(null) }
    var sheetItem by remember { mutableStateOf<MediaItem?>(null) }
    val version by Store.version.collectAsState()
    val progress by produceState(emptyMap<String, Progress>(), version) { value = Store.allProgress() }
    var downloadsChanged by remember { mutableIntStateOf(0) }
    // Live offline-download state for every tile; polls only while something is still downloading.
    val downloads by produceState(emptyMap<String, TdApi.File>(), downloadsChanged) {
        while (true) {
            val list = runCatching { Library.downloads() }.getOrNull() ?: break
            value = list.mapNotNull { item -> item.file?.let { item.key to it } }.toMap()
            if (value.values.all { it.local.isDownloadingCompleted }) break
            delay(2_000)
        }
    }
    val actions = remember(progress, downloads) {
        object : MediaActions {
            override fun play(item: MediaItem) = context.startActivity(PlayerActivity.intent(context, item.chatId, item.messageId))
            override fun more(item: MediaItem) { sheetItem = item }
            override fun progressOf(item: MediaItem) = progress[item.key]?.fraction
            override fun downloadStatusOf(item: MediaItem) = downloads[item.key]?.let(::downloadStatus)
        }
    }
    // Kept here so the Home feed survives tab switches.
    val freshPager = remember { Pager<String> { Library.search("", MediaFilter.VIDEOS, it ?: "") } }
    LiveMessages(freshPager, MediaFilter.VIDEOS)

    BackHandler(openChat != null) { openChat = null }
    BackHandler(openChat == null && tab != Tab.HOME) { tab = Tab.HOME }
    BackHandler(openChat == null && tab == Tab.HOME) { onExit() }

    Box(Modifier.fillMaxSize().background(Cinema.Stage)) {
        val content = Modifier.fillMaxSize().statusBarsPadding()
        val chat = openChat
        if (chat != null) {
            ChatScreen(chat, { openChat = null }, actions, content)
        } else {
            when (tab) {
                Tab.HOME -> HomeScreen(freshPager, actions, { openChat = it }, content, onAccount)
                Tab.CHATS -> ChatsScreen({ openChat = it }, content)
                Tab.SEARCH -> SearchScreen(actions, content)
                Tab.LIBRARY -> LibraryScreen(actions, onAccount, content)
            }
            PillNavBar(
                Tab.entries, tab, { it.label }, { it.icon }, { tab = it },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onExit).padding(horizontal = 12.dp, vertical = 12.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Leave Telegram", Modifier.size(22.dp), tint = Cinema.TextDim) }
            }
        }
        sheetItem?.let { item ->
            MediaSheet(
                item, progress[item.key], onPlay = { actions.play(item) }, onDismiss = { sheetItem = null },
                onDownloadsChanged = { downloadsChanged++ },
            )
        }
    }
}

private fun downloadStatus(file: TdApi.File): String {
    val size = file.size.takeIf { it > 0 } ?: file.expectedSize
    return when {
        file.local.isDownloadingCompleted -> "Downloaded · ${formatSize(size)}"
        size > 0 -> "Downloading ${file.local.downloadedSize * 100 / size}% · ${formatSize(size)}"
        else -> "Downloading…"
    }
}

/**
 * Floating dock: every tab shows its glyph and a small caption; one projector-amber pill slides under the
 * selected glyph. Its two ends ride separate springs (the leading end quicker), so the pill stretches toward
 * the new tab and settles: fluid, not a jump. [leading] sits before the tabs.
 */
@Composable
fun <T> PillNavBar(
    tabs: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    // Where each glyph capsule sits (window coordinates) and where the bar starts, to aim the pill.
    val capsules = remember { mutableStateMapOf<Int, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = capsules[tabs.indexOf(selected)]?.translate(-origin)
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (!placed) {
            left.snapTo(target.left); right.snapTo(target.right); placed = true
            return@LaunchedEffect
        }
        val movingRight = target.left > left.value
        val lead = spring<Float>(dampingRatio = 0.82f, stiffness = 520f)
        val trail = spring<Float>(dampingRatio = 0.85f, stiffness = 190f)
        launch { left.animateTo(target.left, if (movingRight) trail else lead) }
        launch { right.animateTo(target.right, if (movingRight) lead else trail) }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Cinema.Panel)
            .border(1.dp, Cinema.Line, RoundedCornerShape(50))
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                // Read in the draw phase only, so the glide redraws without recomposing the bar.
                if (placed && target != null) {
                    drawRoundRect(
                        Cinema.AccentFill,
                        topLeft = Offset(left.value, target.top),
                        size = Size(right.value - left.value, target.height),
                        cornerRadius = CornerRadius(target.height / 2),
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        tabs.forEachIndexed { index, tab ->
            val isSelected = tab == selected
            val tint by animateColorAsState(if (isSelected) Cinema.Text else Cinema.TextDim, label = "tab")
            val glyph by animateColorAsState(if (isSelected) Cinema.OnAccent else Cinema.TextDim, label = "glyph")
            Column(
                Modifier
                    // No ripple: the sliding pill is the feedback.
                    .clickable(interactionSource = null, indication = null) { onSelect(tab) }
                    .padding(horizontal = if (tabs.size > 5) 4.dp else 8.dp, vertical = 2.dp), // six tabs still fit a 360dp phone
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .onGloballyPositioned { capsules[index] = it.boundsInWindow() }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Icon(icon(tab), label(tab), Modifier.size(20.dp), tint = glyph) }
                Spacer(Modifier.height(3.dp))
                Text(label(tab), style = CaptionStyle.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = tint, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSheet(item: MediaItem, progress: Progress?, onPlay: () -> Unit, onDismiss: () -> Unit, onDownloadsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ref = Ref(item.chatId, item.messageId)
    var favourite by remember(item.key) { mutableStateOf(false) }
    var later by remember(item.key) { mutableStateOf(false) }
    var offline by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        favourite = Store.isSaved(SavedList.FAVOURITES, ref)
        later = Store.isSaved(SavedList.WATCH_LATER, ref)
        offline = runCatching { Library.downloads().any { it.key == item.key } }.getOrDefault(false)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TdImage(
                    item.thumb, item.miniThumb,
                    Modifier.width(132.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small).background(Cinema.PanelHigh),
                ) { GradientPlaceholder(item.title, item.kind) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(item.chatTitle, if (item.durationSec > 0) formatDuration(item.durationSec) else "", formatSize(item.sizeBytes))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            val resumeAt = progress?.takeIf { it.positionMs > 5_000 && it.fraction < 0.95f }
            PrimaryButton(
                if (resumeAt != null) "Resume from ${formatDuration((resumeAt.positionMs / 1000).toInt())}" else "Play",
                icon = Icons.Rounded.PlayArrow,
            ) { onDismiss(); onPlay() }
            Spacer(Modifier.height(10.dp))
            SheetToggle(if (favourite) "In favourites" else "Add to favourites", if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, favourite) {
                favourite = !favourite
                scope.launch { Store.setSaved(SavedList.FAVOURITES, ref, favourite) }
            }
            SheetToggle(if (later) "In watch later" else "Watch later", if (later) Icons.Rounded.WatchLater else Icons.Rounded.Schedule, later) {
                later = !later
                scope.launch { Store.setSaved(SavedList.WATCH_LATER, ref, later) }
            }
            if (item.file != null) {
                SheetToggle(
                    if (offline) "Saved for offline · tap to remove" else "Download for offline",
                    if (offline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    offline,
                ) {
                    offline = !offline
                    scope.launch {
                        runCatching { if (offline) Library.saveOffline(item) else Library.removeOffline(item) }
                        onDownloadsChanged()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetToggle(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Cinema.Danger else Cinema.TextDim)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
