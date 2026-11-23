package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.UsageFailure
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.ui.UsageScreen
import com.tajchert.trefoil.ui.UsageUiState
import com.tajchert.trefoil.ui.TrefoilTheme

@Composable
private fun UsageShot(
    tab: Int, dark: Boolean = false, usage: UsageUiState = UsageFixtures.usage(),
    providers: List<ProviderInstance> = UsageFixtures.providers, offline: Boolean = false, back: Boolean = true,
) {
    val state = UsageFixtures.state(providers)
    TrefoilTheme(darkTheme = dark) {
        UsageScreen(if (offline) state.copy(connection = ConnectionStatus.Disconnected, canRead = false, canWrite = false) else state, usage, onBack = if (back) ({}) else null, onRefresh = {}, onSelectRange = {}, onRetry = {}, initialTab = tab)
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_Light() { UsageShot(0) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Usage_Limits_Dark() { UsageShot(0, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_NearLimit() { UsageShot(0, providers = UsageFixtures.nearLimit) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_Exceeded() { UsageShot(0, providers = UsageFixtures.exceeded) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Week() { UsageShot(1) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Usage_Cost_Month_Dark() { UsageShot(1, dark = true, usage = UsageFixtures.usage(UsageRange.Month)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Today() { UsageShot(1, usage = UsageFixtures.usage(UsageRange.Today)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Tokens_Week() { UsageShot(2) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Tokens_Month() { UsageShot(2, usage = UsageFixtures.usage(UsageRange.Month)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Loading() { UsageShot(1, usage = UsageUiState(range = UsageRange.Week, loading = true)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Error() {
    UsageShot(1, usage = UsageUiState(range = UsageRange.Week, failure = UsageFailure.Failed, failureMessage = "Could not read usage from the server."))
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_Offline() { UsageShot(0, offline = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Offline_NoMonth() {
    val cached = UsageFixtures.usage(UsageRange.Month)
    UsageShot(1, usage = cached.copy(reports = cached.reports - UsageRange.Month), offline = true)
}

/*
 * Adaptive sizes: the screen alone, at the size it gets in the shell. TabletLandscape 1200x800 and Desktop 1840x1080 sit
 * beside the 80dp rail, TabletPortrait 720x1280 too (no back arrow); FoldPortrait 673x841 has no rail.
 */
private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Usage_Limits_TabletLandscape() = UsageShot(0, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = NIGHT)
@Composable fun Usage_Limits_TabletLandscape_Dark() = UsageShot(0, dark = true, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Usage_Tokens_TabletLandscape() = UsageShot(2, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Usage_Cost_TabletLandscape() = UsageShot(1, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = NIGHT)
@Composable fun Usage_Cost_TabletLandscape_Dark() = UsageShot(1, dark = true, usage = UsageFixtures.usage(UsageRange.Month), back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Usage_Offline_TabletLandscape() = UsageShot(0, offline = true, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Usage_Limits_TabletPortrait() = UsageShot(0, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Usage_Cost_TabletPortrait() = UsageShot(1, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Usage_Offline_TabletPortrait() = UsageShot(1, offline = true, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Usage_Limits_FoldPortrait() = UsageShot(0)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Usage_Cost_FoldPortrait() = UsageShot(1)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Usage_Tokens_FoldPortrait() = UsageShot(2)

@PreviewTest @Preview(showBackground = true, widthDp = 1840, heightDp = 1080)
@Composable fun Usage_Limits_Desktop() = UsageShot(0, back = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1840, heightDp = 1080, uiMode = NIGHT)
@Composable fun Usage_Cost_Desktop_Dark() = UsageShot(1, dark = true, usage = UsageFixtures.usage(UsageRange.Month), back = false)
