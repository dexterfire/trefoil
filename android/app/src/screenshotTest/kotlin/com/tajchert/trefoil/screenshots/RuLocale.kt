package com.tajchert.trefoil.screenshots

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/** The screenshot renderer (layoutlib) runs with the test JVM's locale, pinned to en-US in build.gradle.kts because
 *  layoutlib formats binary-XML float attributes with the JVM default and would reject its own comma-decimal output.
 *  Resource strings would then resolve to the English mirror instead of the Russian product text.
 *
 *  Previews whose visible strings come from resources wrap their content here: [LocalResources] is replaced with a
 *  Resources stub that answers string ids straight from [ruStrings] — the Russian default locale table — instead of
 *  layoutlib's locale-driven lookup. Everything else (metrics, assets) is untouched. */
@Composable
internal fun WithRuLocale(content: @Composable () -> Unit) {
    val base = LocalResources.current
    val ru = object : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int): String = ruStrings[id] ?: base.getString(id)
        override fun getString(id: Int, vararg formatArgs: Any?): String =
            ruStrings[id]?.let { String.format(it, *formatArgs) } ?: base.getString(id, *formatArgs)
    }
    CompositionLocalProvider(LocalResources provides ru, LocalContext provides LocalContext.current) { content() }
}
