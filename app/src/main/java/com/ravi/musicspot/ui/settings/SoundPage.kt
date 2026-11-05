package com.ravi.musicspot.ui.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.AutoEq
import com.ravi.musicspot.data.EqPresets
import com.ravi.musicspot.data.EqSetting
import com.ravi.musicspot.data.ParamBandSetting
import com.ravi.musicspot.dsp.EnhanceLevel
import com.ravi.musicspot.dsp.EqConfig
import com.ravi.musicspot.dsp.FilterType
import com.ravi.musicspot.dsp.responseDb
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.ui.Pills
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private fun updEq(f: (EqSetting) -> EqSetting) = Graph.settings.update { it.copy(eq = f(it.eq)) }
private fun hz(f: Double) = if (f >= 1000) (if (f % 1000 == 0.0) "${(f / 1000).toInt()}k" else "%.1fk".format(f / 1000)) else "${f.roundToInt()}"
private fun db(v: Double) = "%+.1f dB".format(v)

/** Output rates the Upscale stage may target: those every current output reports (an empty list means "any"). */
private fun supportedRates(ctx: Context): List<Int> {
    val wanted = listOf(48_000, 96_000, 192_000)
    val am = ctx.getSystemService(AudioManager::class.java) ?: return wanted
    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
    if (outs.isEmpty() || outs.any { it.sampleRates.isEmpty() }) return wanted
    val rates = outs.flatMap { it.sampleRates.toList() }.toSet()
    return wanted.filter { it in rates }.ifEmpty { listOf(48_000) }
}

