package com.tajchert.trefoil.ui.shell

import com.tajchert.trefoil.ui.adaptive.SidePanelMode
import com.tajchert.trefoil.ui.adaptive.WindowSpec
import com.tajchert.trefoil.ui.adaptive.shellPolicy
import com.tajchert.trefoil.ui.shell.SidePanelTab.Agents
import com.tajchert.trefoil.ui.shell.SidePanelTab.Preview
import com.tajchert.trefoil.ui.shell.SidePanelTab.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the side panel: which tabs exist, which one is shown, how a narrow window presents it, and the header budget. */
class SidePanelTest {
    @Test fun tabsFollowWhatTheMachineAndThreadOffer() {
        assertEquals(listOf(Preview, Screens, Agents), sidePanelTabs(preview = true, screens = true, agents = true))
        assertEquals(listOf(Screens), sidePanelTabs(preview = false, screens = true, agents = false))
        assertEquals(listOf(Preview, Agents), sidePanelTabs(preview = true, screens = false, agents = true))
        assertEquals(emptyList<SidePanelTab>(), sidePanelTabs(preview = false, screens = false, agents = false))
    }

    @Test fun aSavedTabThatWentAwayFallsBackToTheFirstUsableOneOrCloses() {
        assertNull(resolveSidePanelTab(null, listOf(Preview, Screens)))
        assertEquals(Agents, resolveSidePanelTab(Agents, listOf(Preview, Screens, Agents)))
        assertEquals(Preview, resolveSidePanelTab(Agents, listOf(Preview, Screens)))
        // A disabled Screens tab is no fallback, but an open one stays open.
        assertNull(resolveSidePanelTab(Agents, listOf(Screens), disabled = setOf(Screens)))
        assertEquals(Screens, resolveSidePanelTab(Screens, listOf(Screens), disabled = setOf(Screens)))
        assertNull(resolveSidePanelTab(Preview, emptyList()))
    }

    @Test fun theToggleOpensTheLastUsedTabOrTheFirstOne() {
        assertEquals(Agents, sidePanelOpenTarget(Agents, listOf(Preview, Screens, Agents)))
        assertEquals(Preview, sidePanelOpenTarget(null, listOf(Preview, Screens, Agents)))
        assertEquals(Screens, sidePanelOpenTarget(Agents, listOf(Screens)))
        assertEquals(Agents, sidePanelOpenTarget(Screens, listOf(Screens, Agents), disabled = setOf(Screens)))
        assertNull(sidePanelOpenTarget(null, emptyList()))
    }

