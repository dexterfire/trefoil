package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*

/* Routes: how a machine is reached (local network, Tailscale, HTTPS, SSH, T3 Connect tunnel). Hosts only, never URLs or tokens. */

internal fun routeKindLabel(kind: RouteKind): String = when (kind) {
    RouteKind.Lan -> "Local network"
    RouteKind.Tailnet -> "Tailscale"
    RouteKind.Public -> "HTTPS"
    RouteKind.Ssh -> "SSH"
    RouteKind.Tunnel -> "T3 Connect"
}

internal fun routeKindIcon(kind: RouteKind): ImageVector = when (kind) {
    RouteKind.Lan -> TfIcons.Wifi
    RouteKind.Tailnet -> TfIcons.Shield
    RouteKind.Public -> TfIcons.Globe
    RouteKind.Ssh -> TfIcons.Terminal
    RouteKind.Tunnel -> TfIcons.Cloud
}

/** `host` or `host:port` of a route URL: no scheme, path, query, fragment or credentials. Blank when unparseable. */
internal fun routeHost(baseUrl: String): String =
    runCatching { java.net.URI(baseUrl.trim()).let { uri -> uri.host?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host } } }.getOrNull().orEmpty()

private fun isPlainHttp(baseUrl: String): Boolean = baseUrl.trim().startsWith("http://", ignoreCase = true)

/** One line of the route list. [needsConsent]: a learned plain-HTTP route that is not used until the user allows it. */
internal data class RouteRow(val route: EnvironmentRoute, val host: String, val active: Boolean, val needsConsent: Boolean)

/**
 * The routes to show for [environment]: its connection plan plus learned plain-HTTP routes that the plan hides
 * until the user consents. [active] is the live route; it only matches rows of this machine.
 */
internal fun routeRows(environment: SavedEnvironment, active: EnvironmentRoute?): List<RouteRow> {
    val planned = environment.candidateRoutes()
    val waiting = environment.routes.filter { route ->
        route.source == RouteSource.Learned && route.kind != RouteKind.Ssh && isPlainHttp(route.baseUrl) &&
            planned.none { it.kind == route.kind && routeHost(it.baseUrl) == routeHost(route.baseUrl) }
    }
    val ssh = environment.ssh
    return (planned + waiting).sortedWith(compareBy({ it.kind.ordinal }, { it.source.ordinal })).map { route ->
        val host = if (route.kind == RouteKind.Ssh) ssh?.let { "${it.host}:${it.port}" }.orEmpty() else routeHost(route.baseUrl)
        val isActive = active != null && active.kind == route.kind && (route.kind == RouteKind.Ssh || routeHost(active.baseUrl) == host)
        RouteRow(route, host, isActive, needsConsent = route in waiting)
    }
}

/** Plain-HTTP routes the user can turn on or off: any learned route over HTTP, used or waiting. */
internal fun hasLearnedHttpRoute(environment: SavedEnvironment): Boolean =
    environment.routes.any { it.source == RouteSource.Learned && it.kind != RouteKind.Ssh && isPlainHttp(it.baseUrl) }

internal fun hasTunnelRoute(environment: SavedEnvironment): Boolean =
    environment.routes.any { it.kind == RouteKind.Tunnel } || environment.candidateRoutes().any { it.kind == RouteKind.Tunnel }

/** Compact label for the route of the live connection. */
@Composable
internal fun RouteBadge(kind: RouteKind, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Row(modifier.background(c.s2, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp).semantics(mergeDescendants = true) { contentDescription = "Connected via ${routeKindLabel(kind)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(routeKindIcon(kind), null, Modifier.size(13.dp), tint = c.muted)
        Text(routeKindLabel(kind), style = TrefoilType.meta.copy(fontSize = 12.sp), color = c.text, maxLines = 1)
    }
}

/** The routes of one machine, the plain-HTTP consent switch and the hint to turn on T3 Connect. Stateless. */
@Composable
internal fun RoutesSection(environment: SavedEnvironment, active: EnvironmentRoute?, onAllowLocalHttp: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Tf.colors
    val rows = routeRows(environment, active)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ROUTES", style = TrefoilType.section, color = c.muted)
        rows.forEach { RouteRowItem(it) }
        if (hasLearnedHttpRoute(environment)) LocalHttpConsent(environment.allowInsecureHttp, enabled, onAllowLocalHttp)
        if (!hasTunnelRoute(environment)) TunnelHint()
    }
}

@Composable
private fun RouteRowItem(row: RouteRow) {
    val c = Tf.colors
    val label = routeKindLabel(row.route.kind)
    val dim = row.needsConsent
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$label, ${row.host}"
        stateDescription = when { row.needsConsent -> "Waiting for permission"; row.active -> "In use"; else -> "Available" }
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(routeKindIcon(row.route.kind), null, Modifier.size(18.dp), tint = if (dim) c.muted.copy(alpha = .6f) else c.text)
        Column(Modifier.weight(1f)) {
            Text(label, style = TrefoilType.meta, color = if (dim) c.muted else c.text, maxLines = 1)
            if (row.host.isNotBlank()) Text(row.host, style = TrefoilType.mono, color = c.muted.copy(alpha = if (dim) .7f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            row.active -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(c.green); Text("In use", style = TrefoilType.meta, color = c.green, maxLines = 1)
            }
            row.needsConsent -> Text("Off", style = TrefoilType.meta, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun LocalHttpConsent(allowed: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.toggleable(value = allowed, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use local network (unencrypted HTTP)", Modifier.weight(1f), style = TrefoilType.meta, color = c.text)
            Switch(allowed, null, enabled = enabled)
        }
        Text("Plain HTTP exposes conversations to others on the network. Turn it on only for a network you trust.", style = TrefoilType.preview.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.amber)
    }
}

/** Muted nudge shown while the machine has no T3 Connect tunnel address. */
@Composable
internal fun TunnelHint(modifier: Modifier = Modifier) {
    val c = Tf.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(TfIcons.Cloud, null, Modifier.size(18.dp), tint = c.muted)
        Text("Turn on T3 Connect in T3 Code (Settings → Sharing & access) to reach this machine from anywhere. Trefoil learns the address after it next connects.",
            style = TrefoilType.preview.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.muted)
    }
}
