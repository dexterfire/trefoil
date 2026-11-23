package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.UsageUiState

/*
 * Form-factor baseline: the real TrefoilApp shell rendered at foldable, tablet, landscape-phone and desktop sizes.
 * These record today's behaviour as-is, so a layout change shows up as a reference diff. The Shell helper only
 * passes fixture state; ShellStart opens destinations that are otherwise reached through UI state.
 * Sizes (dp): FoldPortrait 673x841, FoldLandscape 841x673, TabletPortrait 800x1280, TabletLandscape 1280x800,
 * PhoneLandscape 880x400, Desktop 1920x1080.
 */

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Shell(
    state: ClientState, saved: List<SavedEnvironment> = listOf(ScreenshotFixtures.workstation), start: ShellStart = ShellStart(),
    usage: UsageUiState = UsageUiState(), probes: Map<String, MachineProbe> = emptyMap(),
) = TrefoilApp(
    state, saved, { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
    machineProbes = probes, usage = usage, start = start,
)

// ThreadsConversation
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_ThreadsConversation_FoldPortrait() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_ThreadsConversation_FoldLandscape() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_ThreadsConversation_TabletPortrait() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_ThreadsConversation_TabletLandscape() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 880, heightDp = 400)
@Composable fun FF_ThreadsConversation_PhoneLandscape() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 1920, heightDp = 1080)
@Composable fun FF_ThreadsConversation_Desktop() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun FF_ThreadsConversation_TabletLandscape_Dark() = Shell(FormFactorFixtures.conversationState())

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841, uiMode = NIGHT)
@Composable fun FF_ThreadsConversation_FoldPortrait_Dark() = Shell(FormFactorFixtures.conversationState())

// ThreadsNoSelection
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_ThreadsNoSelection_FoldPortrait() = Shell(FormFactorFixtures.listState())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_ThreadsNoSelection_FoldLandscape() = Shell(FormFactorFixtures.listState())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_ThreadsNoSelection_TabletPortrait() = Shell(FormFactorFixtures.listState())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_ThreadsNoSelection_TabletLandscape() = Shell(FormFactorFixtures.listState())

// ConversationApproval
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_ConversationApproval_FoldPortrait() = Shell(FormFactorFixtures.approvalState())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_ConversationApproval_FoldLandscape() = Shell(FormFactorFixtures.approvalState())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_ConversationApproval_TabletPortrait() = Shell(FormFactorFixtures.approvalState())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_ConversationApproval_TabletLandscape() = Shell(FormFactorFixtures.approvalState())

// UsageLimits
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_UsageLimits_FoldPortrait() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 0), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_UsageLimits_FoldLandscape() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 0), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_UsageLimits_TabletPortrait() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 0), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_UsageLimits_TabletLandscape() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 0), usage = FormFactorFixtures.usage())

// UsageCost
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_UsageCost_FoldPortrait() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 1), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_UsageCost_FoldLandscape() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 1), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_UsageCost_TabletPortrait() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 1), usage = FormFactorFixtures.usage())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_UsageCost_TabletLandscape() = Shell(FormFactorFixtures.usageState(), start = ShellStart(showUsage = true, usageTab = 1), usage = FormFactorFixtures.usage())

// MachinesChooser
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_MachinesChooser_FoldPortrait() = Shell(FormFactorFixtures.listState(), saved = ShellFixtures.saved, start = ShellStart(choosingEnvironment = true), probes = ShellFixtures.probes)

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_MachinesChooser_FoldLandscape() = Shell(FormFactorFixtures.listState(), saved = ShellFixtures.saved, start = ShellStart(choosingEnvironment = true), probes = ShellFixtures.probes)

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_MachinesChooser_TabletPortrait() = Shell(FormFactorFixtures.listState(), saved = ShellFixtures.saved, start = ShellStart(choosingEnvironment = true), probes = ShellFixtures.probes)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_MachinesChooser_TabletLandscape() = Shell(FormFactorFixtures.listState(), saved = ShellFixtures.saved, start = ShellStart(choosingEnvironment = true), probes = ShellFixtures.probes)

// MachinesOnboarding
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_MachinesOnboarding_FoldPortrait() = Shell(ShellFixtures.nothingConnected(), saved = emptyList())

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_MachinesOnboarding_FoldLandscape() = Shell(ShellFixtures.nothingConnected(), saved = emptyList())

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_MachinesOnboarding_TabletPortrait() = Shell(ShellFixtures.nothingConnected(), saved = emptyList())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_MachinesOnboarding_TabletLandscape() = Shell(ShellFixtures.nothingConnected(), saved = emptyList())

// DevServers
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_DevServers_FoldPortrait() = Shell(FormFactorFixtures.portsState(), start = ShellStart(showPorts = true))

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_DevServers_FoldLandscape() = Shell(FormFactorFixtures.portsState(), start = ShellStart(showPorts = true))

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_DevServers_TabletPortrait() = Shell(FormFactorFixtures.portsState(), start = ShellStart(showPorts = true))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_DevServers_TabletLandscape() = Shell(FormFactorFixtures.portsState(), start = ShellStart(showPorts = true))

// ScreenSessions
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun FF_ScreenSessions_FoldPortrait() = Shell(FormFactorFixtures.screensState(), start = ShellStart(showScreens = true))

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun FF_ScreenSessions_FoldLandscape() = Shell(FormFactorFixtures.screensState(), start = ShellStart(showScreens = true))

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun FF_ScreenSessions_TabletPortrait() = Shell(FormFactorFixtures.screensState(), start = ShellStart(showScreens = true))

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun FF_ScreenSessions_TabletLandscape() = Shell(FormFactorFixtures.screensState(), start = ShellStart(showScreens = true))
