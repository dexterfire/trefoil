package com.tajchert.trefoil.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.ui.StatusDot
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilMark
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.threadIndicator
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/** Longest line of cards and the button; the block stays this narrow however wide the pane is. */
private val EmptyDetailMaxWidth = 440.dp
private const val MaxNeedsYouCards = 3

/** Heading of the empty detail pane: the number of waiting threads when some wait, otherwise the invitation to pick one. */
internal fun emptyDetailHeading(needsYou: Int): String = when {
    needsYou <= 0 -> "Pick a thread"
    needsYou == 1 -> "1 thread needs you"
    else -> "$needsYou threads need you"
}

/**
 * Detail pane of two panes while nothing is selected on the Threads destination: the brand mark, a heading, up to three threads that
 * need the user and a New thread button that is enabled exactly when the rail's button is. The block scrolls instead of clipping in a
 * narrow end region or at compact height. [leading] is the top-start control (Show thread list) while the list is collapsed.
 */
@Composable
internal fun EmptyDetail(
    needsYou: List<ThreadSummary>, newThreadEnabled: Boolean, onOpenThread: (String) -> Unit, onNewThread: () -> Unit,
    modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
) {
    val c = Tf.colors
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = EmptyDetailMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TrefoilMark(Modifier.size(48.dp))
                Text(emptyDetailHeading(needsYou.size), style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp).semantics { heading() })
                if (needsYou.isEmpty()) Text("Choose a thread from the list, or start a new one.", style = TrefoilType.preview, color = c.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                if (needsYou.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    needsYou.take(MaxNeedsYouCards).forEach { NeedsYouCard(it) { onOpenThread(it.id) } }
                }
                Button(onNewThread, Modifier.padding(top = 24.dp).heightIn(min = 48.dp), enabled = newThreadEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.s2, disabledContentColor = c.muted)) {
                    Icon(TfIcons.Plus, null, Modifier.size(18.dp))
                    Text("New thread", Modifier.padding(start = 8.dp))
                }
            }
        }
        if (leading != null) Box(Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp)) { leading() }
    }
}

@Composable
private fun NeedsYouCard(thread: ThreadSummary, onClick: () -> Unit) {
    val c = Tf.colors
    val label = threadIndicator(thread).label
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = c.s1, contentColor = c.text, modifier = Modifier.fillMaxWidth().focusRing().handPointer()) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusDot(c.amber)
            Column(Modifier.weight(1f)) {
                Text(thread.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
