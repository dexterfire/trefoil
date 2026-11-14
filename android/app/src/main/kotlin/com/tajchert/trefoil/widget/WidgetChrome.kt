package com.tajchert.trefoil.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.R

// One chrome for every Trefoil widget: rounded container, header (mark, title, refresh), body,
// and a muted footer ("Studio · 3m ago"). Colours are the fixed Trefoil palette ([WidgetPalette]),
// never wallpaper colours.

/** Usage screen tab a widget tap should open. */
enum class UsageWidgetTab { Limits, Cost, Tokens }

/** Intent extra (value: [UsageWidgetTab] name) that makes [MainActivity] open the Usage screen. */
const val EXTRA_OPEN_USAGE_TAB = "com.tajchert.trefoil.extra.OPEN_USAGE_TAB"
const val ACTION_OPEN_USAGE = "com.tajchert.trefoil.action.OPEN_USAGE"

/** One line shown by every widget when no machine snapshot exists yet. */
const val WIDGET_SIGNED_OUT_TEXT = "Open Trefoil to connect a machine"

/**
 * Fixed Trefoil palette (light, dark) from ui/TrefoilTheme.kt, identical on every Android version.
 * The container surface lives in res/values{,-night}/widget_colors.xml for widget_background.xml.
 */
object WidgetPalette {
    private fun pair(light: Long, dark: Long): ColorProvider = DayNightColorProvider(Color(light), Color(dark))
    val track: ColorProvider = pair(0xFFDDE2DF, 0xFF2A2F2F)
    val text: ColorProvider = pair(0xFF111514, 0xFFECEFEE)
    val muted: ColorProvider = pair(0xFF56605D, 0xFF9CA4A2)
    val accent: ColorProvider = pair(0xFF1E7A3A, 0xFF8EDB9B)
    val onAccent: ColorProvider = pair(0xFFFFFFFF, 0xFF08200F)
    /** Past-day chart bars; the latest day uses [accent]. Both keep at least 3:1 against the surface. */
    val pastBar: ColorProvider = pair(0xFF4F9F69, 0xFF4E9A5E)
    val good: ColorProvider = pair(0xFF1E7A3A, 0xFF74D08F)
    val warn: ColorProvider = pair(0xFF955700, 0xFFF4B55B)
    val bad: ColorProvider = pair(0xFFB3261E, 0xFFF28B82)
    /** Remaining-percent color: red at 10% or less, amber at 30% or less. */
    fun remaining(percent: Int): ColorProvider = when { percent <= 10 -> bad; percent <= 30 -> warn; else -> good }
}

/** Kept as the single entry point for widget content; colours come from [WidgetPalette]. */
@Composable
fun TrefoilWidgetTheme(content: @Composable () -> Unit) = content()

/** Chrome metrics in dp. The header is a fixed 48dp row so refresh stays a full touch target. */
internal const val WIDGET_PAD_H = 14f
internal const val WIDGET_PAD_V = 12f
internal const val WIDGET_HEADER_DP = 48f
internal const val WIDGET_TITLE_SP = 13
internal const val WIDGET_NOTE_SP = 12

/** Footer line height including its top gap. */
internal fun widgetFooterDp(fontScale: Float): Float = 4f + 18f * fontScale.coerceAtLeast(1f)

internal data class WidgetChromeParts(val header: Boolean, val footer: Boolean)

/**
 * Which chrome rows fit around [contentDp] of body: small sizes drop the footer first, then the
 * header. A stale or failed refresh ([alert]) keeps the footer in place of the header.
 */
internal fun widgetChromeParts(heightDp: Float, fontScale: Float, contentDp: Float, alert: Boolean): WidgetChromeParts {
    val footer = widgetFooterDp(fontScale)
    val withHeader = WIDGET_HEADER_DP + WIDGET_PAD_V + contentDp
    val bare = 2 * WIDGET_PAD_V + contentDp
    return when {
        heightDp >= withHeader + footer -> WidgetChromeParts(header = true, footer = true)
        alert && heightDp >= bare + footer -> WidgetChromeParts(header = false, footer = true)
        heightDp >= withHeader -> WidgetChromeParts(header = true, footer = false)
        else -> WidgetChromeParts(header = false, footer = false)
    }
}

/** Width available to the body. */
internal fun widgetContentWidth(widthDp: Float): Float = (widthDp - 2 * WIDGET_PAD_H).coerceAtLeast(1f)

