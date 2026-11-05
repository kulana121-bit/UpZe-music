package com.ravi.musicspot.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ravi.musicspot.ui.components.GlassTopBar
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.screens.LocalNav
import com.ravi.musicspot.ui.screens.pagePadding
import com.ravi.musicspot.ui.theme.Ms
import kotlin.math.roundToInt

/** A settings page: glass top bar over a scrolling list. */
@Composable
fun SettingsScaffold(title: String, back: Boolean = true, actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    val onBack: (() -> Unit)? = if (back) { { nav.back() } } else null
    val header = com.ravi.musicspot.ui.screens.rememberHeaderHeight() // v2.0.17: list starts below the floating title pill
    GlassLayer(Modifier.fillMaxSize(), overlay = { GlassTopBar(title, onBack = onBack, height = header, actions = actions) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(top = 76.dp, header = header.value), content = content)
    }
}

/** A soft rounded card holding related rows, with an optional small caption above it. */
@Composable
fun Group(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 10.dp))
        Column(Modifier.fillMaxWidth().clip(Ms.r20).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 4.dp), content = content)
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

@Composable
fun RowIcon(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun NavRow(icon: ImageVector?, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { RowIcon(icon); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else 0.5f }) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(title: String?, options: List<T>, selected: T, label: (T) -> String, subtitle: String? = null, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (title != null || subtitle != null) Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, enabled = enabled, shape = Ms.pill) }
        }
    }
}

/** Radio-style list: one row per option with a detail line. */
@Composable
fun <T> OptionList(options: List<T>, selected: T, label: (T) -> String, detail: (T) -> String?, onSelect: (T) -> Unit) {
    options.forEachIndexed { i, o ->
        if (i > 0) RowDivider()
        Row(Modifier.fillMaxWidth().clickable { onSelect(o) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = o == selected, onClick = { onSelect(o) })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label(o), style = MaterialTheme.typography.titleSmall)
                detail(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun SliderRow(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, steps: Int = 0, enabled: Boolean = true,
    subtitle: String? = null, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/**
 * A short list reordered by long-pressing (or dragging) the handle. Rows have a fixed [rowHeight] so the drop
 * position is simple arithmetic; every row also exposes "Move up/down" accessibility actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T : Any> ReorderList(
    items: List<T>, onReorder: (List<T>) -> Unit, rowHeight: Dp = 64.dp, label: (T) -> String,
    row: @Composable (item: T, index: Int, handle: Modifier) -> Unit,
) {
    // One state object for the whole lifetime: the drag gesture closures below keep pointing at it.
    var order by remember { mutableStateOf(items) }
    var dragging by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(items) { if (dragging == null) order = items }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val commit by rememberUpdatedState(onReorder)
    fun move(item: T, by: Int): Boolean {
        // v2.0.14: bail before coerceIn (lastIndex is -1 on an empty list and would throw).
        val from = order.indexOf(item)
        if (from < 0) return false
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from == to) return false
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }
    Column {
        order.forEachIndexed { index, item ->
            key(item) {
                val isDragging = dragging == item
                val handle = Modifier.pointerInput(item) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = item; offset = 0f },
                        onDragEnd = { dragging = null; offset = 0f; commit(order) },
                        onDragCancel = { dragging = null; offset = 0f; commit(order) },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val steps = (offset / rowPx).roundToInt()
                            if (steps != 0 && move(item, steps)) offset -= steps * rowPx
                        },
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(rowHeight).zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) offset else 0f; shadowElevation = if (isDragging) 12f else 0f }
                        .background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent, Ms.r16)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move ${label(item)} up") { move(item, -1).also { if (it) commit(order) } },
                                CustomAccessibilityAction("Move ${label(item)} down") { move(item, 1).also { if (it) commit(order) } },
                            )
                        },
                ) { row(item, index, handle) }
            }
        }
    }
}

@Composable
fun DragHandle(modifier: Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.DragHandle, "Long-press and drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
