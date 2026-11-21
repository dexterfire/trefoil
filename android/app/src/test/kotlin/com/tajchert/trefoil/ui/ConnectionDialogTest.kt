package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sshFieldsSurviveTestingAndFailureAndUsernameIsTrimmed() {
        val busy = mutableStateOf(false)
        val attempt = mutableStateOf(ConnectionAttempt())
        var submitted: SshConnectionInput? = null
        compose.setContent { MaterialTheme { SshConnectionDialog(busy.value, null, {}, {}, attempt.value) { submitted = it } } }
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput(" host.local ")
        compose.onNodeWithText("SSH username").performTextInput("alice ")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("alice", submitted?.username); busy.value = true; attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Testing) }
        compose.onNodeWithText("SSH username").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("alice ").assertExists()
        compose.runOnIdle { busy.value = false; attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Failed, "Authentication failed. Check your credentials.") }
        compose.onNodeWithText("Authentication failed. Check your credentials.").assertIsDisplayed()
        compose.onNodeWithText("SSH username").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("alice ").assertExists()
    }

    @Test fun sshFormSurvivesHostKeyOverlayAndClosesAfterSuccessfulAttempt() {
        val attempt = mutableStateOf(ConnectionAttempt())
        val hostKey = mutableStateOf<SshHostKey?>(null)
        compose.setContent { TrefoilApp(
            state = ClientState(), savedEnvironments = emptyList(), onPair = { _, _, _ -> }, onConnect = {},
            onSelectThread = {}, onSend = {}, onStop = {}, onRespond = { _, _, _ -> },
            draft = null, onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {}, onReconnect = {}, onDisconnect = {}, onForget = {},
            onSshConnect = { attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Testing) },
            sshHostKey = hostKey.value, onTrustSsh = { hostKey.value = null }, connectionAttempt = attempt.value,
        ) }
        compose.onNodeWithContentDescription("Connect over SSH").performScrollTo().performClick()
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput("host.local")
        compose.onNodeWithText("SSH username").performTextInput("alice")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.onNodeWithText("SSH username").assertExists()
        compose.runOnIdle {
            attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.AwaitingTrust)
            hostKey.value = SshHostKey("host.local", 22, "ssh-ed25519", "SHA256:fixture")
        }
        compose.onNodeWithText("Verify SSH host key").assertIsDisplayed()
        compose.onNodeWithText("Trust and connect").performClick()
        compose.onNodeWithText("SSH username").performScrollTo()
        compose.onNodeWithText("alice").assertExists()
        compose.runOnIdle { attempt.value = ConnectionAttempt(2, ConnectionKind.Ssh, ConnectionPhase.Failed, "Authentication failed.") }
        compose.onNodeWithText("Authentication failed.").assertIsDisplayed()
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.runOnIdle { attempt.value = ConnectionAttempt(3, ConnectionKind.Ssh, ConnectionPhase.Succeeded) }
        compose.onNodeWithText("SSH username").assertDoesNotExist()
    }

    @Test fun pastedPublicKeyShowsInlineAndIsRevealed() {
        compose.setContent { MaterialTheme { SshConnectionDialog(false, SshConnectionInput("Server", "host", "alice", authMethod = SshAuthMethod.PrivateKey), {}, {}) {} } }
        compose.onNodeWithText("Paste private key").performScrollTo().performTextInput("ssh-ed25519 AAAA public-comment")
        compose.onNodeWithText("This is a public key. Choose the private key file without the .pub extension.").assertIsDisplayed()
    }

    @Test fun pairingFieldsRetainedDuringFailureAndPrivateHttpRequiresOptIn() {
        val busy = mutableStateOf(false)
        val attempt = mutableStateOf(ConnectionAttempt())
        var accepted: Boolean? = null
        compose.setContent { MaterialTheme { PairDialog(busy.value, {}, attempt.value) { _, _, insecure -> accepted = insecure } } }
        compose.onNodeWithText("Display name").performTextInput("LAN")
        compose.onNodeWithText("Pairing link").performTextInput("http://192.168.1.5:3773/?token=secret")
        compose.onNodeWithText("Allow unencrypted HTTP on this private network").performScrollTo().assertExists()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertNull(accepted) }
        compose.onNodeWithText("Choose whether to allow HTTP on this private network.").assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(true, accepted); busy.value = true }
        compose.onNodeWithText("Display name").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("LAN").assertExists()
        compose.runOnIdle { busy.value = false; attempt.value = ConnectionAttempt(1, ConnectionKind.Pairing, ConnectionPhase.Failed, "Pairing failed. Check the link.") }
        compose.onNodeWithText("Pairing failed. Check the link.").assertIsDisplayed()
    }

    private val deepLink = "t3code://pair?pairingUrl=https%3A%2F%2Fexample.test%2Fpair%23token%3Dfixture-token"
    private val directLink = "https://example.test/pair#token=fixture-token"

    @Test fun prefilledPairLinkIsNeverSubmittedUntilConfirmed() {
        var submitted: String? = null
        compose.setContent { MaterialTheme { PairDialog(false, {}, initialLink = deepLink) { url, _, _ -> submitted = url } } }
        compose.onNodeWithText("Display name").performTextInput("Fixture")
        compose.waitForIdle()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(deepLink, submitted) }
    }

    @Test fun unparsablePrefilledLinkUsesTheNormalErrorPath() {
        var submitted = false
        compose.setContent { MaterialTheme { PairDialog(false, {}, initialLink = "t3code://pair?junk") { _, _, _ -> submitted = true } } }
        compose.onNodeWithText("Display name").performTextInput("Fixture")
        compose.onNodeWithText("Pair").performClick()
        compose.onNodeWithText("Enter a valid pairing link.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(submitted) }
    }

    @Test fun pasteButtonReadsClipboardOnlyWhenTapped() {
        var reads = 0
        val text = mutableStateOf<String?>(null)
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) {}
            override fun getText(): AnnotatedString? { reads++; return text.value?.let(::AnnotatedString) }
        }
        var submitted: String? = null
        compose.setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) { MaterialTheme { PairDialog(false, {}) { url, _, _ -> submitted = url } } } }
        compose.onNodeWithText("Display name").performTextInput("Fixture")
        compose.runOnIdle { assertEquals(0, reads) }
        // Empty clipboard: a no-op, the field stays empty.
        compose.onNodeWithText("Paste link").performClick()
        compose.onNodeWithText("Pair").performClick()
        compose.onNodeWithText("Enter a valid pairing link.").assertIsDisplayed()
        compose.runOnIdle { text.value = " $directLink\n"; assertNull(submitted) }
        compose.onNodeWithText("Paste link").performClick()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(directLink, submitted); assertEquals(2, reads) }
    }

    @Test fun pairLinkRequestOpensPrefilledDialogWithoutSubmittingAndReplacesOnNewRequest() {
        val request = mutableStateOf<Pair<Long, String>?>(null)
        val submitted = mutableListOf<String>()
        compose.setContent { TrefoilApp(
            state = ClientState(), savedEnvironments = emptyList(), onPair = { url, _, _ -> submitted += url }, onConnect = {},
            onSelectThread = {}, onSend = {}, onStop = {}, onRespond = { _, _, _ -> },
            draft = null, onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {}, onReconnect = {}, onDisconnect = {}, onForget = {},
            onSshConnect = {}, pairLinkRequest = request.value,
        ) }
        compose.onNodeWithText("Pair an environment").assertDoesNotExist()
        compose.runOnIdle { request.value = 1L to deepLink }
        compose.onNodeWithText("Pair an environment").assertIsDisplayed()
        compose.runOnIdle { assertTrue(submitted.isEmpty()); request.value = 2L to directLink }
        compose.onNodeWithText("Pair an environment").assertIsDisplayed()
        compose.onNodeWithText("Display name").performTextInput("Fixture")
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(listOf(directLink), submitted) }
    }

    @Test fun pairLinkRequestDuringOutstandingAttemptSurvivesItsSuccessAndYieldsToSshPrompts() {
        val request = mutableStateOf<Pair<Long, String>?>(null)
        val attempt = mutableStateOf(ConnectionAttempt())
        val hostKey = mutableStateOf<SshHostKey?>(null)
        compose.setContent { TrefoilApp(
            state = ClientState(), savedEnvironments = emptyList(), onPair = { _, _, _ -> }, onConnect = {},
            onSelectThread = {}, onSend = {}, onStop = {}, onRespond = { _, _, _ -> },
            draft = null, onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {}, onReconnect = {}, onDisconnect = {}, onForget = {},
            onSshConnect = {}, sshHostKey = hostKey.value, connectionAttempt = attempt.value, pairLinkRequest = request.value,
        ) }
        compose.runOnIdle { request.value = 1L to deepLink }
        compose.onNodeWithText("Display name").performTextInput("Fixture")
        compose.onNodeWithText("Pair").performClick()
        // A newer link replaces the dialog while the first attempt is outstanding; that attempt finishing must not close it.
        compose.runOnIdle { request.value = 2L to directLink }
        compose.runOnIdle { attempt.value = ConnectionAttempt(1, ConnectionKind.Pairing, ConnectionPhase.Succeeded) }
        compose.onNodeWithText("Pair an environment").assertIsDisplayed()
        // An SSH host-key prompt keeps the screen; the pair dialog is not stacked behind or over it.
        compose.runOnIdle { hostKey.value = SshHostKey("host.local", 22, "ssh-ed25519", "SHA256:fixture") }
        compose.onNodeWithText("Pair an environment").assertDoesNotExist()
        compose.onNodeWithText("Verify SSH host key").assertIsDisplayed()
    }

    @Test fun pairLinkNeverCancelsASubmittedSshAttemptAwaitingTrust() {
        val request = mutableStateOf<Pair<Long, String>?>(null)
        val hostKey = mutableStateOf<SshHostKey?>(null)
        var cancelled = 0
        compose.setContent { TrefoilApp(
            state = ClientState(), savedEnvironments = emptyList(), onPair = { _, _, _ -> }, onConnect = {},
            onSelectThread = {}, onSend = {}, onStop = {}, onRespond = { _, _, _ -> },
            draft = null, onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {}, onReconnect = {}, onDisconnect = {}, onForget = {},
            onSshConnect = {}, onCancelSsh = { cancelled++ }, sshHostKey = hostKey.value, pairLinkRequest = request.value,
        ) }
        compose.onNodeWithContentDescription("Connect over SSH").performScrollTo().performClick()
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput("host.local")
        compose.onNodeWithText("SSH username").performTextInput("alice")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.runOnIdle { hostKey.value = SshHostKey("host.local", 22, "ssh-ed25519", "SHA256:fixture") }
        compose.onNodeWithText("Verify SSH host key").assertIsDisplayed()
        compose.runOnIdle { request.value = 1L to deepLink }
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onNodeWithText("Verify SSH host key").assertIsDisplayed()
        compose.onNodeWithText("Pair an environment").assertDoesNotExist()
    }
}
