package com.tajchert.trefoil.ui.ports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.DiscoveredLocalServer
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType

/**
 * Thread-header entry point for the ports screen: "Dev server :5173", or "N dev servers" for several.
 * [servers] are the ones owned by this thread; the chip draws nothing when there are none.
 */
@Composable
fun ThreadPortsChip(servers: List<DiscoveredLocalServer>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ports = chipPorts(servers)
    if (ports.isEmpty()) return
    val c = Tf.colors
    val label = if (ports.size == 1) stringResource(R.string.ports_chip_single, ports[0]) else stringResource(R.string.ports_chip_multiple, ports.size)
    val description = if (ports.size == 1) stringResource(R.string.ports_chip_description_single, ports[0]) else stringResource(R.string.ports_chip_description_multiple, ports.size)
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
        modifier = modifier.padding(end = 2.dp).semantics { contentDescription = description; role = Role.Button },
    ) {
        Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TfIcons.Globe, null, Modifier.size(16.dp))
            Text(label, style = TrefoilType.meta.copy(color = c.text), maxLines = 1)
        }
    }
}
