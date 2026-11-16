package com.tajchert.trefoil.keepalive

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide entry point for the opt-in foreground service that keeps the live T3 connection open while Trefoil is in
 * the background, so returning to the app does not reconnect. The view model decides when it runs; this only drives it.
 */
object ConnectionKeepAlive {
    private val _disconnectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user taps Disconnect on the keep-alive notification; the view model disconnects (which stops it). */
    val disconnectRequests: SharedFlow<Unit> = _disconnectRequests.asSharedFlow()

    /**
     * Starts the service, or updates its notification, for [machineName]. Returns false when the OS refuses (for example a
     * background start restriction); never throws. Call it while the app is in the foreground.
     */
    fun start(context: Context, machineName: String): Boolean = false

    /** Stops the service if it runs; safe to call at any time. */
    fun stop(context: Context) {}

    internal fun requestDisconnect() { _disconnectRequests.tryEmit(Unit) }
}
