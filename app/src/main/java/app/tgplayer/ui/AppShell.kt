package app.tgplayer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.composed
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.tgplayer.LoginScreen
import app.tgplayer.Telegram
import app.tgplayer.data.Storage
import app.tgplayer.data.TelegramLock
import app.tgplayer.local.LocalMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** App-wide events the UI re-reads on (bumped by MainActivity.onResume, e.g. after granting file access). */
object AppEvents {
    val resumed = MutableStateFlow(0)
}

enum class MainTab(val label: String, val icon: ImageVector) {
    VIDEO("Video", Icons.Rounded.VideoLibrary),
    MUSIC("Music", Icons.Rounded.LibraryMusic),
    BROWSE("Browse", Icons.Rounded.FolderOpen),
    LIBRARY("Library", Icons.Rounded.Theaters),
    MORE("More", Icons.Rounded.Tune),
}

/**
 * The player app: Video, Music, Browse (files, network drives, links, Telegram), Library (movies and shows) and More.
 * Telegram opens full screen.
 */
@Composable
fun AppShell() {
    var selected by rememberSaveable { mutableStateOf(MainTab.VIDEO) }
    var telegram by rememberSaveable { mutableStateOf(false) }
    val tabs = MainTab.entries
    val tab = selected
    if (telegram) {
        TelegramSection(onExit = { telegram = false })
        return
    }
    BackHandler(tab != MainTab.VIDEO) { selected = MainTab.VIDEO }
    Box(Modifier.fillMaxSize().background(Cinema.Stage)) {
        val content = Modifier.fillMaxSize().statusBarsPadding()
        when (tab) {
            MainTab.VIDEO -> VideoTab(content)
            MainTab.MUSIC -> MusicTab(content)
            MainTab.BROWSE -> BrowseTab(onOpenTelegram = { telegram = true }, content)
            MainTab.LIBRARY -> LibraryTab(content)
            MainTab.MORE -> MoreScreen(content)
        }
        PillNavBar(
            tabs, tab, { it.label }, { it.icon }, { selected = it },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
        )
    }
}

/** Telegram inside the player: starts TDLib on first use and asks to log in only now. */
@Composable
fun TelegramSection(onExit: () -> Unit) {
    val context = LocalContext.current
    remember {
        TelegramLock.init(context)
        Telegram.start(context)
    }
    val auth by Telegram.authState.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(!TelegramLock.isSet || TelegramLock.unlockedThisVisit) }
    val leave = {
        TelegramLock.unlockedThisVisit = false // the password is asked again on the next visit
        onExit()
    }
    fun logOut() {
        TelegramLock.clear() // nothing left to protect once signed out
        unlocked = true
        scope.launch { runCatching { Telegram.send(TdApi.LogOut()) } }
    }
    // Signed out elsewhere (e.g. session ended from another device): the lock has nothing to guard.
    LaunchedEffect(auth) {
        if (auth is TdApi.AuthorizationStateWaitPhoneNumber && TelegramLock.isSet) {
            TelegramLock.clear()
            unlocked = true
        }
    }

    Box(Modifier.fillMaxSize().background(Cinema.Stage)) {
        if (!unlocked) {
            TelegramLockScreen(
                onUnlocked = { TelegramLock.unlockedThisVisit = true; unlocked = true },
                onExit = leave,
                onForgot = ::logOut,
            )
        } else if (auth is TdApi.AuthorizationStateReady) {
            LaunchedEffect(Unit) { runCatching { Storage.trim() } } // keep the Telegram streaming cache under its limit
            TelegramRoot(onExit = leave, onAccount = { account = true })
        } else {
            BackHandler(onBack = leave)
            LoginScreen(auth, Modifier.safeDrawingPadding())
            IconButton(onClick = leave, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
        }
    }
    if (account) TelegramAccountSheet(onDismiss = { account = false }, onLogout = { account = false; confirmLogout = true })
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of Telegram?") },
            text = { Text("TG Player will forget this login and remove its Telegram password. Your favourites and watch later lists stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    logOut()
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
            containerColor = Cinema.PanelHigh,
        )
    }
}

/** Whether we can read the phone's files; re-checked every time the app comes back to the front. */
@Composable
fun rememberFileAccess(): Boolean {
    val context = LocalContext.current
    val resumes by AppEvents.resumed.collectAsState()
    return remember(resumes) { LocalMedia.hasAccess(context) }
}

/** Explains and requests file access (Android 11+: the "all files" switch; older: the storage permission). */
@Composable
fun AccessGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AppEvents.resumed.value++ }
    Column(modifier.fillMaxWidth().padding(24.dp)) {
        Text("Your files", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "To list videos and music and browse folders like a file manager, TG Player needs access to all files. " +
                "Android shows a switch: turn on “Allow access to manage all files”, then come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cinema.TextDim,
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Allow file access") {
            if (Build.VERSION.SDK_INT >= 30) context.startActivity(LocalMedia.allFilesAccessIntent(context))
            else legacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

/** The main action: a projector-amber pill, its glyph then its label in ink, centred. */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Rounded.ArrowForward,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clip(RoundedCornerShape(50))
            .background(Cinema.AccentFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Cinema.OnAccent)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Cinema.OnAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A tappable list row: leading visual, optional small [eyebrow], title, subtitle, trailing. */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    leading: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .pressRing(onClick, onLongClick)
            .padding(horizontal = 5.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Text(eyebrow, style = CaptionStyle.copy(fontSize = 11.sp), color = Cinema.AccentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Rounded icon tile used for folders and sources. [brush] defaults to the velvet gradient; pass [tint] ink on amber. */
@Composable
fun IconTile(
    icon: ImageVector,
    brush: androidx.compose.ui.graphics.Brush = Cinema.AccentWarm,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    tint: Color = Color.White,
) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** Tap target whose feedback is an amber ring instead of a ripple. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.pressRing(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ring by animateColorAsState(if (pressed) Cinema.Focus else Color.Transparent, label = "ring")
    this.border(2.dp, ring, RoundedCornerShape(12.dp))
        .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}
