package com.tajchert.trefoil.keepalive

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide entry point for the opt-in foreground service that keeps the live T3 connection open while Trefoil is in
 * the background, so returning to the app does not reconnect. The view model decides when it runs; this only drives it.
 */
object ConnectionKeepAlive {
    private val _disconnectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user taps Disconnect on the keep-alive notification; the view model disconnects (which stops it). */
    val disconnectRequests: SharedFlow<Unit> = _disconnectRequests.asSharedFlow()

    private val _running = MutableStateFlow(false)
    /** The service is in the foreground: true once startForeground succeeded, false when it is stopped or destroyed. */
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _starting = MutableStateFlow(false)
    /** An accepted start the service has not handled yet; with [running] false and this false, the service is gone. */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val lock = Any()
    private var pendingStarts = 0
    /** A stop arrived while a start was pending: the service must call startForeground first (or the OS kills the app). */
    private var stopRequested = false

    /**
     * Starts the service, or updates its notification, for [machineName] ([reconnecting]: "Reconnecting to …"). Returns
     * false when the OS refuses (for example a background start restriction); never throws. A running service only gets
     * its notification updated, so this also works in the background; a first start needs the foreground.
     *
     * The service itself calls startForeground asynchronously; if the OS refuses there, the service stops itself and
     * [running] and [starting] both end false.
     */
    fun start(context: Context, machineName: String, reconnecting: Boolean = false): Boolean {
        synchronized(lock) {
            if (_running.value && pendingStarts == 0) {
                ConnectionKeepAliveService.updateNotification(context, machineName, reconnecting)
                return true
            }
            pendingStarts++; _starting.value = true
        }
        val accepted = try {
            ContextCompat.startForegroundService(context, ConnectionKeepAliveService.startIntent(context, machineName, reconnecting))
            true
        } catch (e: IllegalStateException) { false } // includes ForegroundServiceStartNotAllowedException (API 31+)
        catch (e: SecurityException) { false }
        synchronized(lock) {
            if (accepted) stopRequested = false
            else { pendingStarts--; _starting.value = pendingStarts > 0 }
        }
        return accepted
    }

    /**
     * Stops the service if it runs; safe to call at any time. With a start still pending it never calls stopService
     * (Android kills an app whose started foreground service stops before startForeground); the service then enters
     * the foreground and stops itself.
     */
    fun stop(context: Context) {
        synchronized(lock) {
            if (pendingStarts > 0) { stopRequested = true; return }
            _running.value = false
        }
        try { context.stopService(ConnectionKeepAliveService.baseIntent(context)) } catch (_: RuntimeException) {}
    }

    internal fun requestDisconnect() { _disconnectRequests.tryEmit(Unit) }

    /** The service handled a start ([inForeground]: startForeground succeeded); returns true when it must stop now. */
    internal fun startHandled(inForeground: Boolean): Boolean = synchronized(lock) {
        if (inForeground) _running.value = true
        pendingStarts = (pendingStarts - 1).coerceAtLeast(0)
        _starting.value = pendingStarts > 0
        (stopRequested && pendingStarts == 0).also { if (it) { stopRequested = false; _running.value = false } }
    }

    internal fun serviceDestroyed() = synchronized(lock) { _running.value = false }

    @VisibleForTesting
    internal fun reset() = synchronized(lock) {
        pendingStarts = 0; stopRequested = false; _running.value = false; _starting.value = false
    }
}