    @Test fun aNarrowWindowMapsTheOpenTabInsteadOfDroppingIt() {
        val t = ShellDestination.Threads
        assertEquals(SidePanelPresentation(ShellDestination.Servers, false, false), sidePanelPresentation(t, Preview, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(ShellDestination.Screens, false, false), sidePanelPresentation(t, Screens, SidePanelMode.FullScreen))
        assertEquals(SidePanelPresentation(t, true, false), sidePanelPresentation(t, Agents, SidePanelMode.FullScreen))
        // Growing back docks the same tab again.
        for (tab in SidePanelTab.entries) {
            assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, tab, SidePanelMode.ReplacesList))
            assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, tab, SidePanelMode.ThirdPane))
        }
        assertEquals(SidePanelPresentation(t, false, false), sidePanelPresentation(t, null, SidePanelMode.ThirdPane))
        assertEquals(SidePanelPresentation(ShellDestination.Usage, false, false), sidePanelPresentation(ShellDestination.Usage, Agents, SidePanelMode.ThirdPane))
        // A full-screen viewer takes the window at every width.
        assertEquals(SidePanelPresentation(ShellDestination.Screens, false, false), sidePanelPresentation(t, Screens, SidePanelMode.ThirdPane, screenFullscreen = true))
        assertEquals(SidePanelPresentation(t, false, true), sidePanelPresentation(t, Agents, SidePanelMode.ThirdPane, screenFullscreen = true))
    }

    @Test fun backClosesADockedPanelBeforeLeavingTheThread() {
        assertEquals(ShellBack.ClosePanel, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = true))
        assertEquals(ShellBack.ToParent, shellBack(ShellDestination.Threads, true, false, true, false, hasBackParent = true, panelDocked = false))
        // The page or viewer inside handles its own Back first.
        assertNull(shellBack(ShellDestination.Threads, true, portBrowserOpen = true, twoPane = true, showThread = false, hasBackParent = false, panelDocked = true))
    }

    @Test fun thePanelAndTheKeyboardLayoutNeverMeet() {
        // The keyboard layout needs compact height, where no panel ever docks.
        for (w in listOf(600f, 720f, 880f, 1280f, 1920f)) for (h in listOf(320f, 400f, 479f)) {
            val policy = shellPolicy(WindowSpec(w, h))
            assertTrue("compact height ${w}x$h", policy.compactHeight)
            assertEquals("no docked panel at ${w}x$h", SidePanelMode.FullScreen, effectiveSidePanelMode(policy))
        }
        assertEquals(SidePanelMode.ReplacesList, effectiveSidePanelMode(shellPolicy(WindowSpec(1280f, 800f))))
        assertEquals(SidePanelMode.ThirdPane, effectiveSidePanelMode(shellPolicy(WindowSpec(1920f, 1080f))))
        assertEquals(TransientList.PanelOpen, transientList(true, false, false, false, 800f, PaneFocus.Conversation, panelReplacesList = true))
        assertEquals(TransientList.None, transientList(true, split = true, compactHeight = false, imeVisible = false, roomAboveImeDp = 800f, focus = PaneFocus.None, panelReplacesList = true))
        assertEquals(TransientList.HideList, transientList(true, false, true, true, 100f, PaneFocus.Conversation, panelReplacesList = false))
    }

    @Test fun headerBudgetFollowsTheConversationPaneWidth() {
        data class Row(val window: Float, val pane: Float, val font: Float, val agents: Boolean, val servers: Boolean, val dockable: Boolean, val expected: HeaderBudget)
        val rows = listOf(
            // Phones keep today's header whatever the font scale.
            Row(400f, 400f, 1f, true, true, false, HeaderBudget(agentsChip = true, serversChip = true, toggle = false)),
            Row(400f, 400f, 2f, true, true, false, HeaderBudget(agentsChip = true, serversChip = true, toggle = false)),
            // FoldPortrait (no dock), TabletLandscape with the panel open (420 conversation).
            Row(673f, 336f, 1f, true, true, false, HeaderBudget(false, false, false, menuAgents = true, menuServers = true)),
            Row(1280f, 420f, 1f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1280f, 420f, 1f, false, true, true, HeaderBudget(false, false, true, menuServers = true)),
            // One chip at 460-599, Agents first.
            Row(1000f, 520f, 1f, true, true, true, HeaderBudget(true, false, true, menuServers = true)),
            Row(1000f, 520f, 1f, false, true, true, HeaderBudget(false, true, true)),
            Row(1000f, 520f, 1f, true, false, true, HeaderBudget(true, false, true)),
            // Both from 600.
            Row(1280f, 600f, 1f, true, true, true, HeaderBudget(true, true, true)),
            Row(1920f, 940f, 1f, true, true, true, HeaderBudget(true, true, true)),
            // Large text demotes earlier: 1.5 below 680, 2.0 below 840.
            Row(1280f, 679f, 1.5f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1280f, 680f, 1.5f, true, true, true, HeaderBudget(true, true, true)),
            Row(1280f, 839f, 2f, true, true, true, HeaderBudget(false, false, true, menuAgents = true, menuServers = true)),
            Row(1920f, 840f, 2f, true, true, true, HeaderBudget(true, true, true)),
            Row(1280f, 700f, 1.3f, true, true, true, HeaderBudget(true, true, true)),
        )
        rows.forEach { r -> assertEquals(r.toString(), r.expected, headerBudget(r.window, r.pane, r.font, r.agents, r.servers, r.dockable)) }
    }

    @Test fun anOpenPanelDropsTheToggleOnlyBelow380dp() {
        assertEquals(false, headerBudget(841f, 332f, 1f, true, true, true, panelOpen = true).toggle)
        assertEquals(false, headerBudget(841f, 379f, 1f, true, true, true, panelOpen = true).toggle)
        assertEquals(true, headerBudget(841f, 380f, 1f, true, true, true, panelOpen = true).toggle)
        // Closed, the toggle that opens the panel stays however narrow the pane is.
        assertEquals(true, headerBudget(841f, 332f, 1f, true, true, true, panelOpen = false).toggle)
        // Chips are unaffected by the panel state.
        assertEquals(headerBudget(841f, 332f, 1f, true, true, true).copy(toggle = false), headerBudget(841f, 332f, 1f, true, true, true, panelOpen = true))
    }

    @Test fun demotedServersLabelNamesThePort() {
        assertEquals("Dev servers (:5173)", serversMenuLabel(listOf(5173)))
        assertEquals("Dev servers (3)", serversMenuLabel(listOf(3000, 5173, 8080)))
        assertFalse(serversMenuLabel(listOf(1)).isEmpty())
    }
}
