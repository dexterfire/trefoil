package com.tajchert.trefoil

import okhttp3.OkHttpClient

/**
 * The one [OkHttpClient] of this process. [com.tajchert.trefoil.core.session.T3Client],
 * [com.tajchert.trefoil.core.session.EnvironmentTokenRenewer] and [com.tajchert.trefoil.core.session.EnvironmentProbe]
 * each derive their own settings from it with `newBuilder()`, which keeps its connection pool and dispatcher: a probe's
 * or a renewal's TLS connection is reused by the client instead of being set up again on a slow link.
 */
internal object AppHttp {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