/** Height available to the body once [parts] are placed. */
internal fun widgetContentHeight(heightDp: Float, fontScale: Float, parts: WidgetChromeParts): Float =
    (heightDp - (if (parts.header) WIDGET_HEADER_DP + WIDGET_PAD_V else 2 * WIDGET_PAD_V) -
        (if (parts.footer) widgetFooterDp(fontScale) else 0f)).coerceAtLeast(1f)

/** Opens Trefoil on the Usage screen at [tab]. */
fun openUsageAction(context: Context, tab: UsageWidgetTab): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_USAGE).putExtra(EXTRA_OPEN_USAGE_TAB, tab.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

/** Asks for a background refresh of the widget snapshot (never a mutation). */
fun refreshUsageAction(): Action = actionRunCallback<RefreshUsageWidgetsAction>()

/** Refresh tap: opens Trefoil when a background refresh can't reach this machine (e.g. SSH needs a prompt). */
@Composable
fun widgetRefreshAction(snapshot: UsageWidgetSnapshot, tab: UsageWidgetTab): Action =
    if (snapshot.backgroundRefresh) refreshUsageAction() else openUsageAction(LocalContext.current, tab)

/** Rounded Trefoil container; tapping anywhere not handled by a child opens Trefoil on [tab]. */
@Composable
internal fun widgetRoot(tab: UsageWidgetTab): GlanceModifier =
    GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(ImageProvider(R.drawable.widget_background))
        .cornerRadius(R.dimen.widget_corner_radius)
        .clickable(openUsageAction(LocalContext.current, tab))

/** Single-line widget text; three sizes in total: the hero number, [WIDGET_TITLE_SP] and [WIDGET_NOTE_SP]. */
@Composable
internal fun WidgetText(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider = WidgetPalette.muted,
    sizeSp: Int = WIDGET_NOTE_SP,
    bold: Boolean = false,
    align: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text,
        modifier,
        maxLines = maxLines,
        style = TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            textAlign = align,
        ),
    )
}

/**
 * Shared widget layout. [content] fills the body between the header and footer; [titleAction]
 * makes the title tappable (e.g. to cycle a range) and [bodyAlignment] centres small bodies.
 */
@Composable
internal fun WidgetScaffold(
    title: String,
    snapshot: UsageWidgetSnapshot,
    tab: UsageWidgetTab,
    parts: WidgetChromeParts,
    updatedAtMillis: Long,
    now: Long,
    titleAction: Action? = null,
    bodyAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val refresh = widgetRefreshAction(snapshot, tab)
    Column(widgetRoot(tab)) {
        if (parts.header) WidgetHeader(title, refresh, titleAction) else Spacer(GlanceModifier.height(WIDGET_PAD_V.dp))
        Column(
            GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = WIDGET_PAD_H.dp),
            verticalAlignment = bodyAlignment,
            content = content,
        )
        if (parts.footer) WidgetFooter(snapshot, updatedAtMillis, now, refresh)
        Spacer(GlanceModifier.height(WIDGET_PAD_V.dp))
    }
}

@Composable
private fun WidgetHeader(title: String, refresh: Action, titleAction: Action?) {
    Row(
        GlanceModifier.fillMaxWidth().height(WIDGET_HEADER_DP.dp).padding(start = WIDGET_PAD_H.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(R.drawable.ic_trefoil_mark),
            null,
            GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(WidgetPalette.accent),
        )
        Spacer(GlanceModifier.width(6.dp))
        WidgetText(
            title,
            GlanceModifier.defaultWeight().let { if (titleAction == null) it else it.clickable(titleAction) }
                .padding(vertical = 14.dp),
            color = WidgetPalette.text,
            sizeSp = WIDGET_TITLE_SP,
            bold = true,
        )
        Image(
            ImageProvider(R.drawable.widget_refresh),
            "Refresh usage",
            GlanceModifier.size(WIDGET_HEADER_DP.dp).clickable(refresh).padding(14.dp),
            colorFilter = ColorFilter.tint(WidgetPalette.muted),
        )
    }
}

@Composable
private fun WidgetFooter(snapshot: UsageWidgetSnapshot, updatedAtMillis: Long, now: Long, refresh: Action) {
    val alert = widgetAlert(snapshot, updatedAtMillis, now)
    Row(
        GlanceModifier.fillMaxWidth().padding(start = WIDGET_PAD_H.dp, end = WIDGET_PAD_H.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alert) {
            Image(
                ImageProvider(R.drawable.widget_stale),
                null,
                GlanceModifier.size(12.dp),
                colorFilter = ColorFilter.tint(WidgetPalette.warn),
            )
            Spacer(GlanceModifier.width(4.dp))
        }
        WidgetText(widgetFooterText(snapshot, updatedAtMillis, now), GlanceModifier.defaultWeight().clickable(refresh))
    }
}

