package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MachineProberTest {
    private val direct = SavedEnvironment("lan", "Mac mini", "https://mini.example.test/", "fixture-token")
    private val lan = EnvironmentRoute(RouteKind.Lan, "https://192.168.1.5/", RouteSource.Learned)
    private val tunnel = EnvironmentRoute(RouteKind.Tunnel, "https://prod-abc.t3coderelay.com/", RouteSource.Learned)
    private fun ssh(password: String? = "fixture-password", fingerprint: String = "SHA256:pinned") =
        SavedEnvironment("ssh", "home", "", "fixture-token", ssh = SshProfile("home.test", 22, "mike", fingerprint, "ssh-ed25519", password = password))

    private class FakeTunnel : ProbeTunnel {
        override val baseUrl = "http://127.0.0.1:41000/"
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun directMachinesUseTheSavedAddress() = runBlocking {
        var seen: List<EnvironmentRoute>? = null
        val prober = MachineProber({ _, routes -> seen = routes; MachineProbe.Online }, { _, _ -> error("SSH is not used") })
        assertEquals(MachineProbe.Online, prober.probe(direct, emptyList()))
        assertEquals(listOf(EnvironmentRoute(RouteKind.Public, direct.baseUrl, RouteSource.Paired)), seen)
    }

    @Test fun directMachinesProbeTheirWholePlanInOrder() = runBlocking {
        var seen: List<EnvironmentRoute>? = null
        val machine = direct.copy(routes = listOf(lan, tunnel))
        assertEquals(MachineProbe.Online, MachineProber({ _, routes -> seen = routes; MachineProbe.Online }, { _, _ -> error("unused") }).probe(machine, emptyList()))
        assertEquals(listOf(RouteKind.Lan, RouteKind.Public, RouteKind.Tunnel), seen?.map { it.kind })
    }

    @Test fun routeAdapterStopsOnlineOrOnAuthAndRemembersIdentityMismatch() = runBlocking {
        val routes = listOf(lan, EnvironmentRoute(RouteKind.Public, "https://mini.example.test/", RouteSource.Paired), tunnel)
        suspend fun probe(results: Map<RouteKind, MachineProbe>): Pair<MachineProbe, List<RouteKind>> {
            val asked = mutableListOf<RouteKind>()
            return probeRoutes(routes) { asked += it.kind; results[it.kind] ?: MachineProbe.Unreachable } to asked
        }
        assertEquals(MachineProbe.Online to listOf(RouteKind.Lan, RouteKind.Public, RouteKind.Tunnel), probe(mapOf(RouteKind.Tunnel to MachineProbe.Online)))
        assertEquals(MachineProbe.AuthFailed to listOf(RouteKind.Lan, RouteKind.Public), probe(mapOf(RouteKind.Public to MachineProbe.AuthFailed, RouteKind.Tunnel to MachineProbe.Online)))
        assertEquals(MachineProbe.NeedsAttention to listOf(RouteKind.Lan, RouteKind.Public, RouteKind.Tunnel), probe(mapOf(RouteKind.Lan to MachineProbe.NeedsAttention)))
        assertEquals(MachineProbe.Online, probe(mapOf(RouteKind.Lan to MachineProbe.NeedsAttention, RouteKind.Tunnel to MachineProbe.Online)).first)
        assertEquals(MachineProbe.Unreachable, probe(emptyMap()).first)
        // A dead LAN address only gets its short budget before the tunnel is asked.
        assertEquals(MachineProbe.Online, probeRoutes(listOf(lan, tunnel)) { if (it.kind == RouteKind.Lan) awaitCancellation() else MachineProbe.Online })
    }

    @Test fun sshMachinesTryLearnedDirectRoutesThenSshThenTheTunnel() = runBlocking {
        val asked = mutableListOf<String>()
        val machine = ssh().copy(routes = listOf(lan, tunnel))
        val prober = MachineProber({ _, routes -> asked += routes.joinToString(",") { it.kind.name }; if (routes.single().kind == RouteKind.Tunnel) MachineProbe.Online else MachineProbe.Unreachable },
            { _, _ -> asked += "open"; FakeTunnel() })
        assertEquals(MachineProbe.Online, prober.probe(machine, emptyList()))
        assertEquals(listOf("Lan", "open", "Ssh", "Tunnel"), asked)
    }

    @Test fun aRouteThePlanFilterDropsIsNotProbedEvenWhenItIsTheOnlyRouteBeforeSsh() = runBlocking {
        val asked = mutableListOf<String>()
        // On plain cellular the connect walks only [Ssh, Tunnel]; the LAN leg before SSH must not come back as its own group.
        val prober = MachineProber({ _, routes -> asked += routes.joinToString(",") { it.kind.name }; MachineProbe.Unreachable },
            { _, _ -> asked += "open"; FakeTunnel() }, plan = { usableRoutes(it.candidateRoutes(), cellularOnly = true) })
        prober.probe(ssh().copy(routes = listOf(lan, tunnel)), emptyList())
        assertEquals(listOf("open", "Ssh", "Tunnel"), asked)
        // Off cellular the same machine probes its LAN address first, as before.
        asked.clear()
        MachineProber({ _, routes -> asked += routes.joinToString(",") { it.kind.name }; MachineProbe.Unreachable },
            { _, _ -> asked += "open"; FakeTunnel() }, plan = { usableRoutes(it.candidateRoutes(), cellularOnly = false) })
            .probe(ssh().copy(routes = listOf(lan, tunnel)), emptyList())
        assertEquals(listOf("Lan", "open", "Ssh", "Tunnel"), asked)
    }

    @Test fun sshMachineWithoutCredentialsStillProbesItsTunnel() = runBlocking {
        val machine = ssh(password = null).copy(routes = listOf(tunnel))
        var opened = false
        assertEquals(MachineProbe.Online, MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> opened = true; FakeTunnel() }).probe(machine, emptyList()))
        assertFalse(opened)
        assertEquals(MachineProbe.Unknown, MachineProber({ _, _ -> MachineProbe.Unreachable }, { _, _ -> error("unused") }).probe(machine, emptyList()))
    }

    @Test fun sshLoginFailureFallsBackToTheTunnelButATokenRejectionIsDecisive() = runBlocking {
        val machine = ssh().copy(routes = listOf(tunnel))
        val loginRejected = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> throw SshConnectionException("SSH authentication failed. The server rejected the username or credentials.") })
        assertEquals(MachineProbe.Online, loginRejected.probe(machine, emptyList()))
        val unreachableTunnel = MachineProber({ _, _ -> MachineProbe.Unreachable }, { _, _ -> throw SshConnectionException("SSH authentication failed. The server rejected the username or credentials.") })
        assertEquals(MachineProbe.AuthFailed, unreachableTunnel.probe(machine, emptyList()))
        var tunnelAsked = false
        val tokenRejected = MachineProber({ _, routes -> if (routes.single().kind == RouteKind.Tunnel) { tunnelAsked = true; MachineProbe.Online } else MachineProbe.AuthFailed }, { _, _ -> FakeTunnel() })
        assertEquals(MachineProbe.AuthFailed, tokenRejected.probe(machine, emptyList()))
        assertFalse(tunnelAsked)
    }

    @Test fun directTimeoutAndFailuresAreUnreachable() = runBlocking {
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> awaitCancellation() }, { _, _ -> error("unused") }, directTimeoutMillis = { 50 }).probe(direct, emptyList()))
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> error("boom") }, { _, _ -> error("unused") }).probe(direct, emptyList()))
    }

    @Test fun deadRoutesNeverStarveTheTunnelOfAFullPlan() = runTest {
        val tailnet = EnvironmentRoute(RouteKind.Tailnet, "https://mac.tail1234.ts.net/", RouteSource.Learned)
        val public = EnvironmentRoute(RouteKind.Public, "https://mac.example/", RouteSource.Paired)
        val machine = direct.copy(routes = listOf(lan, tailnet, public, tunnel))
        // Lan, Tailnet and Public hang for their whole budget (3 + 3 + 8 s); the tunnel still gets its turn.
        val prober = MachineProber({ _, routes -> probeRoutes(routes) { if (it.kind == RouteKind.Tunnel) MachineProbe.Online else awaitCancellation() } }, { _, _ -> error("unused") })
        assertEquals(MachineProbe.Online, prober.probe(machine, emptyList()))
        assertEquals(24_000L, directGroupTimeoutMillis(listOf(lan, tailnet, public, tunnel)))
    }

    @Test fun sshWithoutSavedCredentialsStaysUnknownWithoutConnecting() = runBlocking {
        var opened = false
        val prober = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> opened = true; FakeTunnel() })
        assertEquals(MachineProbe.Unknown, prober.probe(ssh(password = null), emptyList()))
        assertFalse(opened)
    }

    @Test fun sshProbesThroughAPinnedTunnelAndAlwaysClosesIt() = runBlocking {
        val tunnel = FakeTunnel()
        var pin: String? = null
        var url: String? = null
        val prober = MachineProber({ _, routes -> url = routes.single().baseUrl; MachineProbe.Online }, { _, expected -> pin = expected; tunnel })
        val trusted = listOf(SshHostKey("HOME.test", 22, "ssh-ed25519", "SHA256:trusted-store"))
        assertEquals(MachineProbe.Online, prober.probe(ssh(), trusted))
        assertEquals("SHA256:trusted-store", pin)
        assertEquals(tunnel.baseUrl, url)
        assertTrue(tunnel.closed)

        val failing = FakeTunnel()
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> error("boom") }, { _, _ -> failing }).probe(ssh(), emptyList()))
        assertTrue(failing.closed)
    }

    @Test fun sshHostKeyProblemsNeverPromptAndNeedAttention() = runBlocking {
        val key = SshHostKey("home.test", 22, "ssh-ed25519", "SHA256:other")
        val changed = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> throw SshHostKeyException(key, changed = true) })
        assertEquals(MachineProbe.NeedsAttention, changed.probe(ssh(), emptyList()))
        // No pinned key at all: a probe must not open a connection that would ask for trust.
        var opened = false
        val unpinned = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> opened = true; FakeTunnel() })
        assertEquals(MachineProbe.NeedsAttention, unpinned.probe(ssh(fingerprint = ""), emptyList()))
        assertFalse(opened)
    }

    @Test fun sshLoginFailuresMapToAuthOrUnreachable() = runBlocking {
        fun failing(message: String) = MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> throw SshConnectionException(message) })
        assertEquals(MachineProbe.AuthFailed, failing("SSH authentication failed. The server rejected the username or credentials. Edit your login and try again.").probe(ssh(), emptyList()))
        assertEquals(MachineProbe.Unreachable, failing("SSH connection timed out. Check the address, port and network.").probe(ssh(), emptyList()))
        assertEquals(MachineProbe.Unreachable, MachineProber({ _, _ -> MachineProbe.Online }, { _, _ -> awaitCancellation() }, sshTimeoutMillis = 50).probe(ssh(), emptyList()))
    }

    @Test fun autoConnectPrefersTheLastUsedMachine() {
        val a = SavedEnvironment("a", "A", "https://a.test/", "t")
        val b = SavedEnvironment("b", "B", "https://b.test/", "t")
        val c = SavedEnvironment("c", "C", "https://c.test/", "t")
        val all = listOf(a, b, c)
        assertEquals(AutoConnectDecision.Connect("b"), autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Online, "c" to MachineProbe.Checking)))
        assertEquals(AutoConnectDecision.Wait, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Checking)))
        // Last-used unreachable: fall back to the only reachable machine once every probe is done.
        assertEquals(AutoConnectDecision.Wait, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.Checking)))
        assertEquals(AutoConnectDecision.Connect("a"), autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.AuthFailed)))
        assertEquals(AutoConnectDecision.None, autoConnectDecision("b", all, mapOf("a" to MachineProbe.Online, "b" to MachineProbe.Unreachable, "c" to MachineProbe.Online)))
        assertEquals(AutoConnectDecision.Connect("c"), autoConnectDecision(null, all, mapOf("a" to MachineProbe.Unknown, "b" to MachineProbe.NeedsAttention, "c" to MachineProbe.Online)))
        assertEquals(AutoConnectDecision.None, autoConnectDecision("forgotten", all, emptyMap()))
        assertEquals(AutoConnectDecision.None, autoConnectDecision(null, emptyList(), emptyMap()))
    }
}
