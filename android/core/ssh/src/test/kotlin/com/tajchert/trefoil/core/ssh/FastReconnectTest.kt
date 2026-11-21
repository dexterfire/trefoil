package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.SshAuthMethod
import com.tajchert.trefoil.core.model.SshConnectionInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URI

/** Reconnect of a SAVED machine (input.savedEnvironmentId set) against the public fixture SSH/T3 servers. */
class FastReconnectTest {
    @Before @After fun clearCache() = RemotePortCache.clear()

    private fun TunnelIntegrationTest.Fixture.saved() = input().copy(savedEnvironmentId = "fixture")
    private suspend fun TunnelIntegrationTest.Fixture.hostKey() =
        try { SshTunnelConnector().open(saved(), null); error("Missing trust rejection") } catch (e: SshHostKeyException) { e.presented }

    @Test fun firstReconnectReadsRuntimeOnceWithoutExecutableLookup() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(tunnel.active.value)
            assertEquals(1, fixture.commands.size)
            assertTrue(fixture.commands.single().startsWith("cat -- "))
            // One exec also carries the liveness check; nothing else is run on a saved machine.
            assertTrue(fixture.commands.single().contains("kill -0"))
            assertFalse(fixture.commands.any { it.startsWith("if test") || it.endsWith("--version") })
            tunnel.close()
        }
    }

    @Test fun secondReconnectRunsNoRemoteCommandAndUsesTheCachedPort() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            SshTunnelConnector().open(fixture.saved(), key.fingerprint).close()
            fixture.commands.clear()
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(tunnel.active.value)
            assertTrue("Unexpected commands ${fixture.commands}", fixture.commands.isEmpty())
            assertEquals("forwarded-payload", URI(tunnel.baseUrl + "payload").toURL().readText())
            tunnel.close()
        }
    }

    @Test fun staleCachedPortFallsBackToFullRuntimeReadAndRefreshesTheCache() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            RemotePortCache.put("fixture", key.fingerprint, 1)
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(tunnel.active.value)
            assertEquals(1, fixture.commands.count { it.startsWith("cat -- ") })
            assertEquals(fixture.http.port, RemotePortCache.get("fixture", key.fingerprint))
            assertEquals("forwarded-payload", URI(tunnel.baseUrl + "payload").toURL().readText())
            tunnel.close()
        }
    }

    @Test fun changedEnvironmentOnCachedPortIsRejectedAndForgotten() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            SshTunnelConnector().open(fixture.saved(), key.fingerprint).close()
            fixture.environmentId = "replacement"
            try { SshTunnelConnector().open(fixture.saved(), key.fingerprint); fail("Environment changed") } catch (_: SshConnectionException) {}
            assertNull(RemotePortCache.get("fixture", key.fingerprint))
        }
    }

    @Test fun cacheIsBoundToMachineAndHostKey() {
        RemotePortCache.put("alpha", "SHA256:one", 4000)
        assertEquals(4000, RemotePortCache.get("alpha", "SHA256:one"))
        assertNull(RemotePortCache.get("beta", "SHA256:one"))
        assertNull(RemotePortCache.get("alpha", "SHA256:two"))
        assertNull("A different host key drops the entry", RemotePortCache.get("alpha", "SHA256:one"))
        RemotePortCache.put("alpha", "SHA256:one", 4000)
        SshTunnelConnector.forgetMachine("alpha")
        assertNull(RemotePortCache.get("alpha", "SHA256:one"))
    }

    @Test fun changedHostKeyFailsClosedAndClearsTheMachineEntry() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            RemotePortCache.put("fixture", key.fingerprint, fixture.http.port)
            try { SshTunnelConnector().open(fixture.saved(), "SHA256:pinned-elsewhere"); fail("Changed key accepted") }
            catch (e: SshHostKeyException) { assertTrue(e.changed); assertEquals(key.fingerprint, e.presented.fingerprint) }
            assertNull(RemotePortCache.get("fixture", key.fingerprint))
            assertTrue("No remote command before the key is trusted", fixture.commands.isEmpty())
        }
    }

    @Test fun savedMachineResolvesExecutableLazilyForPairing() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertFalse(fixture.commands.any { it.startsWith("if test") })
            assertEquals("fixture-only-credential", tunnel.issuePairingCredential())
            val lookup = fixture.commands.indexOfFirst { it.startsWith("if test") }
            val version = fixture.commands.indexOfFirst { it.endsWith("--version") }
            val pairing = fixture.commands.indexOfFirst { it.contains("auth pairing create") }
            assertTrue(lookup in 0 until version && version < pairing)
            tunnel.close()
        }
    }

    @Test fun savedMachineWithMismatchedCliStillRefusesPairingBeforeCreate() = runBlocking {
        TunnelIntegrationTest.Fixture(cliVersion = "0.2.0").use { fixture ->
            val key = fixture.hostKey()
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(tunnel.active.value)
            try { tunnel.issuePairingCredential(); fail("Mismatched CLI accepted") } catch (_: SshConnectionException) {}
            assertFalse(fixture.commands.any { it.contains("auth pairing create") })
            tunnel.close()
        }
    }

    @Test fun firstTimePairingStillChecksTheExecutableOnOpen() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val tunnel = fixture.open()
            assertTrue(fixture.commands.any { it.startsWith("if test") })
            assertTrue(fixture.commands.any { it.endsWith("--version") })
            tunnel.close()
        }
    }

    @Test fun firstTimePairingPopulatesTheCacheForTheNextReconnect() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            // No saved id: an unsaved pairing attempt does not use or read the cache, but teaches it its environment.
            SshTunnelConnector().open(fixture.input(), key.fingerprint).close()
            assertEquals(fixture.http.port, RemotePortCache.get("fixture", key.fingerprint))
        }
    }

    @Test fun privateKeyDecryptionFailureSurfacesOnlyAfterHostKeyIsVerified() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val pem = javaClass.getResource("/keys/ed25519_enc")!!.readText()
            val input = fixture.input().copy(authMethod = SshAuthMethod.PrivateKey, password = "", privateKey = pem, passphrase = "wrong")
            try { SshTunnelConnector().open(input, null); fail("Untrusted key accepted") } catch (e: SshHostKeyException) {
                try { SshTunnelConnector().open(input, e.presented.fingerprint); fail("Wrong passphrase accepted") }
                catch (unlock: SshConnectionException) { assertTrue(unlock.message.orEmpty().contains("Could not unlock")) }
            }
            assertTrue(fixture.commands.isEmpty())
        }
    }
}
