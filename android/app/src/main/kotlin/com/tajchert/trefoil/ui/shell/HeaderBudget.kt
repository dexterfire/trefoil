package com.tajchert.trefoil.ui.shell

import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec

/**
 * Which trailing conversation-header controls stay visible; the rest move to the top of the thread menu.
 * [toggle] is the side-panel toggle (only where a panel can dock); [menuAgents] / [menuServers] are the demoted chips.
 */
internal data class HeaderBudget(
    val agentsChip: Boolean, val serversChip: Boolean, val toggle: Boolean,
    val menuAgents: Boolean = false, val menuServers: Boolean = false,
)

/** Pane widths of spec 3.2: below the first only the toggle stays, below the second at most one chip. */
internal const val HeaderChipsBelow = 460f
internal const val HeaderOneChipBelow = 600f

/** Below this conversation-pane width an open panel's toggle is left out of the header. */
internal const val HeaderToggleOpenMinPane = 380f

/**
 * Spec 3.2 header table, decided from the conversation pane width (never the window). Windows narrower than 600dp keep today's header
 * (every chip that exists, no toggle). Large text demotes the chips earlier: from font scale 1.5 below 680dp, from 2.0 below 840dp.
 * The Agents chip wins the single slot of a 460-599dp pane.
 */
internal fun headerBudget(
    windowWidth: Float, paneWidth: Float, fontScale: Float, agents: Boolean, servers: Boolean, dockable: Boolean, panelOpen: Boolean = false,
): HeaderBudget {
    if (windowWidth < AdaptiveSpec.MediumMinWidth) return HeaderBudget(agentsChip = agents, serversChip = servers, toggle = false)
    // With the panel open the toggle would sit in a pane too narrow for it; the panel's own Close button is the way out.
    val toggle = dockable && !(panelOpen && paneWidth < HeaderToggleOpenMinPane)
    val chipsDemoted = paneWidth < HeaderChipsBelow || (fontScale >= 2f && paneWidth < AdaptiveSpec.ExpandedMinWidth) || (fontScale >= 1.5f && paneWidth < 680f)
    if (chipsDemoted) return HeaderBudget(agentsChip = false, serversChip = false, toggle = toggle, menuAgents = agents, menuServers = servers)
    if (paneWidth < HeaderOneChipBelow && agents && servers) return HeaderBudget(agentsChip = true, serversChip = false, toggle = toggle, menuServers = true)
    return HeaderBudget(agentsChip = agents, serversChip = servers, toggle = toggle)
}

/** Menu label of the demoted dev-servers chip: "Dev servers (:5173)" for one port, "Dev servers (3)" for several. */
internal fun serversMenuLabel(ports: List<Int>): String = if (ports.size == 1) "Dev servers (:${ports[0]})" else "Dev servers (${ports.size})"
