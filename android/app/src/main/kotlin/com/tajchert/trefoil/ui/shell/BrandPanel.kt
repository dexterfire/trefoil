package com.tajchert.trefoil.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilMark
import com.tajchert.trefoil.ui.TrefoilType

/**
 * Start region of a split window while no machine is connected (first run, machine chooser): there is no thread list to show, so the
 * region stays a quiet brand panel with no controls and Machines fills the other side of the hinge.
 */
@Composable
internal fun BrandPanel(modifier: Modifier = Modifier) {
    val c = Tf.colors
    Column(modifier.background(c.s1).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        TrefoilMark(Modifier.size(48.dp))
        Text("Trefoil", style = TrefoilType.rowTitle, color = c.text, modifier = Modifier.semantics { heading() })
        Text("Connect a machine to start", style = TrefoilType.meta, color = c.muted, textAlign = TextAlign.Center)
    }
}
