package com.tajchert.trefoil

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tajchert.trefoil.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.downloads.CompletedDownload
import com.tajchert.trefoil.downloads.DownloadFileActions
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private var usageRequest by mutableStateOf<Pair<Long, UsageWidgetTab>?>(null)
    private fun handleUsageIntent(intent: Intent?) {
        parseUsageWidgetIntent(intent)?.let { usageRequest = ((usageRequest?.first ?: 0) + 1) to it }
    }
    private var pairLinkRequest by mutableStateOf<Pair<Long, String>?>(null)
    private fun handlePairLinkIntent(intent: Intent?) {
        val link = parsePairLinkIntent(intent) ?: return
        pairLinkRequest = ((pairLinkRequest?.first ?: 0) + 1) to link
        // The link is a single-use credential: drop it from the activity intent so nothing can replay it.
        setIntent(Intent(Intent.ACTION_MAIN))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsageIntent(intent)
        handlePairLinkIntent(intent)
    }
    private val model: TrefoilViewModel by viewModels()
    private val fileActions by lazy { DownloadFileActions(this) }
    private fun openDownloaded(file: CompletedDownload, install: Boolean = false, share: Boolean = false) {
        try {
            if ((install || file.isApk && !share) && !fileActions.canInstallPackages()) {
                Toast.makeText(this, "Allow installs for Trefoil, then return and tap Install.", Toast.LENGTH_LONG).show()
                startActivity(fileActions.unknownSourcesIntent())
            } else startActivity(when { share -> fileActions.shareIntent(file); install -> fileActions.installIntent(file); else -> fileActions.openIntent(file) })
        } catch (_: Exception) { Toast.makeText(this, "Could not open this download. Check that the file is still available.", Toast.LENGTH_LONG).show() }
    }
    /** Default-network changes in the foreground (and in the background while keep-alive runs): a better-route or link check, or a pending reconnect once online. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { model.onDefaultNetwork(network.networkHandle) }
        override fun onLost(network: Network) { model.onDefaultNetworkLost(network.networkHandle) }
    }
    private var networkCallbackRegistered = false
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        // Too many callbacks or a missing permission: route checks still run every minute.
        networkCallbackRegistered = try { connectivity.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper())); true }
        catch (_: RuntimeException) { false }
    }
    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) } catch (_: RuntimeException) {}
        networkCallbackRegistered = false
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denied keeps the setting on: the service still runs, Android only hides its notification.
    }
    /** The "Keep connected in background" toggle; turning it on asks for the notification permission on Android 13+. */
    fun onKeepConnectedChange(enabled: Boolean) {
        model.setKeepConnected(enabled)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onStart() {
        super.onStart(); model.resume()
        registerNetworkCallback()
    }
    override fun onStop() {
        model.pause()
        // While keep-alive runs, link checks and reconnect-when-online need network changes in the background too.
        if (!model.keepAliveActive.value) unregisterNetworkCallback()
        super.onStop()
    }
    override fun onDestroy() {
        // Accepted edge case: a destroyed activity (keep-alive still running) drops network changes until the next onStart.
        unregisterNetworkCallback()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch consumes the widget intent; recreation (rotation) must not reopen Usage.
        if (savedInstanceState == null) { handleUsageIntent(intent); handlePairLinkIntent(intent) }
        enableEdgeToEdge()
        lifecycleScope.launch {
            // Keep-alive ending in the background: stop listening for network changes until the next start.
            model.keepAliveActive.collect { active -> if (!active && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) unregisterNetworkCallback() }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val screenViewer by model.screenSessions.state.collectAsStateWithLifecycle()
            val downloads by model.fileDownloads.state.collectAsStateWithLifecycle()
            val environments by model.environments.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            val working by model.working.collectAsStateWithLifecycle()
            val sshHostKey by model.sshHostKey.collectAsStateWithLifecycle()
            val sshLogin by model.sshLogin.collectAsStateWithLifecycle()
            val connectionAttempt by model.connectionAttempt.collectAsStateWithLifecycle()
            val machineProbes by model.machineProbes.collectAsStateWithLifecycle()
            val usage by model.usage.collectAsStateWithLifecycle()
            val keepConnected by model.keepConnected.collectAsStateWithLifecycle()
            TrefoilApp(
                usageOpenRequest = usageRequest,
                pairLinkRequest = pairLinkRequest,
                usage = usage,
                onOpenUsage = model.usageController::open,
                onUsageRange = model.usageController::selectRange,
                onRefreshUsage = model.usageController::refresh,
                onRetryUsage = model.usageController::retry,
                state = state.copy(error = notice ?: state.error, busy = working || state.busy),
                savedEnvironments = environments,
                onPair = model::pair,
                onConnect = model::connect,
                onSelectThread = model::selectThread,
                onSend = model::send,
                onInterrupt = model::interrupt,
                onStopSubagent = model::stopSubagent,
                onThreadAction = model::threadAction,
                onUndoSettlement = model::undoSettlement,
                onLoadActivity = model::loadActivity,
                screenViewer = screenViewer,
                onRefreshScreens = model.screenSessions::refresh,
                onOpenScreen = model.screenSessions::open,
                onCloseScreen = model.screenSessions::close,
                isScreenAccessCurrent = model.screenSessions::isCurrent,
                fileDownloadUi = FileDownloadUi(state.environment?.id, state.selectedThreadId,
                    state.connection == com.tajchert.trefoil.core.model.ConnectionStatus.Connected && (state.canRead || state.canWrite), downloads,
                    { environmentId, threadId, reference -> model.fileDownloads.download(reference, environmentId, threadId) },
                    { environmentId, threadId, reference -> model.fileDownloads.cancel(reference, environmentId, threadId) },
                    { openDownloaded(it) }, { openDownloaded(it, share = true) }, { openDownloaded(it, install = true) },
                    imageLoader = model.fileDownloads.images, htmlLoader = model.fileDownloads.htmlRenders),
                onRespond = model::respond,
                draft = draft,
                sendFailed = model.sendFailed,
                onStartDraft = model::startDraft,
                onCancelDraft = model::cancelDraft,
                onSetRuntimeMode = model::setRuntimeMode,
                onSetModel = model::setModel,
                onSetModelOption = model::setModelOption,
                onReconnect = model::reconnect,
                onDisconnect = model::disconnect,
                onForget = model::forget,
                onSshConnect = model::connectSsh,
                sshHostKey = sshHostKey,
                onTrustSsh = model::trustSsh,
                onCancelSsh = model::cancelSsh,
                sshLogin = sshLogin,
                connectionAttempt = connectionAttempt,
                onSshKeyPickerActive = { model.sshFilePickerActive = it },
                machineProbes = machineProbes,
                onAllowLocalNetworkHttp = model::allowLocalNetworkHttp,
                keepConnected = keepConnected,
                onKeepConnectedChange = ::onKeepConnectedChange,
            )
        }
    }
}

private const val MAX_PAIR_LINK_LENGTH = 4096

/**
 * The `t3code://pair…` link of a browser/app VIEW intent, or null. Untrusted: only the data string is read, and it is
 * returned unparsed so the dialog applies the same validation as a pasted link. A link re-delivered from recents is ignored.
 */
internal fun parsePairLinkIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val data = intent.data ?: return null
    if (!data.scheme.equals("t3code", ignoreCase = true) || data.host != "pair") return null
    return data.toString().takeIf { it.length <= MAX_PAIR_LINK_LENGTH }
}

internal fun parseUsageWidgetIntent(intent: Intent?): UsageWidgetTab? {
    if (intent?.action != ACTION_OPEN_USAGE) return null
    return UsageWidgetTab.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_OPEN_USAGE_TAB) } ?: UsageWidgetTab.Limits
}
