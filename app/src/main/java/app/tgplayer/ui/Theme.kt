package app.tgplayer.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tgplayer.R

/**
 * "Ambient cinema": a screening room. Velvet-violet dark (a video app is watched with the lights low), one warm
 * projector amber for whatever acts or is selected, and the video's own picture as the room's light (see
 * [AmbientGlow]). Dark only.
 */
object Cinema {
    /** Velvet: the room. */
    val Stage = Color(0xFF14111D)
    /** Seat: raised panels, idle chips. */
    val Panel = Color(0xFF1D1929)
    /** Balcony: tiles' empty frames, focused fields. */
    val PanelHigh = Color(0xFF282339)
    /** Seam: hairlines and tracks. */
    val Line = Color(0xFF3A3450)
    /** Projector amber: fills for progress, switches, the selected tab, the main button. Text on it is [OnAccent]. */
    val AccentFill = Color(0xFFF4B740)
    /** Marquee: amber for text and icons on the dark room. */
    val AccentText = Color(0xFFFFD27A)
    /** Ink: text and icons on an [AccentFill] surface (about 10:1). */
    val OnAccent = Color(0xFF1B1405)
    val Danger = Color(0xFFFF6B6B)
    val Text = Color(0xFFF3EFF8)
    val TextDim = Color(0xFFA69FBA)
    /** Ring around the focused or pressed item. */
    val Focus = AccentFill

    val Accent: Brush = SolidColor(AccentFill)
    val AccentWarm = Brush.linearGradient(listOf(Color(0xFF4A3466), Color(0xFF2A2140)))
    /** Bottom scrim so titles stay readable over any thumbnail. */
    val Scrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD90A0810))
}

private val colors = darkColorScheme(
    primary = Cinema.AccentFill,
    onPrimary = Cinema.OnAccent,
    secondary = Cinema.AccentText,
    onSecondary = Color.Black,
    tertiary = Cinema.Danger,
    background = Cinema.Stage,
    onBackground = Cinema.Text,
    surface = Cinema.Stage,
    onSurface = Cinema.Text,
    surfaceVariant = Cinema.PanelHigh,
    onSurfaceVariant = Cinema.TextDim,
    surfaceContainerLowest = Cinema.Stage,
    surfaceContainerLow = Cinema.Panel,
    surfaceContainer = Cinema.Panel,
    surfaceContainerHigh = Cinema.PanelHigh,
    surfaceContainerHighest = Cinema.PanelHigh,
    outline = Cinema.Line,
    outlineVariant = Cinema.Line,
    error = Cinema.Danger,
)

/** Montserrat (OFL): a wide geometric sans that holds up as heavy title-card type and as small labels. */
val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

private val base = Typography()
private fun TextStyle.m(weight: FontWeight, spacing: Float = 0f) = copy(fontFamily = Montserrat, fontWeight = weight, letterSpacing = spacing.sp)
private val typography = Typography(
    displayLarge = base.displayLarge.m(FontWeight.Bold, -1f),
    displayMedium = base.displayMedium.m(FontWeight.Bold, -0.8f),
    displaySmall = base.displaySmall.m(FontWeight.Bold, -0.6f),
    // Screen titles and the hero's video title are the loudest type: heavy and tight, like a film's title card.
    headlineLarge = base.headlineLarge.m(FontWeight.ExtraBold, -0.9f),
    headlineMedium = base.headlineMedium.m(FontWeight.ExtraBold, -0.6f),
    headlineSmall = base.headlineSmall.m(FontWeight.Bold, -0.3f),
    titleLarge = base.titleLarge.m(FontWeight.Bold, -0.2f),
    // Section titles ("Continue watching", a channel's name).
    titleMedium = base.titleMedium.m(FontWeight.Bold, -0.1f),
    titleSmall = base.titleSmall.m(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.m(FontWeight.Normal),
    bodyMedium = base.bodyMedium.m(FontWeight.Normal),
    bodySmall = base.bodySmall.m(FontWeight.Medium),
    labelLarge = base.labelLarge.m(FontWeight.Bold, 0.2f),
    labelMedium = base.labelMedium.m(FontWeight.Bold, 0.3f),
    labelSmall = base.labelSmall.m(FontWeight.Bold, 0.4f),
)

/** Small sentence-case labels: stat names, "See all", tab captions, eyebrows. */
val CaptionStyle = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.1.sp)

/** Soft corners that grow with the surface: chips < tiles < cards < sheets. Buttons, tabs and the nav are pills. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun CinemaTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        // No root Surface in this app, so set the default text/icon colour here.
        CompositionLocalProvider(LocalContentColor provides Cinema.Text, content = content)
    }

/**
 * Edge-to-edge with light status/navigation bar icons. The app is always dark, but the default
 * ("auto") follows the phone's theme and draws dark icons on a light-themed phone (e.g. MIUI).
 */
fun ComponentActivity.enableCinemaEdgeToEdge() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
)
