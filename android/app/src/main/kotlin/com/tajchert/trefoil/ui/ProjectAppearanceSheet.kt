package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer

/**
 * Icon and colour for one project. Both are optional: clearing either returns that part to the deterministic look the
 * project already had, so nothing is lost by trying an icon out.
 *
 * [onPreview] is called as the user scrubs so the list underneath can show the choice, and [onApply] once they confirm.
 */
@Composable
internal fun ProjectAppearanceSheet(
    projectTitle: String,
    icon: ProjectIcon?,
    color: ProjectColor?,
    onPickIcon: (ProjectIcon?) -> Unit,
    onPickColor: (ProjectColor?) -> Unit,
    onClose: () -> Unit,
) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().background(c.sheet, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProjectTile(projectTitle, size = 34.dp, key = projectTitle, icon = icon, color = color)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(projectTitle, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text, maxLines = 1)
                Text("Иконка и цвет", fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = c.muted)
            }
            Text("Готово", Modifier.focusRing(RoundedCornerShape(8.dp)).handPointer().clickable(onClick = onClose)
                .padding(horizontal = 8.dp, vertical = 6.dp), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.accent)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Иконка".uppercase(java.util.Locale.ROOT), Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp),
                fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.07.em, color = c.muted)
            // Eight per row: the picker stays four rows tall on a phone, so both grids fit on one screen.
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectIcon.entries.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry ->
                            IconChoice(entry, selected = icon == entry, Modifier.weight(1f)) { onPickIcon(if (icon == entry) null else entry) }
                        }
                        repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                // No icon: the project's initials come back.
                ResetChip("Без иконки", icon == null) { onPickIcon(null) }
            }

            Text("Цвет".uppercase(java.util.Locale.ROOT), Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
                fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.07.em, color = c.muted)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectColor.entries.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry ->
                            ColorChoice(entry, selected = color == entry, Modifier.weight(1f)) { onPickColor(if (color == entry) null else entry) }
                        }
                        repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                ResetChip("Цвет по умолчанию", color == null) { onPickColor(null) }
            }
        }
    }
}

@Composable
private fun IconChoice(icon: ProjectIcon, selected: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val c = Tf.colors
    Box(
        modifier.aspectRatio(1f).focusRing(RoundedCornerShape(9.dp)).handPointer().clickable(onClick = onPick)
            .semantics { role = Role.RadioButton; contentDescription = icon.id; this.selected = selected }
            .background(if (selected) c.accentSoft else c.s2, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon.vector(), null, Modifier.fillMaxSize(0.5f), tint = if (selected) c.accent else c.muted)
    }
}

@Composable
private fun ColorChoice(choice: ProjectColor, selected: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val dark = Tf.colors.isDark
    Box(
        modifier.aspectRatio(1f).focusRing(CircleShape).handPointer().clickable(onClick = onPick)
            .semantics { role = Role.RadioButton; contentDescription = choice.id; this.selected = selected }
            .padding(3.dp)
            .background(if (selected) Tf.colors.text else Color.Transparent, CircleShape)
            .padding(2.dp)
            .background(choice.background(dark), CircleShape),
    )
}

@Composable
private fun ResetChip(label: String, selected: Boolean, onPick: () -> Unit) {
    val c = Tf.colors
    Box(
        Modifier.focusRing(RoundedCornerShape(999.dp)).handPointer().clickable(onClick = onPick)
            .semantics { role = Role.RadioButton; contentDescription = label; this.selected = selected }
            .background(if (selected) c.accentSoft else c.s2, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (selected) c.accent else c.muted)
    }
}
