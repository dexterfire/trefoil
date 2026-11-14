package com.tajchert.trefoil

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairLinkIntentTest {
    private val link = "t3code://pair?pairingUrl=https%3A%2F%2Fexample.test%2Fpair%23token%3Dfixture-token"
    private fun view(uri: String?) = Intent(Intent.ACTION_VIEW).apply { if (uri != null) data = Uri.parse(uri) }

    @Test fun acceptsPairLinkAndReturnsItUnparsed() {
        assertEquals(link, parsePairLinkIntent(view(link)))
        val upper = link.replaceFirst("t3code", "T3CODE")
        assertEquals(upper, parsePairLinkIntent(view(upper)))
    }

    @Test fun acceptsMalformedPairLinkSoTheDialogShowsItsNormalError() {
        assertEquals("t3code://pair?junk", parsePairLinkIntent(view("t3code://pair?junk")))
    }

    @Test fun ignoresOtherHostsSchemesAndMissingData() {
        assertNull(parsePairLinkIntent(view("t3code://other?pairingUrl=fixture-token")))
        assertNull(parsePairLinkIntent(view("https://example.test/pair#token=fixture-token")))
        assertNull(parsePairLinkIntent(view("http://pair/?token=fixture-token")))
        assertNull(parsePairLinkIntent(view(null)))
        assertNull(parsePairLinkIntent(null))
    }

    @Test fun ignoresOtherActionsOversizedLinksAndRecentsRedelivery() {
        assertNull(parsePairLinkIntent(Intent(Intent.ACTION_MAIN).setData(Uri.parse(link))))
        assertNull(parsePairLinkIntent(view("t3code://pair?pairingUrl=" + "a".repeat(5000))))
        assertNull(parsePairLinkIntent(view(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }
}
