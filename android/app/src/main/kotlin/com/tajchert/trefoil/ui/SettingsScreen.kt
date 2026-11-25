package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.theme.ThemeFamily

/**
 * App settings. Every control writes straight through [onChange]; nothing is held locally, so a setting is never lost by
 * leaving the screen mid-edit.
 *
 * The list-order controls are grouped by the two bands the list actually uses — pinned projects and the rest — because
 * one shared rule would make "pinned on top" meaningless for the ordering the user chose.
 */
@Composable
internal fun SettingsScreen(
    preferences: AppPreferences,
    currentWidthDp: Float,
    onChange: ((AppPreferences) -> AppPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val c = Tf.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TfIconButton(TfIcons.Back, "Назад", onBack, iconSize = 22.dp, tint = c.text)
            Text("Настройки", Modifier.weight(1f), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                letterSpacing = (-0.01).em, color = c.text)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            SettingsSection("Закреплённые проекты")
            ProjectOrderChoice(preferences.list.pinnedOrder) { picked ->
                onChange { it.copy(list = it.list.copy(pinnedOrder = picked)) }
            }

            SettingsSection("Остальные проекты")
            ProjectOrderChoice(preferences.list.otherOrder) { picked ->
                onChange { it.copy(list = it.list.copy(otherOrder = picked)) }
            }

            SettingsSection("Треды внутри проекта")
            RadioRow("По активности", preferences.list.threadOrder == ThreadOrder.Activity) {
                onChange { it.copy(list = it.list.copy(threadOrder = ThreadOrder.Activity)) }
            }
            RadioRow("По дате создания", preferences.list.threadOrder == ThreadOrder.Created) {
                onChange { it.copy(list = it.list.copy(threadOrder = ThreadOrder.Created)) }
            }

            SettingsSection("Оформление")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeFamily.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { family ->
                            ThemeCard(family, selected = preferences.themeFamily == family, Modifier.weight(1f)) {
                                onChange { it.copy(themeFamily = family) }
                            }
                        }
                    }
                }
            }
            SettingsSection("Яркость")
            SegmentedRow(
                options = listOf("Системная" to BrightnessMode.System, "Тёмная" to BrightnessMode.Dark, "Светлая" to BrightnessMode.Light),
                selected = preferences.brightness,
            ) { mode -> onChange { it.copy(brightness = mode) } }

            SettingsSection("Планшетный режим")
            SwitchRow("Боковая панель", preferences.forceRail) { on ->
                onChange { it.copy(forceRail = on) }
            }
            WidthSlider(
                current = currentWidthDp,
                forced = preferences.forcedWidthDp,
                onChange = { width -> onChange { it.copy(forcedWidthDp = width) } },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    val c = Tf.colors
    Text(title.uppercase(java.util.Locale.ROOT), Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.07.em, color = c.muted)
}

/** The three order rules a band can take: newest activity, alphabet, or the server's own order. */
@Composable
private fun ProjectOrderChoice(current: ProjectOrder, onPick: (ProjectOrder) -> Unit) {
    RadioRow("По активности", current == ProjectOrder.Activity) { onPick(ProjectOrder.Activity) }
    RadioRow("По алфавиту", current == ProjectOrder.Alphabetical) { onPick(ProjectOrder.Alphabetical) }
    RadioRow("Как отдаёт сервер", current == ProjectOrder.Server) { onPick(ProjectOrder.Server) }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onPick: () -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.fillMaxWidth().focusRing().handPointer().clickable(onClick = onPick)
            .semantics { role = Role.RadioButton; contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).background(if (selected) c.accent else Color.Transparent, CircleShape)
                .then(if (selected) Modifier else Modifier.border1(c.line)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(7.dp).background(c.onAccent, CircleShape)) }
        Text(label, Modifier.weight(1f), fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.text)
    }
}

private fun Modifier.border1(color: Color) = then(
    Modifier.drawBehind {
        drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
    },
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.fillMaxWidth().focusRing().handPointer().clickable { onChange(!checked) }
            .semantics { role = Role.Switch; contentDescription = label; stateDescription = if (checked) "Включено" else "Выключено" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.text)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.s3))
    }
}

@Composable
private fun SegmentedRow(options: List<Pair<String, BrightnessMode>>, selected: BrightnessMode, onPick: (BrightnessMode) -> Unit) {
    val c = Tf.colors
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.s2, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (label, mode) ->
            val on = mode == selected
            Box(
                Modifier.weight(1f).focusRing(RoundedCornerShape(8.dp)).handPointer().clickable { onPick(mode) }
                    .semantics { role = Role.RadioButton; contentDescription = label }
                    .background(if (on) c.s1 else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = InterFamily, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp,
                    color = if (on) c.text else c.muted)
            }
        }
    }
}

/**
 * Previews a theme family as the actual colours it will paint, so the choice is made by looking rather than by name.
 */
@Composable
private fun ThemeCard(family: ThemeFamily, selected: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val c = Tf.colors
    val preview = family.palette(c.isDark)
    Column(
        modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).handPointer().clickable(onClick = onPick)
            .semantics { role = Role.RadioButton; contentDescription = family.label }
            .background(preview.bg, RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.androidBorder(c.accent) else Modifier.androidBorder(preview.line))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.62f).height(6.dp).background(preview.accent, RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxWidth(0.9f).height(6.dp).background(preview.s2, RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxWidth(0.75f).height(6.dp).background(preview.s2, RoundedCornerShape(3.dp)))
        Text(family.label, Modifier.padding(top = 2.dp), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = preview.text)
        Text(
            if (c.isDark) "тёмная половина" else "светлая половина",
            fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 9.sp, color = preview.muted,
        )
    }
}

private fun Modifier.androidBorder(color: Color): Modifier = border(1.5.dp, color, RoundedCornerShape(12.dp))

/**
 * Width override for trying the tablet layout on a phone. It starts at the window's real width and writes the same value
 * the shell policy reads, so the layout that appears is the real one for that width.
 */
@Composable
private fun WidthSlider(current: Float, forced: Float?, onChange: (Float?) -> Unit) {
    val c = Tf.colors
    val value = forced ?: current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ширина окна", Modifier.weight(1f), fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.text)
            Text("${value.toInt()}dp", fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.accent)
            if (forced != null) TextButton(onClick = { onChange(null) }) { Text("Сбросить", fontSize = 12.sp, color = c.muted) }
        }
        Slider(
            value = value.coerceIn(MinForcedWidth, MaxForcedWidth),
            onValueChange = { onChange(it) },
            valueRange = MinForcedWidth..MaxForcedWidth,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.s3),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${MinForcedWidth.toInt()}", fontSize = 10.sp, color = c.muted)
            Text("${MaxForcedWidth.toInt()}", fontSize = 10.sp, color = c.muted)
        }
    }
}

/** 320dp is the narrowest supported list; 1600dp covers every width class, including ExtraLarge. */
internal const val MinForcedWidth = 320f
internal const val MaxForcedWidth = 1600f
