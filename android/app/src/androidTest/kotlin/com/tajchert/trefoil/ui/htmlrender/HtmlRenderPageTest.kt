package com.tajchert.trefoil.ui.htmlrender

import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.tajchert.trefoil.ui.TrefoilDark
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads a fixture page in the real hardened WebView. JVM tests cannot catch WebView request details, such as
 * the main-frame URL arriving with its `#t3-theme=` fragment (which once made every page fail).
 */
@RunWith(AndroidJUnit4::class)
class HtmlRenderPageTest {
    private val fixture = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style id="t3-theme">:root{color-scheme:dark;--background:#0a0a0a;--foreground:#f5f5f5}</style></head><body>
<div id="g"></div><script>document.getElementById('g').insertAdjacentHTML('beforeend', '<div style="height:240px">Fixture</div>');</script>
</body></html>"""

    @Test fun fixturePageLoadsAndReportsHeight() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reported = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val height = AtomicReference<Float?>(null)
        lateinit var page: HtmlRenderPage
        instrumentation.runOnMainSync {
            page = HtmlRenderPage(instrumentation.targetContext, fixture, "fixture-attachment-html", htmlRenderTheme(TrefoilDark), HtmlRenderNetworkAccess.Offline, fullScreen = false)
            page.onFailure = { failed.set(true) }
            page.onHeight = { height.set(it); reported.countDown() }
            page.root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(900, MeasureSpec.EXACTLY))
            page.root.layout(0, 0, 1080, 900)
        }
        try {
            reported.await(20, TimeUnit.SECONDS)
            assertNotNull("page never reported its height (main document refused?)", height.get())
            assertFalse("page reported a failure", failed.get())
        } finally {
            instrumentation.runOnMainSync { page.release() }
        }
    }
}
