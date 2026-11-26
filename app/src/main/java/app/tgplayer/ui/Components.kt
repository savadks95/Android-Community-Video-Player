package app.tgplayer.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tgplayer.data.ChatItem
import app.tgplayer.data.ChatKind
import app.tgplayer.data.MediaItem
import app.tgplayer.data.MediaKind
import kotlin.math.absoluteValue

/** Poster-style media tile: 16:9 thumbnail, scrim, duration pill, resume bar; title and source below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    item: MediaItem,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = item.chatTitle,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(Cinema.PanelHigh),
        ) {
            TdImage(item.thumb, item.miniThumb, Modifier.fillMaxSize()) { GradientPlaceholder(item.title, item.kind) }
            Box(Modifier.fillMaxSize().background(Cinema.Scrim))
            if (item.kind == MediaKind.AUDIO) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.align(Alignment.Center).size(34.dp), tint = Color.White.copy(alpha = 0.85f))
            }
            Pill(
                text = if (item.durationSec > 0) formatDuration(item.durationSec) else formatSize(item.sizeBytes),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Cinema.Accent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** When there's no thumbnail: a colour field picked from the title, with a big glyph. */
@Composable
fun GradientPlaceholder(seed: String, kind: MediaKind) {
    Box(Modifier.fillMaxSize().background(paletteFor(seed)), contentAlignment = Alignment.Center) {
        Icon(
            if (kind == MediaKind.AUDIO) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
            null,
            Modifier.size(40.dp),
            tint = Color.White.copy(alpha = 0.35f),
        )
    }
}

/** App mark: a disc of projector amber with an ink play glyph. */
@Composable
fun LogoMark(size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Cinema.AccentFill), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(size * 0.62f), tint = Cinema.OnAccent)
    }
}

/** Muted screening-room pairs (plum, velvet, teal, ember, dusk, moss) so placeholders sit quietly on the velvet. */
private val palettes = listOf(
    listOf(Color(0xFF4A2B55), Color(0xFF1B1224)),
    listOf(Color(0xFF33304F), Color(0xFF16131F)),
    listOf(Color(0xFF1D4A4E), Color(0xFF0F1A20)),
    listOf(Color(0xFF5A3524), Color(0xFF1C1412)),
    listOf(Color(0xFF3A2E63), Color(0xFF141026)),
    listOf(Color(0xFF2F4733), Color(0xFF111912)),
)

/** A stable gradient per title/name, so items without artwork still look distinct. */
fun paletteFor(seed: String): Brush = Brush.linearGradient(palettes[seed.hashCode().absoluteValue % palettes.size])

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

@Composable
fun Avatar(chat: ChatItem, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(paletteFor(chat.title)), contentAlignment = Alignment.Center) {
        Text(
            chat.title.trim().take(1).uppercase(),
            style = if (size > 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        TdImage(chat.photo, chat.miniThumb, Modifier.fillMaxSize())
    }
}

@Composable
fun ChatRow(chat: ChatItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(chat.kind), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(chat.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun kindIcon(kind: ChatKind): ImageVector = when (kind) {
    ChatKind.CHANNEL -> Icons.Rounded.Campaign
    ChatKind.GROUP -> Icons.Rounded.Groups
    ChatKind.PERSON -> Icons.Rounded.Person
}

/** Category chips: the selected one is filled with projector amber, the rest sit on the seat colour. */
@Composable
fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { option ->
            val isSelected = option == selected
            val textColor by animateColorAsState(if (isSelected) Cinema.OnAccent else Cinema.TextDim, label = "tab")
            val fill by animateColorAsState(if (isSelected) Cinema.AccentFill else Cinema.Panel, label = "fill")
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(fill)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(label(option), style = CaptionStyle.copy(fontSize = 13.sp), color = textColor)
            }
        }
    }
}

/** A group's heading, like "Continue watching" or a channel's name. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, color = Cinema.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A big number with small units under a dim label ("Watched / 1h 23m"): [parts] are number to unit. */
@Composable
fun Stat(label: String, vararg parts: Pair<String, String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = CaptionStyle, color = Cinema.TextDim, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            buildAnnotatedString {
                parts.forEachIndexed { i, (number, unit) ->
                    if (i > 0) append(" ")
                    append(number)
                    if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cinema.TextDim)) { append(unit) }
                }
            },
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
        )
    }
}

/** 83 min -> 1H 23M as [Stat] parts. */
fun durationParts(ms: Long): Array<Pair<String, String>> {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> arrayOf("$h" to "h", "$m" to "m")
        else -> arrayOf("$m" to "m")
    }
}

/** "Watch progress ··· 23%" over a rounded amber bar. */
@Composable
fun LabeledProgress(label: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = CaptionStyle, color = Cinema.TextDim)
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = Cinema.AccentText)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Cinema.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(Cinema.AccentFill))
        }
    }
}

/**
 * The hero. Narrow (a phone upright): [art] across the top melting into its own [AmbientGlow], which lights
 * the whole hero; [top] over its corner, [headline] over its foot, then [body] in the glow. Wide (landscape,
 * tablets): [top], [headline] and [body] in a left column with [art] filling the right and fading in from the left.
 */
@Composable
fun ConsoleHero(
    art: @Composable () -> Unit,
    top: @Composable () -> Unit,
    headline: @Composable ColumnScope.() -> Unit,
    narrowAspect: Float = 16f / 11f,
    body: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 600.dp) {
            Box {
                AmbientGlow(art, Modifier.matchParentSize())
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(narrowAspect)) {
                        Box(Modifier.fillMaxSize().fadeOutBelow(0.35f)) {
                            art()
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent)))
                        }
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) { top() }
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), content = headline)
                    }
                    Column(Modifier.padding(horizontal = 20.dp), content = body)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.6f)) {
                        art()
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Cinema.Stage, 0.45f to Color.Transparent)))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Cinema.Stage)))
                    }
                }
                Column(Modifier.fillMaxWidth(0.5f).padding(start = 24.dp, top = 14.dp, bottom = 8.dp)) {
                    top()
                    Spacer(Modifier.height(28.dp))
                    headline()
                    body()
                }
            }
        }
    }
}

/**
 * The design's one bold move, after YouTube's Ambient Mode: the video's own picture lights the room. A heavily
 * blurred, enlarged copy of [art], dimmed by the velvet so text on it stays readable, deepening to the plain
 * stage at its foot. Blur needs Android 12; older phones get the dim velvet wash alone.
 */
@Composable
fun AmbientGlow(art: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds()) {
        if (Build.VERSION.SDK_INT >= 31) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                    .blur(64.dp, BlurredEdgeTreatment.Rectangle),
            ) { art() }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Cinema.Stage.copy(alpha = 0.30f),
                    0.5f to Cinema.Stage.copy(alpha = 0.55f),
                    0.85f to Cinema.Stage.copy(alpha = 0.85f),
                    1f to Cinema.Stage,
                ),
            ),
        )
    }
}

/** Fades this content to transparent from [from] (fraction of its height) to its foot, showing what lies behind. */
private fun Modifier.fadeOutBelow(from: Float) =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(from to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }

/** Selection: a projector-amber ring a little outside the item. */
fun Modifier.focusRing(on: Boolean, shape: Shape = RoundedCornerShape(13.dp)) =
    if (on) border(2.dp, Cinema.Focus, shape).padding(3.dp) else padding(3.dp)

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Telegram dates are Unix seconds; 0 means unknown. */
fun formatDate(unixSeconds: Int): String =
    if (unixSeconds <= 0) "" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(unixSeconds * 1000L))

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> ""
}
