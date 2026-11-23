package com.tajchert.trefoil.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.DialogMarginDp
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.adaptive.dialogMaxWidthDp
import com.tajchert.trefoil.ui.adaptive.dialogRegion
import com.tajchert.trefoil.ui.adaptive.usesDialog
import com.tajchert.trefoil.ui.adaptive.windowSizeDp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable private fun mono14() = TrefoilType.mono.copy(fontSize = 14.sp, color = Tf.colors.text)

private const val MAX_KEY_BYTES = 256 * 1024

private val SshAuthMethodSaver = Saver<SshAuthMethod, String>(save = { it.name }, restore = { name -> SshAuthMethod.entries.firstOrNull { it.name == name } })

@Composable
internal fun SshConnectionDialog(busy: Boolean, initial: SshConnectionInput?, dismiss: () -> Unit, pickerActive: (Boolean) -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), connect: (SshConnectionInput) -> Unit) {
    // Credentials deliberately never enter rememberSaveable / Android saved state: password, privateKey and passphrase stay plain remember
    // (lost on rotation by design). Only the non-secret connection fields below survive a configuration change.
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 22).toString()) }
    var method by rememberSaveable(stateSaver = SshAuthMethodSaver) { mutableStateOf(initial?.authMethod ?: SshAuthMethod.Password) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var saveLogin by rememberSaveable { mutableStateOf(false) }
    var home by rememberSaveable { mutableStateOf(initial?.t3Home ?: "~/.t3") }
    var executable by rememberSaveable { mutableStateOf(initial?.executable.orEmpty()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerActive(false)
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_KEY_BYTES + 1 - output.size()))
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                if (output.size() > MAX_KEY_BYTES) break
                            }
                            val bytes = output.toByteArray()
                            require(bytes.size <= MAX_KEY_BYTES)
                            bytes.toString(Charsets.UTF_8)
                        } ?: kotlin.error("Cannot open file")
                    }
                }
                imported.onSuccess { privateKey = it; error = privateKeyValidationError(it) }.onFailure { error = "Could not read private key. Choose a readable file of at most 256 KiB, or paste it below." }
                importing = false
            }
        }
    }
    val enabled = !busy && !importing
    val errorReveal = remember { BringIntoViewRequester() }
    val shownError = error ?: attempt.message.takeIf { attempt.phase == ConnectionPhase.Failed }
    LaunchedEffect(shownError) { if (shownError != null) errorReveal.bringIntoView() }
    // From 600dp the dialog is 560dp wide with name | username and host | port side by side, unless the user's font scale is large
    // enough that two columns would clip labels. Below 600dp it is the AlertDialog it has always been, one field per row.
    val wide = usesDialog(windowSizeDp().width.value)
    val twoColumn = wide && LocalDensity.current.fontScale <= TWO_COLUMN_MAX_FONT_SCALE
    val title = if (initial == null) "Connect over SSH" else "SSH login required"
    val nameField: @Composable (Modifier) -> Unit = { OutlinedTextField(name, { name = it; error = null }, label = { Text("Display name") }, singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = it) }
    val hostField: @Composable (Modifier) -> Unit = { OutlinedTextField(host, { host = it; error = null }, label = { Text("IP address or hostname") }, textStyle = mono14(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = it) }
    val usernameField: @Composable (Modifier) -> Unit = { OutlinedTextField(username, { username = it; error = null }, label = { Text("SSH username") }, textStyle = mono14(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = it) }
    val portField: @Composable (Modifier) -> Unit = { OutlinedTextField(port, { port = it; error = null }, label = { Text("SSH port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = it) }
    val scroll = rememberScrollState()
    val body: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial == null) "Connect to an installed, running T3 Code server through an encrypted SSH tunnel." else "Enter credentials to reconnect to ${initial.name}.", color = Tf.colors.muted)
            if (twoColumn) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { nameField(Modifier.weight(1f)); usernameField(Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { hostField(Modifier.weight(7f)); portField(Modifier.weight(3f)) }
            } else {
                nameField(Modifier.fillMaxWidth()); hostField(Modifier.fillMaxWidth()); usernameField(Modifier.fillMaxWidth()); portField(Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(method == SshAuthMethod.Password, { method = SshAuthMethod.Password; error = null }, enabled = enabled)
                Text("Password")
                RadioButton(method == SshAuthMethod.PrivateKey, { method = SshAuthMethod.PrivateKey; error = null }, enabled = enabled)
                Text("Private key")
            }
            if (method == SshAuthMethod.Password) {
                OutlinedTextField(password, { password = it; error = null }, label = { Text("SSH password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedButton(onClick = { pickerActive(true); runCatching { importer.launch(arrayOf("*/*")) }.onFailure { pickerActive(false); error = "No file picker is available. Paste the private key instead." } }, enabled = enabled) { Text(if (importing) "Reading key…" else "Import private key file") }
                OutlinedTextField(privateKey, { privateKey = it; error = privateKeyValidationError(it) }, label = { Text("Paste private key") }, textStyle = mono14(), visualTransformation = PasswordVisualTransformation(), minLines = 2, maxLines = 4, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("OpenSSH or PEM private key, up to 256 KiB.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(passphrase, { passphrase = it; error = null }, label = { Text("Key passphrase (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saveLogin, { saveLogin = it }, enabled = enabled)
                Text("Save login credentials on this device", Modifier.weight(1f))
            }
            Text("Saved login credentials are encrypted. Otherwise, you will be asked again when reconnecting.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }, enabled = enabled) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
            if (advanced) {
                OutlinedTextField(home, { home = it; error = null }, label = { Text("T3 data directory") }, textStyle = mono14(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(executable, { executable = it; error = null }, label = { Text("T3 executable (optional)") }, textStyle = mono14(), singleLine = true, enabled = enabled, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = Tf.colors.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = Tf.colors.red, modifier = Modifier.bringIntoViewRequester(errorReveal)) }
        }
    }
    val confirm: @Composable () -> Unit = {
        Button(enabled = enabled, onClick = {
            val parsedPort = port.toIntOrNull()
            error = sshInputValidationError(SshConnectionInput(name, host, username, parsedPort ?: 0, method, password, privateKey, passphrase, t3Home = home))
            if (error == null && parsedPort != null) connect(SshConnectionInput(
                name = name.trim(), host = host.trim(), username = username.trim(), port = parsedPort,
                authMethod = method, password = if (method == SshAuthMethod.Password) password else "",
                privateKey = if (method == SshAuthMethod.PrivateKey) privateKey else "",
                passphrase = if (method == SshAuthMethod.PrivateKey) passphrase else "", rememberCredentials = saveLogin,
                t3Home = home.trim(), executable = executable.trim(), savedEnvironmentId = initial?.savedEnvironmentId,
            ))
        }) {
            if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Tf.colors.onAccent); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "Testing connection…" else "Connect")
        }
    }
    val cancel: @Composable () -> Unit = { TextButton(onClick = dismiss) { Text("Cancel", color = Tf.colors.muted) } }
    if (wide) SshWideDialog(dismiss, AdaptiveSpec.DialogSshWidth.dp, TfIcons.Terminal, title, scroll, body, confirm, cancel)
    else AlertDialog(onDismissRequest = dismiss, containerColor = Tf.colors.sheet, shape = RoundedCornerShape(28.dp), icon = { Icon(TfIcons.Terminal, null, tint = Tf.colors.accent) }, title = { Text(title) }, text = body, confirmButton = confirm, dismissButton = cancel)
}

private const val TWO_COLUMN_MAX_FONT_SCALE = 1.15f
private const val SSH_DIALOG_MAX_HEIGHT_DP = 720
private const val SSH_DIALOG_WINDOW_MARGIN_DP = 48

/**
 * [AlertDialog]'s content on a [width]dp frame for windows from 600dp: at most 720dp (and 48dp less than the window) high, the [body]
 * scrolls inside it (state [scroll]) and the actions stay pinned below, so the primary action is always on screen (also with the keyboard
 * open and at large font scales). A hairline marks the header and footer edges while the body can still scroll that way.
 * Dismisses on Back, scrim tap and Escape like the AlertDialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshWideDialog(
    dismiss: () -> Unit, width: Dp, icon: ImageVector, title: String, scroll: ScrollState, body: @Composable () -> Unit, confirm: @Composable () -> Unit, cancel: @Composable () -> Unit,
) {
    val c = Tf.colors
    val window = windowSizeDp()
    // On a separating or half-open fold the dialog sits centred in one half, as TfAdaptiveSheet's does (see dialogRegion); a flat window changes nothing.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val region = dialogRegion(window.width.value, window.height.value, LocalFold.current, rtl, imeVisible)
    val flatMaxHeight = minOf(SSH_DIALOG_MAX_HEIGHT_DP.toFloat(), window.height.value - SSH_DIALOG_WINDOW_MARGIN_DP)
    val maxHeight = (if (region == null) flatMaxHeight else minOf(flatMaxHeight, (region.height - 2 * DialogMarginDp).coerceAtLeast(0f))).dp
    val maxWidth = dialogMaxWidthDp(width.value, region).dp
    // The keyboard only needs making room for when the dialog spans the window's height; in a tabletop top half it sits below.
    val imeAware = region == null || region.height >= window.height.value
    // The region is in window coordinates (left and right, as the hinge is), so the padding must not mirror in RTL.
    val outer = if (region == null) Modifier else Modifier.fillMaxSize()
        .absolutePadding(left = region.left.dp, top = region.top.dp, right = (window.width.value - region.right).dp, bottom = (window.height.value - region.bottom).dp)
        // The region's Box covers the window, so outside taps arrive here (the window's own outside-tap never fires); taps on the dialog are consumed below.
        .pointerInput(dismiss) { detectTapGestures { dismiss() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(outer, contentAlignment = Alignment.Center) {
            Surface(
                Modifier.padding(horizontal = 24.dp).widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight)
                    .then(if (imeAware) Modifier.imePadding() else Modifier)
                    .then(if (region == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { } })
                    .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { dismiss(); true } else false },
                shape = RoundedCornerShape(28.dp), color = c.sheet, contentColor = c.text, tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Icon(icon, null, Modifier.align(Alignment.CenterHorizontally), tint = c.accent)
                    Text(title, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
                    // Fixed 1dp slots that only change colour, so showing a hairline never changes the body's height (and so its own scroll range).
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.canScrollBackward) c.line else Color.Transparent))
                    Box(Modifier.weight(1f, fill = false)) { body() }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.canScrollForward) c.line else Color.Transparent))
                    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) { cancel(); confirm() }
                }
            }
        }
    }
}

@Composable
internal fun SshHostKeyDialog(key: SshHostKey, trust: () -> Unit, cancel: () -> Unit) {
    val scroll = rememberScrollState()
    val body: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Confirm this fingerprint with the server before sending your login credentials.", color = Tf.colors.muted)
            SelectionContainer { Text("${key.host}:${key.port}\n${key.keyType}\n${key.fingerprint}", style = TrefoilType.code, modifier = Modifier.fillMaxWidth().background(Tf.colors.s1, RoundedCornerShape(14.dp)).border(1.dp, Tf.colors.line, RoundedCornerShape(14.dp)).padding(12.dp)) }
            Text("On the server, compare with:")
            SelectionContainer { Text("for key in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$key\" -E sha256; done", style = TrefoilType.code, modifier = Modifier.fillMaxWidth().background(Tf.colors.s1, RoundedCornerShape(14.dp)).border(1.dp, Tf.colors.line, RoundedCornerShape(14.dp)).padding(12.dp)) }
            Text("Trust only if the key type and SHA256 fingerprint match.")
        }
    }
    val trustButton: @Composable () -> Unit = { Button(onClick = trust) { Text("Trust and connect") } }
    val cancelButton: @Composable () -> Unit = { TextButton(onClick = cancel) { Text("Cancel", color = Tf.colors.muted) } }
    // 520dp from 600dp so the fingerprint and the command do not wrap inside a token; the phone keeps the AlertDialog.
    if (usesDialog(windowSizeDp().width.value)) SshWideDialog(cancel, AdaptiveSpec.DialogHostKeyWidth.dp, TfIcons.Shield, "Verify SSH host key", scroll, body, trustButton, cancelButton)
    else AlertDialog(onDismissRequest = cancel, containerColor = Tf.colors.sheet, shape = RoundedCornerShape(28.dp), icon = { Icon(TfIcons.Shield, null, tint = Tf.colors.accent) }, title = { Text("Verify SSH host key") }, text = body, confirmButton = trustButton, dismissButton = cancelButton)
}
