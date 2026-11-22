package com.tajchert.trefoil

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlainCellularTest {
    private fun capabilities(vararg transports: Int) = ShadowNetworkCapabilities.newInstance().also { caps ->
        transports.forEach { shadowOf(caps).addTransportType(it) }
    }

    @Test fun onlyCellularWithoutALocalTransportOrVpnIsPlainCellular() {
        assertTrue(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR).isPlainCellular())
        assertFalse(capabilities(NetworkCapabilities.TRANSPORT_WIFI).isPlainCellular())
        assertFalse(capabilities(NetworkCapabilities.TRANSPORT_ETHERNET).isPlainCellular())
        assertFalse("A VPN (Tailscale) may reach private and tailnet addresses",
            capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN).isPlainCellular())
        assertFalse(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_USB).isPlainCellular())
        assertFalse("Unknown transports keep every route", capabilities().isPlainCellular())
    }
}