@Composable
fun SoundPage() {
    val s by Graph.settings.state.collectAsState()
    val status by EngineAudioProcessor.status.collectAsState()
    val ctx = LocalContext.current
    val rates = remember { supportedRates(ctx) }
    val eq = s.eq
    val pure = s.pureHiRes || s.bitPerfectUsb
    var autoEqOpen by remember { mutableStateOf(false) }

    SettingsScaffold("Sound") {
        item {
            Group {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(when {
                        pure -> "Bypassed: pure hi-res / bit-perfect output is on (Settings › Playback)"
                        !eq.enabled -> "Engine idle — EQ off"
                        eq.bypassB -> "B: bypass (bit-exact pass-through)"
                        else -> listOfNotNull(if (status.eqActive) "EQ active" else "EQ flat", status.badge,
                            status.detectedCutoffHz?.let { "source cutoff ~${it / 1000} kHz" },
                            if (status.limiterDb < -0.1) "limiter %.1f dB".format(status.limiterDb) else null).joinToString(" · ")
                    }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (status.disabledForCpu) Text("Enhance paused: this device couldn't keep up in real time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    ResponseCurve(eq, Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp))
                }
                SwitchRow("Equalizer", "Software EQ inside the player (float, no clicks on changes)", eq.enabled, enabled = !pure) { v -> updEq { it.copy(enabled = v) } }
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("A/B compare", style = MaterialTheme.typography.titleSmall)
                        Text("B passes audio through bit-exact", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = !eq.bypassB, onClick = { updEq { it.copy(bypassB = false) } }, shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = eq.enabled && !pure) { Text("A") }
                        SegmentedButton(selected = eq.bypassB, onClick = { updEq { it.copy(bypassB = true) } }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = eq.enabled && !pure) { Text("B") }
                    }
                }
            }
        }

        item {
            Group("Presets") {
                ChoiceRow(null, EqPresets.graphic.keys.toList(), eq.preset, { it }) { name ->
                    updEq { it.copy(graphic = EqPresets.graphic.getValue(name), preset = name, parametricOn = if (it.preset.startsWith("AutoEq")) false else it.parametricOn) }
                }
                RowDivider()
                NavRowLike(Icons.Rounded.Headphones, "AutoEq headphone correction",
                    if (eq.preset.startsWith("AutoEq")) eq.preset.removePrefix("AutoEq · ") else "Search 8,000+ measured headphones") { autoEqOpen = !autoEqOpen }
            }
        }
        if (autoEqOpen) item { AutoEqSearch { autoEqOpen = false } }

        item {
            Group("10-band graphic") {
                EqConfig.GRAPHIC_FREQS.forEachIndexed { i, f ->
                    SliderRow("${hz(f)} Hz", eq.graphic.getOrElse(i) { 0.0 }.toFloat(), -12f..12f, db(eq.graphic.getOrElse(i) { 0.0 }), enabled = eq.enabled && !pure) { v ->
                        val r = (v * 2).roundToInt() / 2.0
                        updEq { e -> e.copy(graphic = e.graphic.toMutableList().also { l -> while (l.size < 10) l.add(0.0); l[i] = r }, preset = "Custom") }
                    }
                }
                TextButton(onClick = { updEq { it.copy(graphic = List(10) { 0.0 }, preset = "Flat") } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reset to flat") }
            }
        }

        item {
            Group("Parametric") {
                SwitchRow("Parametric bands", "Peak, shelf and pass filters with frequency, gain and Q (added on top of the graphic EQ)", eq.parametricOn, enabled = eq.enabled && !pure) { v -> updEq { it.copy(parametricOn = v) } }
                if (eq.parametricOn) {
                    eq.parametric.forEachIndexed { i, b ->
                        RowDivider()
                        ParamBandEditor(i, b, onChange = { nb -> updEq { e -> e.copy(parametric = e.parametric.toMutableList().also { it[i] = nb }, preset = if (e.preset.startsWith("AutoEq")) e.preset else "Custom") } },
                            onRemove = { updEq { e -> e.copy(parametric = e.parametric.filterIndexed { j, _ -> j != i }) } })
                    }
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { updEq { e -> e.copy(parametric = e.parametric + ParamBandSetting(FilterType.PEAK, 1000.0, 0.0, 1.0)) } }, enabled = eq.parametric.size < 16) {
                            Icon(Icons.Rounded.Add, null); Text("Add band")
                        }
                        TextButton(onClick = { updEq { e -> e.copy(parametric = EqSetting.DEFAULT_PARAMS) } }) { Text("Reset bands") }
                    }
                }
            }
        }

        item {
            Group("Gain & space") {
                SliderRow("Preamp", eq.preampDb.toFloat(), -12f..6f, db(eq.preampDb), enabled = eq.enabled && !pure) { v -> updEq { it.copy(preampDb = (v * 2).roundToInt() / 2.0) } }
                SwitchRow("Automatic headroom", "Lowers the preamp by the curve's highest boost so nothing clips", eq.autoHeadroom, enabled = eq.enabled && !pure) { v -> updEq { it.copy(autoHeadroom = v) } }
                RowDivider()
                SliderRow("Bass boost", eq.bassBoostDb.toFloat(), 0f..12f, if (eq.bassBoostDb == 0.0) "Off" else db(eq.bassBoostDb), enabled = eq.enabled && !pure,
                    subtitle = "Low shelf at 90 Hz") { v -> updEq { it.copy(bassBoostDb = (v * 2).roundToInt() / 2.0) } }
                SliderRow("Stereo widener", eq.widen.toFloat(), 0f..1f, if (eq.widen == 0.0) "Off" else "${(eq.widen * 100).roundToInt()}%", enabled = eq.enabled && !pure,
                    subtitle = "Mild mid/side widening") { v -> updEq { it.copy(widen = (v * 20).roundToInt() / 20.0) } }
                RowDivider()
                SwitchRow("Peak limiter", "Last stage; catches any overs", eq.limiter, enabled = !pure) { v -> updEq { it.copy(limiter = v) } }
            }
        }

        item {
            Group("Enhance / Upscale", footer = buildString {
                append("Enhance adds a gentle harmonic exciter above the cutoff a lossy file lost; it can't recover the original, so it's never labelled lossless. ")
                append("Upscale resamples with a high-quality polyphase filter; a new rate starts with the next song. ")
                if (Graph.lowEnd) append("This device reports low memory, so Enhance and Upscale stay off.")
            }) {
                ChoiceRow("Bandwidth extension", EnhanceLevel.entries, s.bwe, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, enabled = !pure && !Graph.lowEnd) { v -> Graph.settings.update { it.copy(bwe = v) } }
                RowDivider()
                ChoiceRow("Upscale target", listOf(0) + rates, s.upscaleRate, { if (it == 0) "Off" else "${it / 1000} kHz" }, enabled = !pure && !Graph.lowEnd,
                    subtitle = "Only rates your current output reports are offered") { v -> Graph.settings.update { it.copy(upscaleRate = v) } }
                RowDivider()
                SwitchRow("Transient restore", "Subtly re-sharpens attacks softened by lossy encoding", s.transientRestore, enabled = !pure && !Graph.lowEnd) { v -> Graph.settings.update { it.copy(transientRestore = v) } }
            }
        }
    }
}

