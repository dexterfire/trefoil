package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.ui.ListPreferences
import com.tajchert.trefoil.ui.ProjectAppearanceSheet
import com.tajchert.trefoil.ui.ProjectColor
import com.tajchert.trefoil.ui.ProjectIcon
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilTheme

/** The trefoil project pinned on top, website below, both open: the everyday grouped list. */
private val groupedPrefs = ListPreferences(
    pinnedProjects = setOf(ScreenshotFixtures.trefoil.id),
    expandedProjects = setOf(ScreenshotFixtures.trefoil.id, ScreenshotFixtures.website.id),
    expansionChosen = true,
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_Grouped_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Grouped_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

/** The trefoil group folded to its header, the website group open: the fold is remembered per project. */
private val foldedPrefs = ListPreferences(
    pinnedProjects = setOf(ScreenshotFixtures.trefoil.id),
    expandedProjects = setOf(ScreenshotFixtures.website.id),
    expansionChosen = true,
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560)
@Composable
fun ThreadList_GroupFolded_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = foldedPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_GroupFolded_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = foldedPrefs)         }
}

/** Every thread in the project waits: the amber needs-you band on the group header. */
private fun needsYouState() = ScreenshotFixtures.connectedState(listOf(
    ScreenshotFixtures.thread("t-ask1", "Approve the release", pendingRequestKind = "approval", updatedMinutesAgo = 5,
        latestMessage = "Ship 0.25.0?"),
    ScreenshotFixtures.thread("t-ask2", "Pick a codename", project = ScreenshotFixtures.website, pendingRequestKind = "user_input", updatedMinutesAgo = 12,
        latestMessage = "Which name for the next milestone?"),
))

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 420)
@Composable
fun ThreadList_NeedsYouBand_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(needsYouState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_NeedsYouBand_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(needsYouState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

/** A thread whose project left the environment: the orphan bucket under the "Без проекта" header. */
private val goneProject = Project(id = "p-gone", title = "gone", path = "/home/dev/gone")

private fun orphansState() = ScreenshotFixtures.connectedState(listOf(
    ScreenshotFixtures.thread("t-orphan", "Fix the CI flake", project = goneProject, updatedMinutesAgo = 8,
        latestMessage = "Still failing on Windows."),
    ScreenshotFixtures.thread("t-trefoil", "Tidy the README", updatedMinutesAgo = 50, latestMessage = "Done, see the diff."),
))

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun ThreadList_Orphans_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(orphansState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Orphans_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(orphansState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

/** The trefoil project with a picked glyph and colour: rocket on teal, kept per project id. */
private val lookPrefs = ListPreferences(
    projectIcons = mapOf(ScreenshotFixtures.trefoil.id to "rocket"),
    projectColors = mapOf(ScreenshotFixtures.trefoil.id to "teal"),
    expandedProjects = setOf(ScreenshotFixtures.trefoil.id, ScreenshotFixtures.website.id),
    expansionChosen = true,
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_ProjectLook_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = lookPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_ProjectLook_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = lookPrefs)         }
}

/** The picker with a picked glyph and colour: the tile previews the choice live. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760)
@Composable
fun ProjectAppearance_Picked_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale {
        Box(Modifier.fillMaxSize()) { ProjectAppearanceSheet("trefoil", ProjectIcon.byId("rocket"), ProjectColor.byId("amber"), onPickIcon = {}, onPickColor = {}, onClose = {}) }
            }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ProjectAppearance_Picked_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale {
        Box(Modifier.fillMaxSize()) { ProjectAppearanceSheet("trefoil", ProjectIcon.byId("rocket"), ProjectColor.byId("amber"), onPickIcon = {}, onPickColor = {}, onClose = {}) }
            }
}

/** The grouped list at a narrow phone width: Russian chrome and the jump chip stay inside the window. */
@PreviewTest
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun ThreadList_GroupedNarrow_Light() {
    TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = groupedPrefs)         }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_GroupedNarrow_Dark() {
    TrefoilTheme(darkTheme = true) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, preferences = groupedPrefs)         }
}
