package com.example.advancedtunnel.vpn

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages automatic tunnel reconnection attempts within [serviceScope] using an
 * exponential backoff strategy, updating [TunnelStateHolder] to [TunnelConnectionState.Reconnecting]
 * and invoking the connection establishment flow after progressive delays.
 */
class ReconnectionMonitor(
    private val serviceScope: CoroutineScope,
    private val isReconnectEnabled: () -> Boolean,
    private val isInterfaceDown: () -> Boolean,
    private val isSocketDown: () -> Boolean,
    private val onPrepareReconnect: suspend (reason: String) -> Boolean,
    private val onAttemptConnection: suspend (attempt: Int, maxAttempts: Int) -> Boolean,
    private val onReconnectSucceeded: suspend (attempt: Int) -> Unit,
    private val onReconnectExhausted: suspend (errorMessage: String) -> Unit,
    private val onStatusUpdate: (statusText: String) -> Unit,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val watchdogIntervalMs: Long = DEFAULT_WATCHDOG_INTERVAL_MS
) {

    companion object {
        private const val TAG = "ReconnectionMonitor"

        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_BASE_DELAY_MS = 2000L
        const val DEFAULT_MAX_DELAY_MS = 30000L
        const val DEFAULT_WATCHDOG_INTERVAL_MS = 2500L
    }

    private val isReconnecting = AtomicBoolean(false)
    private val reconnectMutex = Mutex()

    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * Starts the reconnection monitor for a connection loss or error event,
     * immediately transitioning [TunnelStateHolder] to [TunnelConnectionState.Reconnecting]
     * and launching the exponential-backoff reconnection job inside [serviceScope].
     */
    fun start(reason: String, force: Boolean = true) {
        scheduleReconnect(reason = reason, force = force)
    }

    /**
     * Starts a background health watchdog coroutine within [serviceScope] that periodically
     * verifies the VPN tunnel interface and socket state while reconnection is enabled.
     */
    fun startWatchdog() {
        if (!serviceScope.isActive || watchdogJob?.isActive == true) return

        watchdogJob = serviceScope.launch {
            Log.i(TAG, "ReconnectionMonitor watchdog started.")
            while (isActive && serviceScope.isActive && isReconnectEnabled()) {
                delay(watchdogIntervalMs)

                if (!isActive || !serviceScope.isActive || !isReconnectEnabled() || isReconnecting.get()) {
                    continue
                }

                val currentState = TunnelStateHolder.connectionState.value
                if (currentState is TunnelConnectionState.Connecting ||
                    currentState is TunnelConnectionState.Authenticating
                ) {
                    continue
                }

                if (isInterfaceDown()) {
                    Log.w(TAG, "Watchdog detected VPN interface is down.")
                    scheduleReconnect("Tunnel interface down", force = true)
                } else if (isSocketDown()) {
                    Log.w(TAG, "Watchdog detected tunnel socket is disconnected.")
                    scheduleReconnect("Tunnel socket disconnected", force = true)
                }
            }
            Log.i(TAG, "ReconnectionMonitor watchdog exited.")
        }
    }

    /**
     * Handles unexpected I/O exceptions from tunnel streams or keep-alive heartbeats
     * by immediately starting the exponential-backoff reconnection job.
     */
    fun onIoException(reason: String, exception: Throwable? = null) {
        val message = exception?.message?.takeIf { it.isNotBlank() }?.let { "$reason ($it)" } ?: reason
        Log.w(TAG, "I/O exception reported to ReconnectionMonitor: $message", exception)
        start(reason = message, force = true)
    }

    /**
     * Schedules a reconnection sequence within [serviceScope] if not already running.
     */
    fun scheduleReconnect(reason: String, force: Boolean = false) {
        if (!serviceScope.isActive || !isReconnectEnabled()) return

        val currentState = TunnelStateHolder.connectionState.value
        if (!force &&
            currentState is TunnelConnectionState.TunnelEstablished &&
            !isInterfaceDown() &&
            !isSocketDown()
        ) {
            return
        }

        if (isReconnecting.compareAndSet(false, true)) {
            Log.i(TAG, "Starting ReconnectionMonitor in serviceScope: $reason")

            TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
            TunnelStateHolder.setReconnectPhase("Reconnecting: $reason")
            onStatusUpdate("Reconnecting: $reason")

            reconnectJob?.cancel()
            reconnectJob = serviceScope.launch {
                try {
                    reconnectMutex.withLock {
                        if (!isActive || !serviceScope.isActive || !isReconnectEnabled()) {
                            return@withLock
                        }
                        executeReconnectWithBackoff(reason)
                    }
                } catch (cancellation: CancellationException) {
                    Log.i(TAG, "Reconnection coroutine cancelled.")
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in ReconnectionMonitor", e)
                    TunnelStateHolder.setConnectionState(
                        TunnelConnectionState.Error(
                            e.message ?: "Auto-reconnect failed unexpectedly"
                        )
                    )
                } finally {
                    isReconnecting.set(false)
                }
            }
        }
    }

    private suspend fun CoroutineScope.executeReconnectWithBackoff(reason: String) {
        TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
        TunnelStateHolder.setReconnectPhase("Reconnecting: $reason")
        onStatusUpdate("Reconnecting: $reason")

        val canProceed = onPrepareReconnect(reason)
        if (!canProceed) {
            return
        }

        for (attempt in 1..maxAttempts) {
            if (!isActive || !serviceScope.isActive || !isReconnectEnabled()) return

            val backoffDelayMs = calculateBackoffDelay(attempt)
            val delaySeconds = backoffDelayMs / 1000

            val waitingPhase =
                "Reconnecting ($attempt/$maxAttempts) — retry in ${delaySeconds}s"
            TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
            TunnelStateHolder.setReconnectPhase(waitingPhase)
            onStatusUpdate("Reconnecting ($attempt/$maxAttempts) in ${delaySeconds}s")

            Log.i(
                TAG,
                "Progressive backoff delay: waiting ${backoffDelayMs}ms before attempt $attempt/$maxAttempts"
            )
            delay(backoffDelayMs)

            if (!isActive || !serviceScope.isActive || !isReconnectEnabled()) return

            val attemptPhase = "Reconnecting ($attempt/$maxAttempts)..."
            TunnelStateHolder.setConnectionState(TunnelConnectionState.Reconnecting)
            TunnelStateHolder.setReconnectPhase(attemptPhase)
            onStatusUpdate("Reconnecting attempt $attempt/$maxAttempts")

            Log.i(TAG, "Executing reconnect attempt $attempt/$maxAttempts")

            val connected = try {
                onAttemptConnection(attempt, maxAttempts)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt threw exception: ${e.message}", e)
                false
            }

            if (connected && !isInterfaceDown()) {
                TunnelStateHolder.setConnectionState(TunnelConnectionState.TunnelEstablished)
                onReconnectSucceeded(attempt)
                Log.i(TAG, "Reconnection succeeded on attempt $attempt/$maxAttempts.")
                return
            }
        }

        val exhaustedMsg = "Auto-reconnect failed after $maxAttempts attempts ($reason)."
        Log.e(TAG, exhaustedMsg)
        TunnelStateHolder.setConnectionState(TunnelConnectionState.Error(exhaustedMsg))
        onReconnectExhausted(exhaustedMsg)
    }

    /**
     * Calculates progressive exponential backoff delay for a 1-indexed attempt:
     * baseDelayMs * 2^(attempt - 1), capped at maxDelayMs.
     */
    fun calculateBackoffDelay(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val factor = 1L shl (safeAttempt - 1).coerceAtMost(10)
        return (baseDelayMs * factor).coerceAtMost(maxDelayMs)
    }

    fun isCurrentlyReconnecting(): Boolean = isReconnecting.get()

    fun isScopeActive(): Boolean = serviceScope.isActive

    /**
     * Cancels any active watchdog or reconnection jobs and resets reconnection state.
     */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting.set(false)
    }
}
