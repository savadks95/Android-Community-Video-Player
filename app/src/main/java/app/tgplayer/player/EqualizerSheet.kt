package app.tgplayer.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tgplayer.ui.CaptionStyle
import app.tgplayer.ui.Cinema
import app.tgplayer.ui.PillTabs
import kotlin.math.pow
import kotlin.math.roundToInt

/** The equalizer: a live response curve, presets, one slider per band and a preamp. */
@Composable
internal fun EqualizerSheet() {
    var eq by remember { mutableStateOf(AudioFx.eq) }
    // Touching any control switches the equalizer on.
    fun set(value: AudioFx.Eq, save: Boolean = true) {
        eq = value
        AudioFx.setEq(value, save)
    }
    val custom = "Custom"
    val preset = AudioFx.presetName(eq.gains) ?: custom

    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                Text("Works with every audio format and decoder", style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
            }
            Switch(eq.enabled, { set(eq.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = Cinema.AccentFill))
        }
    }
    val presets: @Composable () -> Unit = {
        Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState())) {
            val names = AudioFx.presets.map { it.first } + if (preset == custom) listOf(custom) else emptyList()
            PillTabs(names, preset, { it }, { name ->
                AudioFx.presets.firstOrNull { it.first == name }?.let { set(eq.copy(enabled = true, gains = it.second.copyOf())) }
            })
        }
    }
    val bands: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier) {
            AudioFx.BANDS.forEachIndexed { i, freq ->
                BandSlider(
                    label = if (freq >= 1000) "${(freq / 1000).toInt()}k" else freq.toInt().toString(),
                    db = eq.gains[i],
                    lit = eq.enabled,
                    onChange = { db -> set(eq.copy(enabled = true, gains = eq.gains.copyOf().also { it[i] = db }), save = false) },
                    onDone = { AudioFx.setEq(eq) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
    val preamp: @Composable () -> Unit = { Preamp(eq, onChange = { set(it, save = false) }, onDone = { AudioFx.setEq(eq) }, onReset = { set(it) }) }

    BoxWithConstraints(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        if (maxWidth >= 560.dp) {
            // Landscape and tablets: settings beside the bands, so everything fits without scrolling.
            Row {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    header()
                    ResponseCurve(eq, Modifier.fillMaxWidth().height(84.dp).padding(top = 12.dp))
                    presets()
                    preamp()
                }
                Spacer(Modifier.width(24.dp))
                bands(Modifier.weight(1f).height(270.dp))
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                header()
                // The curve's band points sit right above the sliders.
                ResponseCurve(eq, Modifier.fillMaxWidth().height(120.dp).padding(top = 16.dp))
                presets()
                bands(Modifier.fillMaxWidth().height(230.dp).padding(top = 18.dp))
                preamp()
            }
        }
    }
}

@Composable
private fun Preamp(eq: AudioFx.Eq, onChange: (AudioFx.Eq) -> Unit, onDone: () -> Unit, onReset: (AudioFx.Eq) -> Unit) {
    Column {
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preamp", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(dbLabel(eq.preampDb, decimals = true), style = MaterialTheme.typography.labelLarge, color = Cinema.AccentText)
        }
        Slider(
            eq.preampDb,
            { onChange(eq.copy(enabled = true, preampDb = (it * 2).roundToInt() / 2f)) },
            valueRange = -AudioFx.MAX_DB..6f,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Cinema.AccentFill, activeTrackColor = Cinema.AccentFill, inactiveTrackColor = Cinema.Line),
        )
        Text(
            "Lower it if strong boosts sound harsh; loud peaks are softened instead of clipping.",
            style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
        )
        if (AudioFx.presetName(eq.gains) != "Flat" || eq.preampDb != 0f) {
            TextButton(onClick = { onReset(eq.copy(gains = FloatArray(AudioFx.BANDS.size), preampDb = 0f)) }, Modifier.padding(top = 6.dp)) {
                Text("Reset to flat", color = Cinema.AccentText)
            }
        }
    }
}

private fun dbLabel(db: Float, decimals: Boolean = false): String = when {
    db == 0f -> "0 dB"
    decimals || db % 1f != 0f -> "%+.1f dB".format(db)
    else -> "%+d".format(db.toInt())
}

/**
 * The equalizer's real frequency response (bands add up where they overlap). The x axis is log
 * frequency, laid out so each band's centre sits right above its slider.
 */
