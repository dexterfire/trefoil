package com.tajchert.trefoil.core.ssh

import com.tajchert.trefoil.core.model.SshAuthMethod
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reconnect of a SAVED machine (input.savedEnvironmentId set) against the public fixture SSH/T3 servers. */
class FastReconnectTest {
    private fun TunnelIntegrationTest.Fixture.saved() = input().copy(savedEnvironmentId = "fixture")
    private suspend fun TunnelIntegrationTest.Fixture.hostKey() =
        try { SshTunnelConnector().open(saved(), null); error("Missing trust rejection") } catch (e: SshHostKeyException) { e.presented }

    /**
     * The script is POSIX sh over a POSIX pid (`cat --`, `tr`, `grep -o`, `kill -0`): Windows has neither, so the check
     * runs only where it is meaningful and is skipped there instead of failing. Linux, macOS and CI keep running it.
     */
    private fun hasPosixShell(): Boolean {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return false
        return try {
            val probe = ProcessBuilder("sh", "-c", "exit 0").redirectErrorStream(true).start()
            val answered = try { probe.waitFor(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!answered) probe.destroy()
            answered && probe.exitValue() == 0
        } catch (_: IOException) { false }
    }

    @Test fun savedReconnectUsesOneExecAndNoExecutableLookup() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(tunnel.active.value)
            assertEquals(1, fixture.commands.size)
            assertTrue(fixture.commands.single().contains("kill -0"))
            assertFalse(fixture.commands.any { it.startsWith("if test") || it.endsWith("--version") })
            tunnel.close()
        }
    }

    @Test fun everyRuntimeCheckStillRunsForSavedMachines() = runBlocking {
        val bad = listOf(
            "devUrl" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":1,"pid":123,"port":${f.http.port},"origin":"http://192.0.2.1:${f.http.port}","devUrl":"http://localhost:3000"}""" },
            "origin port" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":1,"pid":123,"port":${f.http.port},"origin":"http://192.0.2.1:1"}""" },
            "https origin" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":1,"pid":123,"port":${f.http.port},"origin":"https://192.0.2.1:${f.http.port}"}""" },
            "version" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":2,"pid":123,"port":${f.http.port},"origin":"http://192.0.2.1:${f.http.port}"}""" },
            "pid" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":1,"pid":0,"port":${f.http.port},"origin":"http://192.0.2.1:${f.http.port}"}""" },
            "server version" to { f: TunnelIntegrationTest.Fixture -> f.runtimeBody = """{"version":1,"pid":123,"port":${f.http.port},"origin":"http://192.0.2.1:${f.http.port}","serverVersion":"nope"}""" },
        )
        for ((name, tamper) in bad) TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            tamper(fixture)
            try { SshTunnelConnector().open(fixture.saved(), key.fingerprint); fail("Accepted bad runtime: $name") } catch (_: SshConnectionException) {}
        }
    }

    @Test fun deadPidFailsTheConnect() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            fixture.trailer = "none"; fixture.killExit = 1
            try { SshTunnelConnector().open(fixture.saved(), key.fingerprint); fail("Dead pid accepted") } catch (_: SshConnectionException) {}
        }
    }

    @Test fun livenessVerdictForAnotherPidIsNotTrustedAndIsRecheckedForTheSnapshotPid() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            fixture.trailer = "wrong"
            val tunnel = SshTunnelConnector().open(fixture.saved(), key.fingerprint)
            assertTrue(fixture.commands.contains("kill -0 123"))
            tunnel.close()
        }
    }

    @Test fun missingVerdictIsRecheckedAndAFailingRecheckFailsClosed() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            fixture.trailer = "none"
            SshTunnelConnector().open(fixture.saved(), key.fingerprint).close()
            assertTrue(fixture.commands.contains("kill -0 123"))
            fixture.killExit = 1
            try { SshTunnelConnector().open(fixture.saved(), key.fingerprint); fail("Failed kill accepted") } catch (_: SshConnectionException) {}
        }
    }

    @Test fun matchingVerdictNeedsNoSecondExec() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val key = fixture.hostKey()
            SshTunnelConnector().open(fixture.saved(), key.fingerprint).close()
            assertFalse(fixture.commands.any { it.startsWith("kill ") })
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

    @Test fun cancellingWhileTheKeyIsStillDecryptingStopsWaitingAndNeverAuthenticates() = runBlocking {
        TunnelIntegrationTest.Fixture().use { fixture ->
            val pem = javaClass.getResource("/keys/ed25519_enc")!!.readText()
            val input = fixture.input().copy(authMethod = SshAuthMethod.PrivateKey, password = "", privateKey = pem, passphrase = "secret")
            val fingerprint = try { SshTunnelConnector().open(input, null); error("Missing trust rejection") } catch (e: SshHostKeyException) { e.presented.fingerprint }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            // Like the bcrypt loop: ignores interruption until it is done.
            val stuck = KeyLoader { _, _ ->
                started.countDown()
                var interrupted = false
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { interrupted = true }
                if (interrupted) Thread.currentThread().interrupt()
                throw IOException("released")
            }
            val attempt = launch(Dispatchers.Default) { SshTunnelConnector(stuck).open(input, fingerprint) }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            delay(300) // the SSH handshake completes while decryption is still running
            withTimeout(5_000) { attempt.cancelAndJoin() }
            release.countDown()
            delay(300)
            assertEquals(0, fixture.authAttempts.get())
            withTimeout(5_000) { while (fixture.ssh.activeSessions.isNotEmpty()) delay(20) }
        }
    }

    @Test(timeout = 30_000) fun runtimeReadScriptVerifiesTheSnapshotPidInRealShell() {
        Assume.assumeTrue("No POSIX sh with POSIX pids on this machine", hasPosixShell())
        val live = ProcessHandle.current().pid()
        val dir = kotlin.io.path.createTempDirectory("runtime-read").toFile()
        try {
            fun run(body: String): Pair<Int, String> {
                val file = File(dir, "server-runtime.json").apply { writeText(body) }
                val process = ProcessBuilder("sh", "-c", runtimeReadScript(remoteQuote(file.path), "n0nce")).redirectErrorStream(false).start()
                val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
                return process.waitFor() to out
            }
            fun checked(out: String) = parseRuntimeRead(out, "n0nce").checkedPid
            val oneLine = run("""{"version":1,"pid":$live,"port":1}""")
            assertEquals(0, oneLine.first); assertEquals(live, checked(oneLine.second))
            val newlineBeforeValue = run("{\n  \"version\": 1,\n  \"pid\":\n    $live,\n  \"port\": 1\n}\n")
            assertEquals(0, newlineBeforeValue.first); assertEquals(live, checked(newlineBeforeValue.second))
            assertEquals(1L, parseRuntimeRead(newlineBeforeValue.second, "n0nce").runtime["version"]!!.toString().toLong())
            // Two pid fields on the snapshot: no verdict, so the client checks the top-level pid itself.
            val nested = run("""{"meta":{"pid":999999999},"version":1,"pid":$live,"port":1}""")
            assertEquals(0, nested.first); assertNull(checked(nested.second))
            assertEquals(live, parseRuntimeRead(nested.second, "n0nce").runtime["pid"]!!.toString().toLong())
            val nestedOnly = run("""{"meta":{"pid":$live},"version":1,"port":1}""")
            assertEquals(live, checked(nestedOnly.second)) // client then sees no top-level pid and rejects the metadata
            assertNull(parseRuntimeRead(nestedOnly.second, "n0nce").runtime["pid"])
            val dead = ProcessBuilder("true").start().also { it.waitFor() }.pid()
            // A failed probe is no verdict: the snapshot still comes back and the client runs its own kill -0.
            val deadOnly = run("""{"version":1,"pid":$dead,"port":1}""")
            assertEquals(0, deadOnly.first); assertNull(checked(deadOnly.second))
            assertEquals(dead, parseRuntimeRead(deadOnly.second, "n0nce").runtime["pid"]!!.toString().toLong())
            // The top-level key is escaped, so the shell pattern only sees the dead nested pid. The exec must still return
            // the snapshot so the client can verify the live top-level pid itself.
            val escaped = run("""{"meta":{"pid":$dead},"version":1,"\u0070id":$live,"port":1}""")
            assertEquals(0, escaped.first); assertNull(checked(escaped.second))
            assertEquals(live, parseRuntimeRead(escaped.second, "n0nce").runtime["pid"]!!.toString().toLong())
            File(dir, "server-runtime.json").delete()
            val missing = ProcessBuilder("sh", "-c", runtimeReadScript(remoteQuote(File(dir, "server-runtime.json").path), "n0nce")).start()
            missing.inputStream.readBytes(); assertEquals(1, missing.waitFor())
        } finally { dir.deleteRecursively() }
    }

    @Test fun trailerWithAnotherNonceIsNotAVerdict() {
        assertNull(parseRuntimeRead("{\"pid\":1}\n", "mine").checkedPid)
        try { parseRuntimeRead("{\"pid\":1}\ntrefoil-live-other:1\n", "mine"); fail("Foreign trailer parsed as JSON") } catch (_: Exception) {}
    }
}
