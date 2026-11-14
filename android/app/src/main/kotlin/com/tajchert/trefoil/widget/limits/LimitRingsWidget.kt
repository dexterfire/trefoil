package com.tajchert.trefoil.widget.limits

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.tajchert.trefoil.R
import com.tajchert.trefoil.widget.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LimitRingsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId): Unit = coroutineScope {
        val store = UsageWidgetStore.get(context)
        val initial = store.read()
        var cachedSnapshot = initial
        var cachedMasks = createRingMasks(context, initial)
        // One upstream producer draws per snapshot revision, before any LocalSize composition.
        // Active sessions also receive new masks when the store changes; sizes share Bitmap
        // objects.
        val rendered =
            store.snapshot
                .map {
                    if (it != cachedSnapshot) {
                        cachedMasks = createRingMasks(context, it)
                        cachedSnapshot = it
                    }
                    it to cachedMasks
                }
                .stateIn(this, SharingStarted.Eagerly, initial to cachedMasks)
        provideContent {
            val snapshot by store.snapshot.collectAsState()
            val prepared by rendered.collectAsState()
            TrefoilWidgetTheme {
                LimitRingsContent(snapshot, prepared.second.takeIf { prepared.first == snapshot })
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = UsageWidgetSamples.snapshot()
        val masks = createRingMasks(context, sample)
        provideContent { TrefoilWidgetTheme { LimitRingsContent(sample, masks) } }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        val fallback =
            RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Open Trefoil to see usage limits")
                val intent =
                    android.content
                        .Intent(context, com.tajchert.trefoil.MainActivity::class.java)
                        .setAction(ACTION_OPEN_USAGE)
                        .putExtra(EXTRA_OPEN_USAGE_TAB, UsageWidgetTab.Limits.name)
                setOnClickPendingIntent(
                    android.R.id.text1,
                    android.app.PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallback)
    }
}

@Composable
internal fun LimitRingsContent(
    snapshot: UsageWidgetSnapshot?,
    masks: LimitRingMasks?,
    now: Long = System.currentTimeMillis(),
) {
    if (snapshot == null) {
        WidgetMessage(WIDGET_SIGNED_OUT_TEXT, UsageWidgetTab.Limits)
        return
    }
    val size = LocalSize.current
    val scale = LocalContext.current.resources.configuration.fontScale
    val grid = gaugeGrid(size.width.value, size.height.value, scale)
    val gauges = orderedGauges(snapshot.providers, now)
    val visible = gauges.take(grid.visibleCount(gauges.size))
    val more = gauges.size - visible.size
    val parts = WidgetChromeParts(grid.showHeader, grid.showFooter)
    val body = widgetContentHeight(size.height.value, scale, parts)
    WidgetScaffold(
        "Lowest limits",
        snapshot,
        UsageWidgetTab.Limits,
        parts,
        snapshot.limitsUpdatedAtMillis ?: snapshot.updatedAtMillis,
        now,
        bodyAlignment = Alignment.CenterVertically,
    ) {
        if (gauges.isEmpty()) {
            WidgetStateLine(
                snapshot.providers.firstOrNull()?.let(::limitNotice)
                    ?: "No provider limits reported"
            )
        } else {
            // At most 8 rows, each with at most 5 cells. The overflow chip occupies a cell.
            val cells: List<LimitGauge?> =
                visible + if (more > 0 && grid.capacity > 1) listOf(null) else emptyList()
            val diameter =
                minOf(
                        64f,
                        widgetContentWidth(size.width.value) / grid.columns - 8,
                        body / grid.rows - if (grid.detailed) 34 * scale else if (size.height.value >= 90 * scale) 18 * scale else 0f,
                    )
                    .coerceAtLeast(20f)
            cells.chunked(grid.columns).forEach { row ->
                Row(
                    GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { gauge ->
                        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                            if (gauge == null)
                                Box(
                                    GlanceModifier.background(ImageProvider(R.drawable.widget_pill), ContentScale.FillBounds, ColorFilter.tint(WidgetPalette.track))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    WidgetText("+$more more", color = WidgetPalette.text)
                                }
                            else GaugeCell(gauge, masks, now, grid, diameter, size.height.value, scale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GaugeCell(
    gauge: LimitGauge,
    masks: LimitRingMasks?,
    now: Long,
    grid: GaugeGrid,
    diameter: Float,
    height: Float,
    scale: Float,
) {
    val expired = hasReset(gauge.window, now)
    val unavailable = !gauge.window.usedPercent.isFinite()
    val color =
        if (expired || unavailable) WidgetPalette.muted
        else WidgetPalette.remaining(gauge.window.remainingPercent)
    Column(
        GlanceModifier.semantics {
            contentDescription =
                "${gauge.provider.displayName}, ${gauge.window.label}, " +
                    if (expired) "Reset since last check"
                    else if (unavailable) "Unavailable"
                    else
                        "${gauge.window.remainingPercent}% left, ${limitResetText(gauge.window, now)}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(diameter.dp), contentAlignment = Alignment.Center) {
            if (masks != null) {
                Image(
                    ImageProvider(masks.track),
                    null,
                    GlanceModifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(WidgetPalette.track),
                )
                if (!expired && !unavailable)
                    masks.arcs[gauge.window.remainingPercent]?.let {
                        Image(
                            ImageProvider(it),
                            null,
                            GlanceModifier.fillMaxSize(),
                            colorFilter = ColorFilter.tint(color),
                        )
                    }
            }
            WidgetText(
                if (expired) "Reset"
                else if (unavailable) "—" else "${gauge.window.remainingPercent}%",
                color = color,
                sizeSp = if (diameter < 40) WIDGET_NOTE_SP else 16,
                bold = true,
            )
        }
        if (grid.detailed) {
            Row(GlanceModifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                WidgetProviderMark(gauge.provider.driver, sizeDp = 12)
                WidgetText(gauge.window.label, color = WidgetPalette.text)
            }
            WidgetText(limitResetText(gauge.window, now, compact = true))
        } else if (height >= 90 * scale) {
            if (expired) WidgetText("Reset since last check")
            else WidgetText(gauge.provider.displayName)
        }
    }
}