@Composable
private fun ResponseCurve(eq: AudioFx.Eq, modifier: Modifier) {
    val accent = if (eq.enabled) Cinema.AccentFill else Cinema.TextDim
    val grid = Cinema.Line
    Canvas(modifier) {
        val range = 15f // dB from the middle to the top edge
        fun y(db: Double) = (size.height / 2 * (1 - db / range)).toFloat().coerceIn(0f, size.height)
        // Slider i is centred at (i + 0.5) / n of the width: half an octave of margin each side.
        val n = AudioFx.BANDS.size
        val lowest = AudioFx.BANDS[0] / 1.4142135f
        fun freqAt(x: Float) = lowest * 2.0.pow((x / size.width * n).toDouble())

        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (db in listOf(-12.0, -6.0, 6.0, 12.0)) drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1f, pathEffect = dash)
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f)

        val steps = 96
        val line = Path()
        for (s in 0..steps) {
            val x = size.width * s / steps
            val py = y(EqualizerProcessor.responseDb(eq, freqAt(x)))
            if (s == 0) line.moveTo(x, py) else line.lineTo(x, py)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, y(0.0)); lineTo(0f, y(0.0)); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.04f))))
        drawPath(line, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        for (i in 0 until n) {
            val x = size.width * (i + 0.5f) / n
            drawCircle(accent, 3.5.dp.toPx(), Offset(x, y(EqualizerProcessor.responseDb(eq, freqAt(x)))))
        }
    }
}

/**
 * A vertical slider for one band, ±[AudioFx.MAX_DB] in 0.5 dB steps. Drag or tap to set it; with a remote
 * or keyboard, up and down change it while left and right still move between bands.
 */
@Composable
private fun BandSlider(label: String, db: Float, lit: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val value by rememberUpdatedState(db)
    var focused by remember { mutableStateOf(false) }
    val accent = if (lit) Cinema.AccentFill else Cinema.TextDim
    val max = AudioFx.MAX_DB

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dbLabel(db).removeSuffix(" dB"), style = CaptionStyle, color = if (db == 0f) Cinema.TextDim else Cinema.AccentText, textAlign = TextAlign.Center)
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)
                .semantics {
                    contentDescription = "$label hertz"
                    stateDescription = dbLabel(db, decimals = true)
                    progressBarRangeInfo = ProgressBarRangeInfo(db, -max..max, steps = (max * 4).toInt() - 1)
                    setProgress { v -> change((v.coerceIn(-max, max) * 2).roundToInt() / 2f); done(); true }
                }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (it.key) { Key.DirectionUp -> 1f; Key.DirectionDown -> -1f; else -> return@onKeyEvent false }
                    change((value + step).coerceIn(-max, max)); done(); true
                }
                .focusable()
                .pointerInput(Unit) {
                    fun dbAt(y: Float) = ((max - y / size.height * 2 * max).coerceIn(-max, max) * 2).roundToInt() / 2f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume() // keep the sheet from scrolling or closing while a band is dragged
                        change(dbAt(down.position.y))
                        while (true) {
                            val event = awaitPointerEvent()
                            val p = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!p.pressed) break
                            p.consume()
                            change(dbAt(p.position.y))
                        }
                        done()
                    }
                },
        ) {
            val cx = size.width / 2
            fun y(v: Float) = size.height / 2 * (1 - v / max)
            val track = 4.dp.toPx()
            drawLine(Cinema.Line, Offset(cx, 0f), Offset(cx, size.height), track, StrokeCap.Round)
            drawLine(Cinema.TextDim, Offset(cx - 6.dp.toPx(), y(0f)), Offset(cx + 6.dp.toPx(), y(0f)), 1.dp.toPx())
            drawLine(accent, Offset(cx, y(0f)), Offset(cx, y(db)), track, StrokeCap.Round)
            if (focused) drawCircle(Color.White.copy(alpha = 0.25f), 15.dp.toPx(), Offset(cx, y(db)))
            drawCircle(accent, 9.dp.toPx(), Offset(cx, y(db)))
            drawCircle(Cinema.Panel, 3.dp.toPx(), Offset(cx, y(db)))
        }
        Text(label, style = CaptionStyle, color = Cinema.TextDim, textAlign = TextAlign.Center)
    }
}
