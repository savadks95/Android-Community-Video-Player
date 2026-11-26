package app.tgplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tgplayer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Brand intro shown each time the app starts: the mark springs in on a violet glow, the name and
 * tagline track in beneath it, then everything lifts and fades into the app. A tap skips it.
 */
@Composable
fun LaunchIntro(onDone: () -> Unit) {
    val mark = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, tween(700, easing = EaseOutBack)) }
        delay(380)
        name.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        delay(650)
        exit.animateTo(1f, tween(420, easing = FastOutLinearInEasing))
        onDone()
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Cinema.Stage)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.graphicsLayer { val s = 1f + 0.12f * exit.value; scaleX = s; scaleY = s },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        val s = 0.35f + 0.65f * mark.value // EaseOutBack overshoots past 1 for a small bounce
                        scaleX = s; scaleY = s
                        alpha = mark.value.coerceIn(0f, 1f)
                    }
                    .drawBehind {
                        val r = size.minDimension * 1.5f
                        drawCircle(Brush.radialGradient(listOf(Cinema.AccentFill.copy(alpha = 0.55f), Color.Transparent), center, r), r)
                    },
            ) { LogoMark(96.dp) }
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(R.string.app_name).uppercase(),
                Modifier.graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * 14.dp.toPx()
                },
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (4f + 14f * (1f - name.value)).sp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.tagline),
                Modifier.graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * 14.dp.toPx()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Cinema.TextDim,
            )
        }
    }
}
