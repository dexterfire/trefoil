package com.tajchert.trefoil.ui.adaptive

/**
 * What the shell tells the thread list header about its surroundings. The default (all off) is today's phone header.
 *
 * @property onCollapse non-null when the list sits in a collapsible pane: the header shows a "Hide list" icon button that calls it.
 * @property onOpenNavigation non-null when app destinations are not on a rail (two panes without a rail, compact height): the header
 *   shows a navigation/menu button that calls it.
 * @property railPresent true when a rail beside the list already shows machines and the app menu, so the header drops its own
 *   machine switcher and overflow menu.
 */
internal data class ThreadListChrome(val onCollapse: (() -> Unit)? = null, val onOpenNavigation: (() -> Unit)? = null, val railPresent: Boolean = false)
