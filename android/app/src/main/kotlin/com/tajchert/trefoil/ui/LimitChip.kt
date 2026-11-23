package com.tajchert.trefoil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/** Severity of a "% left" value; one set of thresholds for the chip and the Limits tab. */
internal enum class LimitSeverity { Critical, Low, Healthy }

/** Mirrors the window bar in UsageScreen: 10% or less is red, 30% or less is amber, otherwise green. */
internal fun limitSeverity(remainingPercent: Int): LimitSeverity = when {
    remainingPercent <= 10 -> LimitSeverity.Critical
    remainingPercent <= 30 -> LimitSeverity.Low
    else -> LimitSeverity.Healthy
}

internal fun LimitSeverity.color(c: TrefoilColors): Color = when (this) {
    LimitSeverity.Critical -> c.red
    LimitSeverity.Low -> c.amber
    LimitSeverity.Healthy -> c.green
}

/**
 * Lowest `remainingPercent` over the account-wide windows of all enabled providers; null when nothing reports a limit.
 * Claude's model-scoped weekly allowances (`seven_day_<model>`, such as Fable) block only their own model, so they
 * would overstate how close the account is to its limit; only the 5h session and overall weekly windows count.
 */
internal fun tightestLimitPercent(providers: List<ProviderInstance>): Int? =
    providers.filter { it.enabled }
        .flatMap { p -> p.usageLimits?.windows.orEmpty().filterNot { isModelScopedWindow(p.driver, it) } }
        .minOfOrNull { it.remainingPercent }

// Mirrors T3 `windowLimitsModel` in apps/server/src/provider/claudeUsageLimits.ts (MIT; see protocol/T3-LICENSE.txt).
private fun isModelScopedWindow(driver: String, window: UsageLimitWindow): Boolean =
    driver == "claudeAgent" && window.id.startsWith("seven_day_")

internal fun limitChipDescription(percent: Int?): String =
    if (percent == null) "Limits and usage" else "Limits and usage, lowest limit $percent% left"

/**
 * Thread-list header entry to Limits & usage: ring gauge plus the tightest "NN% left", or the gauge icon alone
 * without limit data or when [showNumber] is false (narrow headers). The touch target is at least 48dp.
 */
@Composable
internal fun LimitChip(percent: Int?, showNumber: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics(mergeDescendants = true) { contentDescription = limitChipDescription(percent) }
        .focusRing().handPointer().clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.height(32.dp).background(c.s2, RoundedCornerShape(16.dp)).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (percent == null) Icon(TfIcons.Gauge, null, Modifier.size(18.dp), tint = c.muted)
            else {
                val color = limitSeverity(percent).color(c)
                LimitRing(percent, color, c.line)
                if (showNumber) Text("$percent%", style = TrefoilType.meta, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LimitRing(percent: Int, color: Color, track: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arc = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
        if (percent > 0) drawArc(color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arc, style = stroke)
    }
}
