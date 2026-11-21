/* SSH authentication/crypto patterns adapted from Flocket (Apache-2.0). */
package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultConfig
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.net.*
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class SshHostKeyException(val presented: SshHostKey, val changed: Boolean) : Exception(if (changed) "SSH host key changed. Verify it independently before reconnecting." else "Confirm the SSH host key before connecting.")
class SshConnectionException(message: String) : Exception(message)
internal fun remoteQuote(value: String): String { require('\u0000' !in value); return "'" + value.replace("'", "'\\''") + "'" }
internal fun remotePath(value: String): String = if (value.startsWith("~/")) "\"\$HOME\"/" + remoteQuote(value.drop(2)) else remoteQuote(value)
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
private fun validVersion(value: String) = value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))
private fun fingerprint(key: PublicKey) = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Buffer.PlainBuffer().putPublicKey(key).compactData))

/**
 * Process-lifetime, in-memory remote server ports by machine id. Never persisted: the port is ephemeral and only a
 * hint to skip the runtime-file read; every use is re-validated by the descriptor check. An entry is bound to the SSH host
 * key it was learned under and is dropped when a different key is presented.
 */
internal object RemotePortCache {
    private class Entry(val fingerprint: String, val port: Int)
    private const val MAX_ENTRIES = 32
    private val entries = ConcurrentHashMap<String, Entry>()
    fun get(environmentId: String, fingerprint: String): Int? {
        val entry = entries[environmentId] ?: return null
        if (entry.fingerprint != fingerprint) { entries.remove(environmentId, entry); return null }
        return entry.port
    }
    fun put(environmentId: String, fingerprint: String, port: Int) {
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(environmentId)) entries.clear()
        entries[environmentId] = Entry(fingerprint, port)
    }
    fun forget(environmentId: String) { entries.remove(environmentId) }
    fun clear() = entries.clear()
}

class SshTunnelConnector {
    companion object {
        /** Drops the remembered remote port of a machine; call when it is forgotten or its SSH host key is replaced. */
        @JvmStatic fun forgetMachine(environmentId: String) = RemotePortCache.forget(environmentId)
    }
    suspend fun open(input: SshConnectionInput, expectedFingerprint: String?): SshTunnel = coroutineScope {
        sshInputValidationError(input)?.let { throw SshConnectionException(it) }
        synchronized(SshTunnelConnector::class.java) {
            if (Security.getProvider("BC") !is BouncyCastleProvider) { Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1) }
        }
        val client = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        val attempt = async(Dispatchers.IO) {
            try { connect(client, input, expectedFingerprint) } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        }
        try { attempt.await() } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { client.close() } }
            throw e
        }
    }
    private fun connect(client: SSHClient, input: SshConnectionInput, pin: String?): SshTunnel {
        val presented = AtomicReference<SshHostKey>()
        var tunnel: SshTunnel? = null
        var keyLoad: FutureTask<net.schmizz.sshj.userauth.keyprovider.KeyProvider>? = null
        try {
            if (input.host.isBlank() || input.username.isBlank() || input.port !in 1..65535) throw SshConnectionException("Enter a valid SSH address, port and username.")
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val info = SshHostKey(input.host, input.port, KeyType.fromKey(key).toString(), fingerprint(key))
                    presented.set(info); return pin != null && info.fingerprint == pin
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connectTimeout = 10_000; client.transport.timeoutMs = 15_000
            // Parsing/decrypting the key needs no connection, so overlap it with TCP and key exchange. Its result is only
            // used after connect() returned, i.e. after the host key was verified; a failure surfaces after that too.
            if (input.authMethod == SshAuthMethod.PrivateKey) {
                // sshj reads the key lazily on first use; force it here so the (slow, KDF-heavy) decryption happens now, not in auth.
                keyLoad = FutureTask {
                    client.loadKeys(input.privateKey.trim(), null, input.passphrase.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) })
                        .also { it.private; it.public }
                }
                Thread(keyLoad, "trefoil-ssh-keys").apply { isDaemon = true; start() }
            }
            client.connect(input.host, input.port)
            client.connection.keepAlive.keepAliveInterval = 15
            when (input.authMethod) {
                SshAuthMethod.Password -> client.authPassword(input.username.trim(), input.password.toCharArray())
                SshAuthMethod.PrivateKey -> {
                    val keys = try { keyLoad!!.get() }
                    catch (_: ExecutionException) { throw SshConnectionException("Could not unlock the private key. Check its format and passphrase; select the private key, not its .pub file.") }
                    client.authPublickey(input.username.trim(), keys)
                }
            }
            tunnel = SshTunnel(client, presented.get(), input.copy(password = "", privateKey = "", passphrase = ""))
            tunnel.initialize()
            return tunnel
        } catch (e: Exception) {
            tunnel?.close(); runCatching { client.close() }
            val key = presented.get()
            if (key != null && key.fingerprint != pin) {
                input.savedEnvironmentId?.let { RemotePortCache.forget(it) }
                throw SshHostKeyException(key, pin != null)
            }
            if (e is SshConnectionException) throw e
            throw SshConnectionException(when (e) {
                is net.schmizz.sshj.userauth.UserAuthException -> "SSH authentication failed. The server rejected the username or credentials. Edit your login and try again."
                is UnknownHostException -> "SSH hostname could not be resolved. Check the address."
                is ConnectException -> "SSH connection was refused. Check the address, SSH port and that SSH is enabled on the server."
                is SocketTimeoutException -> "SSH connection timed out. Check the address, port and network."
                else -> "SSH connection failed during login or server discovery. Check the address, credentials and running T3 installation."
            })
        } finally { keyLoad?.cancel(true) }
    }
}

