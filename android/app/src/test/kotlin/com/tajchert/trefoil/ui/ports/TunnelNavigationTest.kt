package com.tajchert.trefoil.ui.ports

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelNavigationTest {
    private val bound = "http://localhost:5173/"
    private fun nav(target: String, gesture: Boolean = true) = tunnelNavigation(bound, target, gesture)

    @Test fun boundOriginStaysInApp() {
        assertEquals(TunnelNavigation.Allow, nav("http://localhost:5173/app?x=1#y", gesture = false))
        assertEquals(TunnelNavigation.Allow, nav("http://127.0.0.1:5173/"))
        assertEquals(TunnelNavigation.Allow, nav("HTTP://LOCALHOST:5173/"))
    }

    @Test fun otherLoopbackPortsNamesAndSchemesAreBlocked() {
        assertEquals(TunnelNavigation.Block, nav("http://localhost:5174/"))
        assertEquals(TunnelNavigation.Block, nav("http://localhost/"))
        assertEquals(TunnelNavigation.Block, nav("https://localhost:5173/"))
        assertEquals(TunnelNavigation.Block, nav("http://[::1]:5173/"))
        assertEquals(TunnelNavigation.Block, nav("http://127.0.0.2:5173/"))
        assertEquals(TunnelNavigation.Block, nav("http://0.0.0.0:5173/"))
        assertEquals(TunnelNavigation.Block, nav("http://app.localhost:5173/"))
    }

    @Test fun portIsNormalizedWhenBoundUrlHasNone() {
        assertEquals(TunnelNavigation.Allow, tunnelNavigation("http://localhost/", "http://localhost:80/", true))
    }

    @Test fun nonHttpSchemesAndCredentialsAreBlocked() {
        listOf("file:///etc/hosts", "content://x/y", "intent://x#Intent;end", "javascript:alert(1)", "data:text/html,hi", "about:blank", "ftp://example.com/", "market://details?id=a")
            .forEach { assertEquals(it, TunnelNavigation.Block, nav(it)) }
        assertEquals(TunnelNavigation.Block, nav("http://user:pw@example.com/"))
        assertEquals(TunnelNavigation.Block, nav("not a url"))
    }

    @Test fun otherWebLinksGoExternalOnlyWithGesture() {
        assertEquals(TunnelNavigation.External("https://example.com/docs"), nav("https://example.com/docs"))
        assertEquals(TunnelNavigation.External("http://192.168.1.5:3000/"), nav("http://192.168.1.5:3000/"))
        assertEquals(TunnelNavigation.Block, nav("https://example.com/docs", gesture = false))
    }
}
