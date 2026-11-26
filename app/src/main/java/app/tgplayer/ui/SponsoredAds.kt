package app.tgplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tgplayer.Telegram
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/*
 * Telegram's official sponsored messages. The Telegram API terms (3.3) require every app that shows
 * channel content to display them: a sponsored post in channel screens, and text ads on the player
 * while a channel video plays. Display rules: https://core.telegram.org/api/sponsored-messages
 */

// ─── Channel screen ───────────────────────────────────────────────────────────

/** The channel's sponsored post, if Telegram has one for this user. Shown above the newest post, which is where the grid ends. */
@Composable
fun SponsoredPost(chatId: Long, modifier: Modifier = Modifier) {
    var ad by remember(chatId) { mutableStateOf<TdApi.SponsoredMessage?>(null) }
    // TDLib counts sponsored views only in an open chat; ChatScreen keeps it open while it's shown. (Not opened here:
    // this card leaves composition when scrolled away, and closing the chat then would stop its live updates.)
    LaunchedEffect(chatId) {
        // tails: first ad only, and its text only (no photo/video); messagesBetween applies to message lists, not this grid.
        ad = runCatching { Telegram.send(TdApi.GetChatSponsoredMessages(chatId)).messages.firstOrNull() }.getOrNull()
    }
    val m = ad ?: return
    LaunchedEffect(m.messageId) {
        runCatching { Telegram.send(TdApi.ViewMessages(chatId, longArrayOf(m.messageId), TdApi.MessageSourceChatHistory(), false)) }
    }
    val openLink = rememberAdLinkOpener()
    val scope = rememberCoroutineScope()
    val text = adText(m.content)
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Cinema.Panel)
            .border(1.dp, Cinema.Line, MaterialTheme.shapes.medium)
            .clickable {
                scope.launch { runCatching { Telegram.send(TdApi.ClickChatSponsoredMessage(chatId, m.messageId, false, false)) } }
                openLink(m.sponsor.url)
            }
            .padding(start = 14.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (m.isRecommended) "Recommended" else "Sponsored",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Cinema.AccentText,
            )
            AdMenu(m.sponsor.info, m.additionalInfo, m.canBeReported, Cinema.TextDim, { Telegram.send(TdApi.ReportChatSponsoredMessage(chatId, m.messageId, it)) }) {
                ad = null
            }
        }
        Column(Modifier.padding(end = 14.dp)) {
            if (m.title.isNotEmpty()) Text(m.title, style = MaterialTheme.typography.titleSmall)
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.buttonText.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Text(
                    m.buttonText,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Cinema.PanelHigh).padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = Cinema.AccentText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun adText(content: TdApi.MessageContent?): String = when (content) {
    is TdApi.MessageText -> content.text.text
    is TdApi.MessagePhoto -> content.caption.text
    is TdApi.MessageVideo -> content.caption.text
    is TdApi.MessageAnimation -> content.caption.text
    else -> ""
}

// ─── Player ───────────────────────────────────────────────────────────────────

/**
 * Telegram's text ads in a small box on the player while a channel video plays. They come in order, each for
 * maxDisplayDuration seconds and closable after minDisplayDuration. The first shows after startDelay seconds,
 * and each next one betweenDelay seconds after the last one hid. Every timer counts playback time only, so
 * pausing ([playing] false) pauses them all. Keyed on the channel, not the video, as the rules ask.
 */
@Composable
fun VideoAdBox(chatId: Long, messageId: Long, playing: () -> Boolean, visible: Boolean, modifier: Modifier = Modifier) {
    val isPlaying by rememberUpdatedState(playing)
    var ad by remember(chatId) { mutableStateOf<TdApi.VideoMessageAdvertisement?>(null) }
    var shownSec by remember(chatId) { mutableIntStateOf(0) }
    var closed by remember(chatId) { mutableStateOf(false) }
    var allHidden by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(chatId) {
        /** Waits for [seconds] of playback, or until [stop]. */
        suspend fun play(seconds: Int, stop: () -> Boolean = { false }, onSecond: () -> Unit = {}) {
            var ms = 0
            while (ms < seconds * 1_000 && !stop()) {
                delay(250)
                if (isPlaying()) {
                    ms += 250
                    if (ms % 1_000 == 0) onSecond()
                }
            }
        }
        val canGet = runCatching { Telegram.send(TdApi.GetMessageProperties(chatId, messageId)).canGetVideoAdvertisements }.getOrDefault(false)
        if (!canGet) return@LaunchedEffect
        val ads = runCatching { Telegram.send(TdApi.GetVideoMessageAdvertisements(chatId, messageId)) }.getOrNull() ?: return@LaunchedEffect
        var wait = ads.startDelay
        for (a in ads.advertisements) {
            play(wait, { allHidden })
            if (allHidden) break
            shownSec = 0
            closed = false
            ad = a
            runCatching { Telegram.send(TdApi.ViewVideoMessageAdvertisement(a.uniqueId)) }
            play(a.maxDisplayDuration, { closed || allHidden }) { shownSec++ }
            ad = null
            wait = ads.betweenDelay
        }
    }
    val a = ad?.takeIf { visible } ?: return
    val openLink = rememberAdLinkOpener()
    val scope = rememberCoroutineScope()
    Row(
        modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable {
                scope.launch { runCatching { Telegram.send(TdApi.ClickVideoMessageAdvertisement(a.uniqueId)) } }
                openLink(a.sponsor.url)
            }
            .padding(start = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f, fill = false).padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ad", Modifier.clip(RoundedCornerShape(4.dp)).background(Cinema.Accent).padding(horizontal = 5.dp),
                    style = MaterialTheme.typography.labelSmall, color = Cinema.OnAccent, fontWeight = FontWeight.Bold,
                )
                if (a.title.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(a.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (a.text.isNotEmpty()) Text(a.text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        AdMenu(a.sponsor.info, a.additionalInfo, a.canBeReported, Color.White.copy(alpha = 0.8f), { Telegram.send(TdApi.ReportVideoMessageAdvertisement(a.uniqueId, it)) }) { all ->
            closed = true
            if (all) allHidden = true
        }
        // Closable only after its minimum time on screen.
        if (shownSec >= a.minDisplayDuration) {
            IconButton(onClick = { closed = true }, Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Hide ad", Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.8f))
            }
        } else Spacer(Modifier.size(8.dp))
    }
}

// ─── Shared ───────────────────────────────────────────────────────────────────

/** Telegram's own domains open without asking; the rules require a confirmation for any other ad link. */
private val telegramHost = Regex("""^(?:[\w-]+\.)*(?:telegram\.(?:org|me|dog)|t\.me|te\.legra\.ph|graph\.org|fragment\.com|telesco\.pe)$""", RegexOption.IGNORE_CASE)

private fun isTelegramLink(url: String): Boolean {
    val uri = Uri.parse(url)
    return uri.scheme == "tg" || telegramHost.matches(uri.host.orEmpty())
}

/** Opens ad links: Telegram links at once, any other site after "Open this link?". */
@Composable
private fun rememberAdLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    pending?.let { url ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Open this link?") },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = { pending = null; openUrl(context, url) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
            containerColor = Cinema.PanelHigh,
        )
    }
    return { url ->
        when {
            url.isBlank() -> {}
            isTelegramLink(url) -> openUrl(context, url)
            else -> pending = url
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

/**
 * The ad's ⋮ menu: "Sponsor info" when Telegram gave any, and "Report ad" when it can be reported.
 * [onHidden] gets true when the report hid all ads for this user.
 */
@Composable
private fun AdMenu(
    sponsorInfo: String,
    additionalInfo: String,
    canReport: Boolean,
    tint: Color,
    report: suspend (ByteArray) -> TdApi.ReportSponsoredResult,
    onHidden: (all: Boolean) -> Unit,
) {
    val info = listOf(sponsorInfo, additionalInfo).filter { it.isNotBlank() }.joinToString("\n\n")
    if (info.isEmpty() && !canReport) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<TdApi.ReportSponsoredResultOptionRequired?>(null) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun send(option: ByteArray) {
        scope.launch {
            when (val r = runCatching { report(option) }.getOrNull()) {
                is TdApi.ReportSponsoredResultOptionRequired -> choose = r
                is TdApi.ReportSponsoredResultOk -> { toast("Ad reported"); onHidden(false) }
                is TdApi.ReportSponsoredResultAdsHidden -> { toast("Ads hidden"); onHidden(true) }
                is TdApi.ReportSponsoredResultPremiumRequired -> toast("Hiding ads needs Telegram Premium")
                else -> toast("Couldn't report this ad")
            }
        }
    }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, "Ad options", Modifier.size(18.dp), tint = tint)
        }
        DropdownMenu(open, { open = false }, containerColor = Cinema.PanelHigh) {
            if (info.isNotEmpty()) DropdownMenuItem(text = { Text("Sponsor info") }, onClick = { open = false; showInfo = true })
            if (canReport) DropdownMenuItem(text = { Text("Report ad") }, onClick = { open = false; send(ByteArray(0)) })
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Sponsor info") },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
            containerColor = Cinema.PanelHigh,
        )
    }
    choose?.let { c ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text(c.title.ifEmpty { "Report ad" }) },
            text = {
                Column {
                    c.options.forEach { o ->
                        Text(
                            o.text,
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { choose = null; send(o.id) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = null }) { Text("Cancel") } },
            containerColor = Cinema.PanelHigh,
        )
    }
}