class SshTunnel internal constructor(private val client: SSHClient, val hostKey: SshHostKey, private val input: SshConnectionInput) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    @Volatile private var descriptorConnection: HttpURLConnection? = null
    private val forwardedSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var listener: ServerSocket? = null
    private var forwarder: net.schmizz.sshj.connection.channel.direct.LocalPortForwarder? = null
    lateinit var baseUrl: String; private set
    lateinit var serverVersion: String; private set
    private var environmentId: String? = input.savedEnvironmentId
    private var executable: String? = null
    private var pairingAttempted = false

    /** One local listener forwarding to one remote port. [abandoned] stops its worker from closing the whole tunnel. */
    private class Forward(val socket: ServerSocket, val port: Int) {
        val abandoned = AtomicBoolean(false)
        /** True until the descriptor check passed: a failing probe (e.g. a dead remote port) must not close the tunnel. */
        @Volatile var probing = true
        @Volatile var ended = false
        @Volatile var job: Job? = null
    }
    private fun openForward(port: Int): Forward {
        val socket = object : ServerSocket() {
            override fun accept(): Socket = super.accept().also {
                forwardedSockets.add(it)
                if (closed.get()) it.close()
            }
        }.apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val forward = Forward(socket, port)
        val portForwarder = synchronized(lifecycleLock) {
            if (closed.get()) { socket.close(); throw SshConnectionException("SSH tunnel closed during discovery") }
            listener = socket
            client.newLocalPortForwarder(Parameters("127.0.0.1", socket.localPort, "127.0.0.1", port), socket).also { forwarder = it }
        }
        baseUrl = "http://127.0.0.1:${socket.localPort}/"
        // SSHJ 0.41's listener exits only when its thread is interrupted. Closing its
        // socket first makes it catch SocketException forever, even after Job.cancel().
        forward.job = scope.launch { try { runInterruptible { portForwarder.listen() } } catch (_: Exception) {} finally { forward.ended = true; if (!forward.abandoned.get() && !forward.probing) close() } }
        return forward
    }
    /** The descriptor check passed on [forward]: from now on its listener ending means the tunnel is gone. */
    private fun settle(forward: Forward) { forward.probing = false; if (forward.ended) close() }
    private fun abandon(forward: Forward) {
        forward.abandoned.set(true)
        forward.job?.cancel()
        runCatching { forward.socket.close() }
        forwardedSockets.forEach { runCatching { it.close() } }
        forwardedSockets.clear()
    }
    private fun readRuntimePort(): Int {
        val file = remotePath(input.t3Home.trimEnd('/') + "/userdata/server-runtime.json")
        // One exec for the runtime file and the liveness check (kill -0 of the pid it names). A stale file fails like a dead pid.
        val runtime = parse(exec("cat -- $file || exit 1; p=\$(sed -n 's/.*\"pid\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p' $file | head -n 1); if [ -n \"\$p\" ]; then kill -0 \"\$p\" || exit 1; fi"))
        val port = runtime["port"]?.jsonPrimitive?.intOrNull ?: 0
        val pid = runtime["pid"]?.jsonPrimitive?.longOrNull ?: 0
        val origin = runCatching { URI(runtime.string("origin")) }.getOrNull()
        if (runtime["version"]?.jsonPrimitive?.intOrNull != 1 || pid <= 0 || port !in 1..65535 || origin?.scheme != "http" || origin.host == null || origin.port != port || origin.userInfo != null || origin.query != null || origin.fragment != null || origin.path.orEmpty().trim('/').isNotEmpty() || runtime.string("devUrl").isNotBlank()) throw SshConnectionException("Unsupported T3 runtime metadata. Use a running production T3 server.")
        serverVersion = runtime.string("serverVersion")
        if (serverVersion.isNotBlank() && !validVersion(serverVersion)) throw SshConnectionException("T3 runtime version is unsupported. Specify a current production installation.")
        return port
    }
    /** Finds the T3 executable and checks it matches the running server. Needed only to create a pairing credential. */
    private fun resolveExecutable(): String {
        executable?.let { return it }
        val resolved = if (input.executable.isNotBlank()) remotePath(input.executable) else {
            val preferred = remotePath(input.t3Home.trimEnd('/') + "/runtime/versions/$serverVersion/t3")
            val fallback = remotePath("~/.t3/bin/t3")
            exec("if test -x $preferred; then printf '%s' runtime; elif test -x $fallback; then printf '%s' launcher; else exit 1; fi").trim().let { if (it == "runtime") preferred else fallback }
        }
        val version = exec("$resolved --version").trim().removePrefix("t3 ").removePrefix("v")
        if (version != serverVersion) throw SshConnectionException("The installed T3 CLI does not match the running server. Choose a matching executable.")
        executable = resolved
        return resolved
    }
    internal fun initialize() {
        val hostFingerprint = hostKey.fingerprint
        // A reconnect of a machine seen in this process skips the runtime-file read: forward to the remembered port and let
        // the descriptor check (live environment id, protocol, version) decide. Any failure falls back to the full read.
        val cachedPort = input.savedEnvironmentId?.let { RemotePortCache.get(it, hostFingerprint) }
        var forward: Forward? = null
        if (cachedPort != null) {
            serverVersion = ""
            val attempt = openForward(cachedPort)
            try { validateDescriptor(); settle(attempt); forward = attempt }
            catch (e: Exception) {
                abandon(attempt)
                input.savedEnvironmentId?.let { RemotePortCache.forget(it) }
                if (closed.get()) throw e
            }
        }
        if (forward == null) {
            val port = readRuntimePort()
            val opened = openForward(port)
            validateDescriptor(); settle(opened)
            environmentId?.let { RemotePortCache.put(it, hostFingerprint, port) }
        }
        // Pairing is the only user of the executable; a saved machine resolves it there. First-time pairing checks it now.
        if (input.savedEnvironmentId == null) resolveExecutable()
        synchronized(lifecycleLock) {
            check(!closed.get()) { "SSH tunnel closed during discovery" }
            mutableActive.value = true
        }
        scope.launch { while (isActive) { delay(500); if (!client.isConnected) { close(); break } } }
    }
    private fun validateDescriptor() {
        val connection = URI(baseUrl + ".well-known/t3/environment").toURL().openConnection() as HttpURLConnection
        descriptorConnection = connection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) throw SshConnectionException("T3 descriptor could not be verified.")
            val bytes = connection.inputStream.use { it.readBounded() }
            if (bytes.size > 65_536) throw SshConnectionException("T3 descriptor is too large.")
            val info = parse(bytes.toString(Charsets.UTF_8))
            if (info["orchestrationProtocolVersion"]?.jsonPrimitive?.intOrNull != 2 || info.string("environmentId").isBlank() || !validVersion(info.string("serverVersion")) || (serverVersion.isNotBlank() && info.string("serverVersion") != serverVersion)) throw SshConnectionException("T3 requires a compatible protocol V2 server and matching runtime version.")
            if (environmentId != null && environmentId != info.string("environmentId")) throw SshConnectionException("The T3 environment identity changed. Pair it as a new environment.")
            environmentId = info.string("environmentId")
            serverVersion = info.string("serverVersion")
        } finally { connection.disconnect(); descriptorConnection = null }
    }
    suspend fun issuePairingCredential(): String = coroutineScope {
        val attempt = async(Dispatchers.IO) {
            val attemptContext = currentCoroutineContext()
            synchronized(this@SshTunnel) {
                if (!active.value || pairingAttempted) throw SshConnectionException("Pairing was already attempted or SSH disconnected. Reconnect to reconcile its outcome.")
                try { validateDescriptor() } catch (e: SshConnectionException) { attemptContext.ensureActive(); throw e } catch (_: Exception) {
                    attemptContext.ensureActive()
                    throw SshConnectionException("T3 descriptor could not be verified. Reconnect before pairing.")
                }
                attemptContext.ensureActive()
                val resolved = try { resolveExecutable() } catch (e: SshConnectionException) { attemptContext.ensureActive(); throw e } catch (_: Exception) {
                    attemptContext.ensureActive()
                    throw SshConnectionException("T3 command failed or timed out. Check the running installation and executable.")
                }
                attemptContext.ensureActive()
                pairingAttempted = true
                try {
                    val result = parse(exec("$resolved auth pairing create --base-dir ${remotePath(input.t3Home)} --json --ttl 5m --label Trefoil"))
                    result.string("credential").takeIf { it.isNotBlank() } ?: throw SshConnectionException("T3 did not return a pairing credential. Reconnect before trying again.")
                } catch (e: SshConnectionException) { attemptContext.ensureActive(); throw e } catch (_: Exception) { attemptContext.ensureActive(); throw SshConnectionException("Pairing outcome is unknown. Reconnect and check the server before trying again.") }
            }
        }
        try { attempt.await() } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            throw e
        }
    }
    private fun exec(command: String): String {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "trefoil-ssh-output").apply { isDaemon = true } }
            try {
                val output = readers.submit<ByteArray> { cmd.inputStream.readBounded() }
                val errors = readers.submit<ByteArray> { cmd.errorStream.readBounded() }
                cmd.join(15, TimeUnit.SECONDS)
                if (!cmd.isEOF || cmd.exitStatus == null) throw SshConnectionException("T3 command timed out after 15 seconds. Check the running server and executable.")
                if (cmd.exitStatus != 0) throw SshConnectionException("T3 command failed with exit code ${cmd.exitStatus}. Check the T3 executable and data directory.")
                val bytes = output.get(1, TimeUnit.SECONDS)
                if (bytes.size > 65_536 || errors.get(1, TimeUnit.SECONDS).size > 65_536) throw SshConnectionException("T3 command returned excessive output.")
                return bytes.toString(Charsets.UTF_8)
            } catch (e: SshConnectionException) { throw e } catch (_: Exception) {
                throw SshConnectionException("T3 command failed or timed out. Check the running installation and executable.")
            } finally { runCatching { cmd.close() }; readers.shutdownNow() }
        }
    }
    /** Closes sockets synchronously; call from an IO dispatcher, including cancellation cleanup. */
    fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            mutableActive.value = false
        }
        // Cancel before closing the listener so runInterruptible interrupts its worker.
        // Avoid LocalPortForwarder.close(): it can target a stale pooled worker thread.
        scope.cancel()
        forwardedSockets.forEach { runCatching { it.close() } }
        forwardedSockets.clear()
        runCatching { descriptorConnection?.disconnect() }
        runCatching { listener?.close() }; runCatching { client.close() }
    }
}

/** Avoid newer java.io methods unavailable on supported Android releases. */
private fun java.io.InputStream.readBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < 65_537) {
        val count = read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
