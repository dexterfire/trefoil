package com.tajchert.trefoil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.EnvironmentProbe
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.core.session.ThreadDraftController
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshTunnel
import com.tajchert.trefoil.core.ssh.SshTunnelConnector
import com.tajchert.trefoil.security.EnvironmentVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

internal const val BACKGROUND_CONNECTION_GRACE_MS = 60_000L
internal const val RETAINED_CONNECTION_FRESHNESS_MS = 20_000L
/** Waits before each automatic reconnect attempt after an unexpected drop; one episode per drop. */
internal val AUTO_RECONNECT_BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L)

class TrefoilViewModel(application: Application) : AndroidViewModel(application) {
    private val client = T3Client(viewModelScope)
    val usageController = UsageController(viewModelScope, { client }, onReport = {
        try { widgetSync.report(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Widget storage must not fail app reads. */ }
    })
    val usage = usageController.state
    init {
        viewModelScope.launch {
            client.state.map { it.environment?.id }.distinctUntilChanged().collect { usageController.reset() }
        }
    }
    val screenSessions = com.tajchert.trefoil.ui.screens.ScreenSessionCoordinator(viewModelScope, client)
    val fileDownloads = com.tajchert.trefoil.downloads.FileDownloadCoordinator(viewModelScope, client,
        com.tajchert.trefoil.downloads.MediaStoreDownloadStore(application))
    private val connector = SshTunnelConnector()
    private val vault = EnvironmentVault(application)
    private val vaultMutex = Mutex()
    private val connectionMutex = Mutex()
    val state = client.state
    private val _environments = MutableStateFlow<List<SavedEnvironment>>(emptyList())
    val environments = _environments.asStateFlow()
    private val widgetSync = com.tajchert.trefoil.widget.UsageWidgetSync(application, viewModelScope, client, environments)
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _connectionAttempt = MutableStateFlow(ConnectionAttempt())
    val connectionAttempt = _connectionAttempt.asStateFlow()
    /** Mutations started here or by [drafts] that have not settled; a route switch waits for zero. */
    private val mutationsInFlight = AtomicInteger()
    private val drafts = ThreadDraftController(viewModelScope, MutationTrackingClient(client, mutationsInFlight)) { _notice.value = it }
    /** Unsent new conversation; while non-null the UI shows the draft instead of the selected thread. */
    val draft: StateFlow<ThreadDraft?> = drafts.draft
    /** Emits once for every send (draft or thread) that did not deliver; the composer must leave its pending state. */
    val sendFailed: SharedFlow<Unit> = drafts.sendFailed
    private val _sshHostKey = MutableStateFlow<SshHostKey?>(null)
    val sshHostKey = _sshHostKey.asStateFlow()
    private val _sshLogin = MutableStateFlow<SshConnectionInput?>(null)
    val sshLogin = _sshLogin.asStateFlow()
    private var hostKeys = emptyList<SshHostKey>()
    private var pendingInput: SshConnectionInput? = null
    private var tunnel: SshTunnel? = null
    private var tunnelMonitor: Job? = null
    private var connectionJob: Job? = null
    private var backgroundDisconnectJob: Job? = null
    private var selectedEnvironment: SavedEnvironment? = null
    private var resumeEnvironment: SavedEnvironment? = null
    private var foreground = true
    private var connectionEpoch = 0L
    var sshFilePickerActive = false
    private val prefs = application.getSharedPreferences("trefoil.ui", android.content.Context.MODE_PRIVATE)
    private val environmentProbe = EnvironmentProbe()
    private val prober = MachineProber(
        // Adapter over the single-route probe; EnvironmentProbe's multi-route probe has the same contract.
        direct = { environment, routes -> probeRoutes(routes) { environmentProbe.probe(environment, it.baseUrl) } },
        openSsh = { input, pinned -> connector.open(input, pinned).let { opened -> object : ProbeTunnel {
            override val baseUrl = opened.baseUrl
            override fun close() = opened.close()
        } } },
    )
    private val _machineProbes = MutableStateFlow<Map<String, MachineProbe>>(emptyMap())
    /** Reachability of saved machines other than the active connection; never mutates a server. */
    val machineProbes = _machineProbes.asStateFlow()
    private var probeJob: Job? = null
    /** Auto-connect is considered once per app launch, until a decision is made or the user acts. */
    private var autoConnectPending = true
    /**
     * The machine of the last successful connection attempt. Armed only by a successful connection and disarmed by any
     * disconnect this view model makes, so a later drop to Error/Disconnected is unexpected and reconnects once.
     */
    private var liveEnvironment: SavedEnvironment? = null
    private var autoReconnectJob: Job? = null
    /** A drop while offline reconnects when a default network appears. */
    private var reconnectWhenOnline: SavedEnvironment? = null
    private var defaultNetwork: Long? = null
    private var networkKnown = false
    private var networkAvailable = true
    /** Route hints for a machine that is not saved yet (SSH pairing saves after connecting); applied when it is saved. */
    private val unsavedHints = mutableMapOf<String, List<RouteHints>>()
    private val routeMonitor = RouteMonitor(viewModelScope,
        preflight = { id, route, insecure, timeout -> client.preflight(id, route, insecure, timeout) },
        canSwitch = ::idleForRouteSwitch,
        onBetterRoute = ::switchRoute,
    )

    private val initialized = viewModelScope.async {
        try {
            vaultMutex.withLock {
                _environments.value = withContext(Dispatchers.IO) { vault.read() }
                hostKeys = withContext(Dispatchers.IO) { vault.readHostKeys() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _notice.value = "Saved credentials could not be unlocked. Pair this device again." }
    }

    init {
        viewModelScope.launch { client.routeHints.collect { hints -> learnRoutes(hints) } }
        viewModelScope.launch {
            combine(client.state.map { Triple(it.connection, it.environment?.id, it.activeRoute) }.distinctUntilChanged(), _environments) { _, _ -> }
                .collect { refreshRouteMonitor() }
        }
        viewModelScope.launch {
            client.state.map { it.connection to it.environment?.id }.distinctUntilChanged().collect { (connection, id) ->
                if (connection == ConnectionStatus.Error || connection == ConnectionStatus.Disconnected) connectionDropped(id)
            }
        }
    }

    private fun action(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _notice.value = null
            try { block() }
            catch (e: CancellationException) {
                // A stray cancellation (e.g. an inner timeout) while this job is alive is a failure, never silence.
                coroutineContext.ensureActive(); _notice.value = failure
            }
            catch (_: Exception) { _notice.value = failure }
        }
    }

    /** A mutation the user started; counted so a route switch never abandons it. */
    private fun mutation(failure: String, block: suspend () -> Unit) = action(failure) { mutationsInFlight.tracked { block() } }

    private fun connectionAction(failure: String, kind: ConnectionKind = ConnectionKind.Saved, block: suspend () -> Unit): Job {
        backgroundDisconnectJob?.cancel(); backgroundDisconnectJob = null
        reconnectWhenOnline = null
        val epoch = ++connectionEpoch
        autoConnectPending = false
        connectionJob?.cancel()
        _sshHostKey.value = null
        if (kind != ConnectionKind.Ssh) _sshLogin.value = null
        pendingInput = null
        _notice.value = null; _working.value = true
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Testing, "Testing connection…")
        connectionJob = viewModelScope.launch {
            try {
                initialized.await()
                connectionMutex.withLock {
                    if (!foreground || epoch != connectionEpoch) return@withLock
                    try {
                        block()
                        if (epoch == connectionEpoch) {
                            _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Succeeded, "Connected")
                            _sshLogin.value = null
                            if (state.value.connection == ConnectionStatus.Connected) liveEnvironment = selectedEnvironment ?: state.value.environment?.forStorage()
                            (selectedEnvironment ?: state.value.environment)?.id?.let { id ->
                                prefs.edit().putString(LAST_ENVIRONMENT, id).apply()
                                _machineProbes.update { it + (id to MachineProbe.Online) }
                            }
                        }
                    } catch (e: CancellationException) {
                        coroutineContext.ensureActive()
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, failure) }
                    } catch (e: SshHostKeyException) {
                        if (epoch == connectionEpoch) {
                            if (e.changed) {
                                pendingInput = null
                                connectionFailed(epoch, kind, "SSH host key changed for ${e.presented.host}:${e.presented.port}. Connection blocked. Verify the server key independently before changing your saved trust.")
                            } else {
                                _sshHostKey.value = e.presented
                                _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.AwaitingTrust, "Confirm the SSH host fingerprint to continue.")
                            }
                        }
                    } catch (e: SshLoginRequired) {
                        // Every route before it failed and SSH is the last option: ask for the login, like a plain SSH connect.
                        if (epoch == connectionEpoch) { pendingInput = null; _connectionAttempt.value = ConnectionAttempt(); _sshLogin.value = e.input }
                    } catch (e: SshConnectionException) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, e.message ?: failure) }
                    } catch (e: PairingException) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, e.message ?: failure) }
                    } catch (_: Exception) {
                        if (epoch == connectionEpoch) { pendingInput = null; connectionFailed(epoch, kind, failure) }
                    }
                }
            } finally { if (epoch == connectionEpoch) _working.value = false }
        }
        return connectionJob!!
    }

    private fun connectionFailed(epoch: Long, kind: ConnectionKind, message: String) {
        _notice.value = message
        _connectionAttempt.value = ConnectionAttempt(epoch, kind, ConnectionPhase.Failed, message)
    }

    private fun connectionStage(message: String) {
        _connectionAttempt.update { it.copy(phase = ConnectionPhase.Testing, message = message) }
    }

    private suspend fun save(environments: List<SavedEnvironment>) {
        val durable = environments.map { environment -> unsavedHints.remove(environment.id)?.fold(environment) { e, h -> e.withRouteHints(h) } ?: environment }.map { it.forStorage() }
        withContext(Dispatchers.IO) { vault.write(durable) }
        _environments.value = durable
    }

    private suspend fun disconnectCurrent() {
        liveEnvironment = null
        tunnelMonitor?.cancel(); tunnelMonitor = null
        client.disconnect()
        val closing = tunnel; tunnel = null
        withContext(NonCancellable + Dispatchers.IO) { closing?.close() }
    }

    fun pair(url: String, name: String, allowInsecureHttp: Boolean) { connectionAction("Pairing failed. Check the link, server address and certificate, then try again.", ConnectionKind.Pairing) {
        clearDraft()
        disconnectCurrent()
        connectionStage("Testing pairing link and exchanging credentials…")
        val paired = client.pair(url, name, allowInsecureHttp)
        // Re-pairing keeps the learned routes; the hints of this connection then refresh them.
        val environment = vaultMutex.withLock {
            save(_environments.value.filterNot { it.id == paired.id } + paired.withLearnedRoutesOf(_environments.value))
            _environments.value.first { it.id == paired.id }
        }
        selectedEnvironment = environment
        connectionStage("Opening T3 session…")
        client.connect(environment)
    } }

    fun connect(environment: SavedEnvironment) {
        if (draft.value != null && environment.id != drafts.environmentId) clearDraft()
        val input = environment.ssh?.let { environment.sshInput() }
        // Only SSH is left and its login is not saved: ask right away, without a connection attempt.
        if (input != null && !input.hasSavedLogin() && environment.candidateRoutes().all { it.kind == RouteKind.Ssh }) {
            cancelSsh(); _sshLogin.value = input; return
        }
        if (input != null) connectionAction(SSH_FAILURE, ConnectionKind.Ssh) { connectThroughPlan(environment) }
        else connectionAction("Could not connect. Check the server or pair again if access has expired.") { connectThroughPlan(environment) }
    }

    fun connectSsh(input: SshConnectionInput) { connectionAction(SSH_FAILURE, ConnectionKind.Ssh) { openSsh(input) } }

    /**
     * Walks [requested]'s connection plan (its latest saved copy), from [startAt] when given: direct routes in one
     * [TrefoilClient.connect] call per run, SSH in its slot when its login is saved. Without a saved login SSH is skipped
     * when a later route (the tunnel) exists; as the last option it asks for the login when [interactive], else fails.
     */
    private suspend fun connectThroughPlan(requested: SavedEnvironment, startAt: EnvironmentRoute? = null, interactive: Boolean = true) {
        disconnectCurrent()
        val environment = _environments.value.firstOrNull { it.id == requested.id } ?: requested
        selectedEnvironment = environment.forStorage()
        val plan = environment.candidateRoutes().let { plan -> startAt?.let { start -> plan.dropWhile { !sameRoute(it, start) } }?.ifEmpty { null } ?: plan }
        val input = environment.ssh?.let { environment.sshInput() }
        val outcome = walkConnectionPlan(plan, input?.hasSavedLogin() == true, ::connectionStage,
            direct = { routes -> client.connect(environment, routes) },
            ssh = { openSsh(checkNotNull(input)) },
        )
        if (outcome == PlanOutcome.NeedsSshLogin) {
            if (interactive && input != null) throw SshLoginRequired(input)
            throw PairingException("Could not reach this machine without your SSH login. Connect to sign in again.")
        }
    }

    private suspend fun openSsh(input: SshConnectionInput) {
        sshInputValidationError(input)?.let { throw SshConnectionException(it) }
        disconnectCurrent()
        val normalized = input.copy(host = input.host.trim(), username = input.username.trim())
        val saved = normalized.savedEnvironmentId?.let { id -> _environments.value.firstOrNull { it.id == id && it.ssh != null } }
        val expected = hostKeys.firstOrNull { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
            ?: saved?.ssh?.takeIf { it.host.equals(normalized.host, ignoreCase = true) && it.port == normalized.port }?.fingerprint
        pendingInput = normalized
        connectionStage("Testing SSH login and locating the T3 server…")
        val opened = connector.open(normalized, expected)
        pendingInput = null
        var adopted = false
        try {
            coroutineContext.ensureActive()
            // A saved identity is verified by T3Client before its bearer token is sent.
            val environment = if (saved == null) {
                connectionStage("Obtaining a T3 pairing credential…")
                val credential = opened.issuePairingCredential()
                val link = opened.baseUrl.trimEnd('/') + "/pair#token=" + java.net.URLEncoder.encode(credential, "UTF-8")
                client.pair(link, normalized.name)
            } else saved.copy(baseUrl = opened.baseUrl)
            val configured = environment.copy(name = normalized.name, ssh = normalized.savedProfile(opened.hostKey))
            connectionStage("Testing T3 session through the SSH tunnel…")
            client.connect(configured)
            coroutineContext.ensureActive()
            check(opened.active.value) { "SSH tunnel closed" }
            // The vault's learned routes are newer than the copy this attempt started from (and survive re-pairing).
            vaultMutex.withLock { save(_environments.value.filterNot { it.id == configured.id } + configured.withLearnedRoutesOf(_environments.value)) }
            selectedEnvironment = _environments.value.firstOrNull { it.id == configured.id } ?: configured.forStorage()
            tunnel = opened; adopted = true
            tunnelMonitor = viewModelScope.launch {
                opened.active.filter { !it }.first()
                connectionMutex.withLock {
                    if (tunnel === opened) {
                        client.disconnect(); tunnel = null
                        withContext(NonCancellable + Dispatchers.IO) { opened.close() }
                        _notice.value = "SSH tunnel closed. Reconnect to refresh; pending command outcomes may be uncertain."
                    }
                }
            }
        } finally {
            if (!adopted) { client.disconnect(); withContext(NonCancellable + Dispatchers.IO) { opened.close() } }
        }
    }

    fun trustSsh() {
        val input = pendingInput ?: return
        val key = _sshHostKey.value ?: return
        if (!input.host.equals(key.host, ignoreCase = true) || input.port != key.port) return
        _sshHostKey.value = null; pendingInput = null
        val trustEpoch = connectionEpoch
        action("Could not save SSH host trust.") {
            vaultMutex.withLock {
                val keys = hostKeys.filterNot { it.host.equals(key.host, ignoreCase = true) && it.port == key.port } + key
                withContext(Dispatchers.IO) { vault.writeHostKeys(keys) }
                hostKeys = keys
            }
            if (foreground && connectionEpoch == trustEpoch) connectSsh(input)
        }
    }

    fun cancelSsh() {
        connectionEpoch++
        connectionJob?.cancel()
        pendingInput = null; _sshHostKey.value = null; _sshLogin.value = null
        _working.value = false
        _connectionAttempt.value = ConnectionAttempt()
    }

    fun selectThread(id: String) {
        clearDraft()
        action("Could not open the thread. Reconnect and try again.") { client.selectThread(id) }
    }
    /**
     * With a draft, the first message creates the thread (`launchThread`); otherwise it goes to the
     * selected thread. Every failed or uncertain send emits [sendFailed]. An uncertain launch keeps
     * the draft and its ids so it can be reconciled or explicitly resent without a duplicate.
     */
    fun send(text: String) = drafts.send(text)
    fun loadActivity(id: String) = action("Could not load activity output. Reconnect and try again.") { client.loadActivity(id) }
    fun interrupt() = mutation("Interrupt outcome is uncertain. Check the run status.") { client.interruptRun() }
    fun stopSubagent(id: String) = mutation("Agent stop failed or its outcome is uncertain. Check the agent before trying again.") { client.stopSubagent(id) }
    fun threadAction(target: ThreadTarget, operation: ThreadAction, value: String?) = mutation("Could not update thread. Check its status before trying again.") { client.threadAction(target, operation, value) }
    fun undoSettlement(undo: SettlementUndo) = mutation("Could not undo settlement. Check the thread status.") { client.undoSettlement(undo) }
    fun respond(id: String, decision: String, answers: Map<String,List<String>>) = mutation("Response outcome is uncertain. Check the request status before responding again.") { client.respond(id, decision, answers) }
    /** Opens a local draft; no server mutation until the first send. */
    fun startDraft(draft: ThreadDraft) = drafts.start(draft)
    fun cancelDraft() = clearDraft()
    private fun clearDraft() = drafts.clear()
    /** Draft: updates locally. Selected thread: dispatches `thread.runtime-mode.set`. */
    fun setRuntimeMode(mode: RuntimeMode) = drafts.setRuntimeMode(mode)
    /** Draft: updates locally. Selected thread: dispatches `thread.model-selection.set`. */
    fun setModel(model: ModelOption) = drafts.setModel(model)

    /** Keeps the draft: reconnecting is the same environment, and an uncertain launch is reconciled after it. */
    fun reconnect() {
        val environment = selectedEnvironment ?: state.value.environment?.forStorage() ?: return
        if (environment.ssh != null) connect(environment)
        else connectionAction("Could not reconnect. Check the server or pair again.") { connectThroughPlan(environment) }
    }

    fun disconnect() {
        liveEnvironment = null; reconnectWhenOnline = null
        autoReconnectJob?.cancel(); autoReconnectJob = null
        clearDraft()
        screenSessions.close()
        resumeEnvironment = null; cancelSsh()
        action("Could not disconnect.") { connectionMutex.withLock { disconnectCurrent() } }
    }
    /** Saves per-machine consent for plain HTTP to literal private LAN addresses; applies from the next connect. */
    fun allowLocalNetworkHttp(environmentId: String, allowed: Boolean) = action("Could not save the local network setting.") {
        initialized.await()
        vaultMutex.withLock {
            val current = _environments.value.firstOrNull { it.id == environmentId } ?: return@withLock
            if (current.allowInsecureHttp == allowed) return@withLock
            save(_environments.value.map { if (it.id == environmentId) it.copy(allowInsecureHttp = allowed) else it })
            selectedEnvironment?.takeIf { it.id == environmentId }?.let { selectedEnvironment = it.copy(allowInsecureHttp = allowed) }
        }
    }
    fun forget(environment: SavedEnvironment) = action("Could not remove saved credentials.") {
        if (drafts.environmentId == environment.id || state.value.environment?.id == environment.id) clearDraft()
        if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) cancelSsh()
        if (resumeEnvironment?.id == environment.id) resumeEnvironment = null
        _machineProbes.update { it - environment.id }
        if (prefs.getString(LAST_ENVIRONMENT, null) == environment.id) prefs.edit().remove(LAST_ENVIRONMENT).apply()
        connectionMutex.withLock {
            if (selectedEnvironment?.id == environment.id || state.value.environment?.id == environment.id) {
                disconnectCurrent(); selectedEnvironment = null
            }
            client.forgetEnvironment(environment.id)
        }
        initialized.await()
        vaultMutex.withLock { save(_environments.value.filterNot { it.id == environment.id }) }
        widgetSync.forget(environment.id)
    }
    fun pause() {
        if (!foreground) return
        screenSessions.pause()
        foreground = false
        // Lifecycle code owns reconnecting after a pause; route switches wait for the foreground.
        autoReconnectJob?.cancel(); autoReconnectJob = null; reconnectWhenOnline = null
        refreshRouteMonitor()
        probeJob?.cancel(); probeJob = null
        _machineProbes.update { probes -> probes.mapValues { (_, probe) -> if (probe == MachineProbe.Checking) MachineProbe.Unknown else probe } }
        resumeEnvironment = if (state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting))
            selectedEnvironment ?: state.value.environment?.forStorage() else null
        // Cancel pending handshakes/trust, but give an established socket and SSH tunnel time to stay live.
        if (sshFilePickerActive) { connectionEpoch++; connectionJob?.cancel(); _working.value = false } else cancelSsh()
        val epoch = connectionEpoch
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = viewModelScope.launch {
            delay(BACKGROUND_CONNECTION_GRACE_MS)
            connectionMutex.withLock {
                // A resume, explicit disconnect or newer connection attempt supersedes this timeout.
                if (!foreground && epoch == connectionEpoch) disconnectCurrent()
            }
        }
    }
    fun resume() {
        foreground = true
        backgroundDisconnectJob?.cancel(); backgroundDisconnectJob = null
        screenSessions.resume()
        refreshRouteMonitor()
        val environment = resumeEnvironment
        resumeEnvironment = null
        if (environment != null && _sshLogin.value == null &&
            (state.value.connection != ConnectionStatus.Connected || state.value.environment?.id != environment.id ||
                (client.millisSinceLastPong()?.let { it > RETAINED_CONNECTION_FRESHNESS_MS } != false))) connect(environment)
        probeMachines(skipId = environment?.id)
    }

    /**
     * Probes every saved machine except the active or resuming one, in parallel. On the first
     * launch it may then connect the last-used machine, or the only reachable one, through the
     * normal [connect] path, unless the user started a connection in the meantime.
     */
    private fun probeMachines(skipId: String?) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            initialized.await()
            if (!foreground) return@launch
            val epoch = connectionEpoch
            val keys = hostKeys
            val live = state.value.environment?.id?.takeIf {
                state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)
            }
            val targets = _environments.value.filter { it.id != skipId && it.id != live }
            _machineProbes.update { probes ->
                probes.filterKeys { id -> _environments.value.any { it.id == id } } + targets.associate { it.id to MachineProbe.Checking }
            }
            fun considerAutoConnect() {
                if (!autoConnectPending) return
                if (epoch != connectionEpoch || !foreground || _sshLogin.value != null ||
                    state.value.connection in setOf(ConnectionStatus.Connected, ConnectionStatus.Connecting, ConnectionStatus.Reconnecting)) {
                    autoConnectPending = false; return
                }
                when (val decision = autoConnectDecision(prefs.getString(LAST_ENVIRONMENT, null), _environments.value, _machineProbes.value)) {
                    AutoConnectDecision.Wait -> {}
                    AutoConnectDecision.None -> autoConnectPending = false
                    is AutoConnectDecision.Connect -> {
                        autoConnectPending = false
                        _environments.value.firstOrNull { it.id == decision.environmentId }?.let(::connect)
                    }
                }
            }
            supervisorScope {
                targets.forEach { environment ->
                    launch {
                        val result = prober.probe(environment, keys)
                        _machineProbes.update { probes -> if (environment.id in probes) probes + (environment.id to result) else probes }
                        considerAutoConnect()
                    }
                }
            }
            considerAutoConnect()
        }
    }
    private suspend fun learnRoutes(hints: RouteHints) {
        try {
            initialized.await()
            vaultMutex.withLock {
                val id = hints.environmentId
                val updated = applyRouteHints(_environments.value, hints)
                if (updated == null) {
                    // SSH pairing saves the machine after its connect: keep the hints until then.
                    if (_environments.value.none { it.id == id }) {
                        if (id !in unsavedHints && unsavedHints.size >= 4) unsavedHints.clear()
                        unsavedHints[id] = (unsavedHints[id].orEmpty() + hints).takeLast(4)
                    }
                    return@withLock
                }
                save(updated)
                val routes = _environments.value.first { it.id == id }.routes
                selectedEnvironment?.takeIf { it.id == id }?.let { selectedEnvironment = it.copy(routes = routes) }
                resumeEnvironment?.takeIf { it.id == id }?.let { resumeEnvironment = it.copy(routes = routes) }
                liveEnvironment?.takeIf { it.id == id }?.let { liveEnvironment = it.copy(routes = routes) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Learning is best effort; the plan keeps the routes it knows. */ }
    }

    /** Watches for a better route while the foreground connection is on a route its plan ranks lower. */
    private fun refreshRouteMonitor() {
        val current = state.value
        val active = current.activeRoute
        val saved = current.environment?.id?.let { id -> _environments.value.firstOrNull { it.id == id } }
        // Leaving an SSH session whose login was typed, not saved, could not fall back to it if the switch failed.
        val fallbackPossible = active?.kind != RouteKind.Ssh || saved?.ssh?.let { saved.sshInput().hasSavedLogin() } == true
        routeMonitor.watch(if (foreground && current.connection == ConnectionStatus.Connected && active != null && saved != null && fallbackPossible)
            RouteWatch(saved.id, active, saved.candidateRoutes(), saved.allowInsecureHttp) else null)
    }

    /** A switch is a disconnect plus connect: only when nothing in flight could be abandoned or cut off. */
    private fun idleForRouteSwitch(): Boolean = foreground && !_working.value && _sshLogin.value == null && _sshHostKey.value == null &&
        mutationsInFlight.get() == 0 && client.isIdleForRouteSwitch() &&
        fileDownloads.state.value.values.none { it.status == com.tajchert.trefoil.downloads.DownloadStatus.Downloading } &&
        screenSessions.state.value.target == null

    /** Reconnects through the plan from [route]; a route that does not end up active goes on cooldown. */
    private fun switchRoute(watch: RouteWatch, route: EnvironmentRoute) {
        val current = state.value
        val saved = _environments.value.firstOrNull { it.id == watch.environmentId } ?: return
        if (current.connection != ConnectionStatus.Connected || current.environment?.id != saved.id || !idleForRouteSwitch()) return
        val attempt = connectionAction("Could not switch to a faster connection. Reconnect to refresh.") {
            connectThroughPlan(saved, startAt = route, interactive = false)
        }
        val epoch = connectionEpoch
        viewModelScope.launch {
            attempt.join()
            if (epoch != connectionEpoch) return@launch
            val active = state.value.activeRoute
            if (state.value.connection != ConnectionStatus.Connected || active == null || !sameRoute(active, route)) routeMonitor.switchFailed(saved.id, route)
        }
    }

    /** An established connection ended without this view model asking for it. */
    private fun connectionDropped(environmentId: String?) {
        val live = liveEnvironment ?: return
        liveEnvironment = null
        if (live.id != environmentId || !foreground || _working.value || _sshLogin.value != null) return
        scheduleAutoReconnect(live)
    }

    /**
     * One reconnect episode through the plan, attempts after [AUTO_RECONNECT_BACKOFF_MS]. It only re-reads (connect never
     * replays a mutation) and stops at success, a user action or pause (epoch change), a forgotten machine, or offline.
     */
    private fun scheduleAutoReconnect(environment: SavedEnvironment) {
        autoReconnectJob?.cancel(); autoReconnectJob = null
        if (!networkAvailable) { reconnectWhenOnline = environment; return }
        reconnectWhenOnline = null
        var epoch = connectionEpoch
        autoReconnectJob = viewModelScope.launch {
            for (wait in AUTO_RECONNECT_BACKOFF_MS) {
                delay(wait)
                if (!foreground || epoch != connectionEpoch || _sshLogin.value != null || _environments.value.none { it.id == environment.id }) return@launch
                if (!networkAvailable) { reconnectWhenOnline = environment; return@launch }
                val attempt = connectionAction("Connection lost. Check the network and server, then reconnect.") {
                    connectThroughPlan(environment, interactive = false)
                }
                epoch = connectionEpoch
                attempt.join()
                if (epoch != connectionEpoch) return@launch
                if (state.value.connection == ConnectionStatus.Connected && state.value.environment?.id == environment.id) return@launch
            }
        }
    }

    /** The default network is [handle] (`Network.getNetworkHandle`). A change checks for a better route; regaining one resumes a pending reconnect. */
    fun onDefaultNetwork(handle: Long) {
        val changed = networkKnown && defaultNetwork != handle
        networkKnown = true; defaultNetwork = handle; networkAvailable = true
        if (!foreground) return
        reconnectWhenOnline?.let { scheduleAutoReconnect(it); return }
        if (changed && state.value.connection == ConnectionStatus.Connected) routeMonitor.requestCheck(networkChanged = true)
    }

    /** Network [handle] stopped being the default; only the current default going away means offline. */
    fun onDefaultNetworkLost(handle: Long) {
        if (defaultNetwork == handle) { defaultNetwork = null; networkAvailable = false }
    }

    override fun onCleared() {
        widgetSync.close()
        probeJob?.cancel()
        screenSessions.close()
        tunnelMonitor?.cancel()
        val closing = tunnel; tunnel = null
        if (closing != null) Thread({ closing.close() }, "trefoil-ssh-cleanup").apply { isDaemon = true; start() }
        client.close()
    }

    private companion object {
        const val LAST_ENVIRONMENT = "lastEnvironmentId"
        const val SSH_FAILURE = "SSH connection failed. Check the address, login and running T3 server."
    }
}

internal enum class PlanOutcome { Connected, NeedsSshLogin }

/** Every route before SSH failed, SSH is the last option and its login is not saved. */
internal class SshLoginRequired(val input: SshConnectionInput) : Exception("SSH login required")

internal fun SshConnectionInput.hasSavedLogin(): Boolean = when (authMethod) {
    SshAuthMethod.Password -> password.isNotEmpty()
    SshAuthMethod.PrivateKey -> privateKey.isNotEmpty()
}

private fun routeLabel(kind: RouteKind) = when (kind) {
    RouteKind.Lan -> "local network"
    RouteKind.Tailnet -> "Tailscale"
    RouteKind.Public -> "HTTPS address"
    RouteKind.Ssh -> "SSH"
    RouteKind.Tunnel -> "T3 Connect tunnel"
}

/** "Trying local network…", or "Trying local network, then T3 Connect tunnel…" for a run of several kinds. */
internal fun routeStage(routes: List<EnvironmentRoute>): String =
    "Trying " + routes.map { routeLabel(it.kind) }.distinct().joinToString(", then ") + "…"

/**
 * Walks [plan] in order: each run of direct routes goes to [direct] in one call; an Ssh step runs [ssh] when
 * [sshLoginSaved]. Without a saved login SSH is skipped when a later route exists, and as the last option the walk
 * returns [PlanOutcome.NeedsSshLogin]. A failed step continues with the next; SSH host key problems stop the walk (they
 * are the user's decision). When every step fails, the last failure is thrown.
 */
internal suspend fun walkConnectionPlan(
    plan: List<EnvironmentRoute>,
    sshLoginSaved: Boolean,
    stage: (String) -> Unit,
    direct: suspend (List<EnvironmentRoute>) -> Unit,
    ssh: suspend () -> Unit,
): PlanOutcome {
    if (plan.isEmpty()) throw PairingException("This machine has no saved address. Pair it again.")
    var failure: Exception? = null
    var index = 0
    while (index < plan.size) {
        currentCoroutineContext().ensureActive()
        if (plan[index].kind == RouteKind.Ssh) {
            index++
            if (!sshLoginSaved) { if (index < plan.size) continue else return PlanOutcome.NeedsSshLogin }
            try { ssh(); return PlanOutcome.Connected }
            catch (e: CancellationException) { throw e }
            catch (e: SshHostKeyException) { throw e }
            catch (e: Exception) { failure = e }
        } else {
            val run = plan.drop(index).takeWhile { it.kind != RouteKind.Ssh }
            index += run.size
            stage(routeStage(run))
            try { direct(run); return PlanOutcome.Connected }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
    }
    throw failure ?: PairingException("Could not connect")
}

/** Hints applied to the matching saved machine, or null when they change nothing (and nothing needs writing). */
internal fun applyRouteHints(environments: List<SavedEnvironment>, hints: RouteHints): List<SavedEnvironment>? {
    val current = environments.firstOrNull { it.id == hints.environmentId } ?: return null
    val updated = current.withRouteHints(hints)
    return if (updated == current) null else environments.map { if (it.id == current.id) updated else it }
}

/** The vault's learned routes for the same machine win over this copy's (re-pairing, or a copy older than the vault). */
internal fun SavedEnvironment.withLearnedRoutesOf(environments: List<SavedEnvironment>): SavedEnvironment =
    environments.firstOrNull { it.id == id }?.let { copy(routes = it.routes) } ?: this

/** Counts the mutations [ThreadDraftController] sends, so a route switch can wait for them to settle. */
internal class MutationTrackingClient(private val inner: TrefoilClient, private val inFlight: AtomicInteger) : TrefoilClient by inner {
    override suspend fun sendMessage(text: String) = inFlight.tracked { inner.sendMessage(text) }
    override suspend fun launchThread(draft: ThreadDraft, firstMessage: String) = inFlight.tracked { inner.launchThread(draft, firstMessage) }
    override suspend fun setRuntimeMode(mode: RuntimeMode) = inFlight.tracked { inner.setRuntimeMode(mode) }
    override suspend fun setModel(model: ModelOption) = inFlight.tracked { inner.setModel(model) }
}

internal inline fun <T> AtomicInteger.tracked(block: () -> T): T {
    incrementAndGet()
    try { return block() } finally { decrementAndGet() }
}