/** Full-widget state (no machine yet): same container, mark, one line; tapping opens Trefoil. */
@Composable
fun WidgetMessage(text: String, tab: UsageWidgetTab) {
    Box(widgetRoot(tab).padding(WIDGET_PAD_H.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                ImageProvider(R.drawable.ic_trefoil_mark),
                null,
                GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(WidgetPalette.accent),
            )
            Spacer(GlanceModifier.height(6.dp))
            WidgetText(text, color = WidgetPalette.text, sizeSp = WIDGET_TITLE_SP, align = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Inline empty/missing state inside a widget body: muted mark and one line (the root tap opens Trefoil). */
@Composable
internal fun WidgetStateLine(text: String, modifier: GlanceModifier = GlanceModifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(R.drawable.ic_trefoil_mark),
            null,
            GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(WidgetPalette.muted),
        )
        Spacer(GlanceModifier.width(6.dp))
        WidgetText(text, GlanceModifier.defaultWeight(), maxLines = 2)
    }
}

/** Monochrome provider mark followed by a small gap, or nothing for unknown drivers. */
@Composable
internal fun WidgetProviderMark(driver: String, sizeDp: Int = 14) {
    widgetProviderIcon(driver)?.let {
        Image(ImageProvider(it), null, GlanceModifier.size(sizeDp.dp), colorFilter = ColorFilter.tint(WidgetPalette.text))
        Spacer(GlanceModifier.width(6.dp))
    }
}

/** Rounded track with a [fraction] fill. [widthDp] is the track width the fill is measured against. */
@Composable
internal fun WidgetMeter(
    fraction: Float,
    color: ColorProvider,
    widthDp: Float,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
    heightDp: Int = 4,
) {
    Box(modifier.height(heightDp.dp).background(ImageProvider(R.drawable.widget_pill), ContentScale.FillBounds, ColorFilter.tint(WidgetPalette.track))) {
        val fill = widthDp * fraction.coerceIn(0f, 1f)
        if (fill > 0f)
            Box(
                GlanceModifier.width(fill.coerceAtLeast(heightDp.toFloat()).dp).height(heightDp.dp)
                    .background(ImageProvider(R.drawable.widget_pill), ContentScale.FillBounds, ColorFilter.tint(color)),
            ) {}
    }
}

/** One option of a [WidgetPillGroup]. */
internal data class WidgetPill(val label: String, val selected: Boolean, val action: Action)

/** Rounded track; the selected option is a filled accent pill. Each option is ~36dp tall. */
@Composable
internal fun WidgetPillGroup(options: List<WidgetPill>) {
    Row(
        GlanceModifier.background(ImageProvider(R.drawable.widget_pill), ContentScale.FillBounds, ColorFilter.tint(WidgetPalette.track))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val base =
                if (option.selected)
                    GlanceModifier.background(ImageProvider(R.drawable.widget_pill), ContentScale.FillBounds, ColorFilter.tint(WidgetPalette.accent))
                else GlanceModifier
            WidgetText(
                option.label,
                base.clickable(option.action).padding(horizontal = WIDGET_PILL_PAD_H.dp, vertical = 8.dp),
                color = if (option.selected) WidgetPalette.onAccent else WidgetPalette.text,
                bold = option.selected,
            )
        }
    }
}

internal const val WIDGET_PILL_PAD_H = 9f
internal const val WIDGET_PILL_ROW_DP = 36f
internal const val WIDGET_PILL_GAP_DP = 6f

/** Conservative width of a pill group at [WIDGET_NOTE_SP] so packed rows never clip. */
internal fun widgetPillGroupWidth(labels: List<String>, fontScale: Float): Float =
    4f + labels.sumOf { (2 * WIDGET_PILL_PAD_H + it.length * 6.6f * fontScale.coerceAtLeast(1f)).toDouble() }.toFloat()

/** Greedy packing of pill groups (by [widths]) into rows that fit [availableDp]; returns indices per row. */
internal fun packPillGroups(widths: List<Float>, availableDp: Float): List<List<Int>> {
    val rows = mutableListOf<MutableList<Int>>()
    var used = 0f
    widths.forEachIndexed { index, width ->
        val needed = if (rows.isEmpty() || rows.last().isEmpty()) width else used + WIDGET_PILL_GAP_DP + width
        if (rows.isNotEmpty() && needed <= availableDp) {
            rows.last() += index
            used = needed
        } else {
            rows += mutableListOf(index)
            used = width
        }
    }
    return rows
}