@Composable
private fun NavRowLike(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) = NavRow(icon, title, subtitle, onClick)

@Composable
private fun ResponseCurve(eq: EqSetting, modifier: Modifier) {
    val cfg = remember(eq) { eq.toConfig().copy(bypass = false, enabled = true) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val points = remember(cfg) { (0..120).map { i -> val f = 20.0 * 1000.0.pow(i / 120.0); cfg.responseDb(f) + (if (cfg.preampDb != 0.0) cfg.preampDb else 0.0) } }
    Canvas(modifier.semantics { contentDescription = "Frequency response curve" }) {
        val range = 15f
        fun y(dbv: Double) = (size.height / 2f - (dbv.toFloat().coerceIn(-range, range) / range) * (size.height / 2f))
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        listOf(100.0, 1000.0, 10000.0).forEach { f -> val x = (ln(f / 20.0) / ln(1000.0)).toFloat() * size.width; drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f) }
        val p = Path()
        points.forEachIndexed { i, v -> val x = i / 120f * size.width; if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
        drawPath(p, line, style = Stroke(width = 3f))
    }
}

@Composable
private fun ParamBandEditor(i: Int, b: ParamBandSetting, onChange: (ParamBandSetting) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Band ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
            Text(typeLabel(b.type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).clickable(onClickLabel = "Change filter type") {
                    val all = FilterType.entries; onChange(b.copy(type = all[(all.indexOf(b.type) + 1) % all.size]))
                }.padding(vertical = 8.dp))
            Switch(checked = b.enabled, onCheckedChange = { onChange(b.copy(enabled = it)) })
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove band ${i + 1}") }
        }
        val t = (ln(b.freqHz / 20.0) / ln(1000.0)).toFloat()
        SliderRow("Frequency", t, 0f..1f, "${hz(b.freqHz)} Hz") { v -> onChange(b.copy(freqHz = (20.0 * 1000.0.pow(v.toDouble())).let { if (it >= 1000) (it / 10).roundToInt() * 10.0 else it.roundToInt().toDouble() })) }
        if (b.type != FilterType.LOW_PASS && b.type != FilterType.HIGH_PASS)
            SliderRow("Gain", b.gainDb.toFloat(), -12f..12f, db(b.gainDb)) { v -> onChange(b.copy(gainDb = (v * 10).roundToInt() / 10.0)) }
        SliderRow("Q", b.q.toFloat(), 0.3f..10f, "%.2f".format(b.q)) { v -> onChange(b.copy(q = (v * 100).roundToInt() / 100.0)) }
    }
}

private fun typeLabel(t: FilterType) = when (t) {
    FilterType.PEAK -> "Peak ▾"; FilterType.LOW_SHELF -> "Low shelf ▾"; FilterType.HIGH_SHELF -> "High shelf ▾"
    FilterType.LOW_PASS -> "Low-pass ▾"; FilterType.HIGH_PASS -> "High-pass ▾"
}

@Composable
private fun AutoEqSearch(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AutoEq.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (query.isBlank()) return
        busy = true; error = null
        scope.launch {
            try { results = AutoEq.search(ctx.cacheDir, query) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Couldn't reach AutoEq: ${e.message}" }
            busy = false
        }
    }
    Group("AutoEq", footer = "Profiles from the AutoEq project (MIT). Applying one loads its parametric filters and preamp and flattens the graphic EQ.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Headphone model") }, placeholder = { Text("e.g. HD 650, AirPods Pro") },
                trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Rounded.Search, "Search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.fillMaxWidth())
            if (busy) CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            results?.let { list ->
                if (list.isEmpty()) Text("No profiles match “$query”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.take(25).forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            try {
                                val p = AutoEq.profile(e)
                                updEq { it.copy(enabled = true, bypassB = false, parametric = p.bands, parametricOn = true, preampDb = p.preampDb.coerceIn(-12.0, 6.0),
                                    graphic = List(10) { 0.0 }, preset = "AutoEq · ${p.name}") }
                                Pills.show("AutoEq: ${p.name}"); onDone()
                            } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { error = "Couldn't load that profile: ${ex.message}" }
                            busy = false
                        }
                    }.padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("measured by ${e.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            FilledTonalButton(onClick = onDone) { Text("Close") }
        }
    }
}
