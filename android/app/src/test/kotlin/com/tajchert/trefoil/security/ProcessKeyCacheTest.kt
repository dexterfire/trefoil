package com.tajchert.trefoil.security

import java.security.InvalidKeyException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ProcessKeyCacheTest {
    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { seed.toByte() }, "AES")

    @Test fun loadsTheKeyOnceForEveryRead() {
        var loads = 0
        val cache = ProcessKeyCache { loads++; key(loads) }
        val first = cache.get()
        repeat(3) { assertSame(first, cache.use { it }) }
        assertSame(first, cache.get())
        assertEquals(1, loads)
    }

    @Test fun aFailedLoadIsNotCached() {
        var loads = 0
        val cache = ProcessKeyCache { loads++; if (loads == 1) throw IllegalStateException("keystore busy") else key(loads) }
        assertThrows(IllegalStateException::class.java) { cache.get() }
        cache.get(); cache.get()
        assertEquals(2, loads)
    }

    @Test fun aRejectedKeyIsReloadedOnceAndTheNewOneKept() {
        var loads = 0
        val cache = ProcessKeyCache { loads++; key(loads) }
        val stale = cache.get()
        val used = cache.use { if (it === stale) throw InvalidKeyException("invalidated") else it }
        assertNotSame(stale, used)
        assertSame(used, cache.get())
        assertEquals(2, loads)
    }

    @Test fun aKeyRejectedTwiceFails() {
        val cache = ProcessKeyCache { key(1) }
        assertThrows(InvalidKeyException::class.java) { cache.use { throw InvalidKeyException("invalidated") } }
    }
}
