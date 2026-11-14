package com.tajchert.trefoil.core.session

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest

/** Queue fixtures: answers the optional post-connect link-state read with 404 without consuming a queued response. */
internal class LinkStateTolerantQueue : QueueDispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse =
        if (request.path == "/api/connect/link-state") MockResponse().setResponseCode(404) else super.dispatch(request)
}
