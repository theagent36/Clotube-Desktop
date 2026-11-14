package com.clotube.desktop.network

import com.clotube.desktop.lifecycle.AppLifecycleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object DesktopNetworkMonitor {
    const val RETRY_INTERVAL_MS = 10_000L
    const val MAX_RECONNECT_ATTEMPTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isOnline = MutableStateFlow(checkInternetReachable())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _showOfflinePopup = MutableStateFlow(false)
    val showOfflinePopup: StateFlow<Boolean> = _showOfflinePopup.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _isCheckingNow = MutableStateFlow(false)
    val isCheckingNow: StateFlow<Boolean> = _isCheckingNow.asStateFlow()

    private var monitorJob: Job? = null
    private var hasShownPopupForCurrentOutage = false

    init {
        start()
    }

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                val reachable = checkInternetReachable()
                if (reachable) {
                    onConnected()
                    val pollIntervalMs = if (AppLifecycleManager.isAppActive) 4_000L else 15_000L
                    delay(pollIntervalMs)
                } else {
                    _isOnline.value = false
                    runOfflineReconnectSequence()
                    // After 5 failed tries, continue passive background checks every 10s
                    // so the app auto-recovers silently as soon as internet comes back.
                    while (isActive && !_isOnline.value) {
                        delay(RETRY_INTERVAL_MS)
                        if (checkInternetReachable()) {
                            onConnected()
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun runOfflineReconnectSequence() {
        if (hasShownPopupForCurrentOutage) return

        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            _reconnectAttempt.value = attempt
            println("[DesktopNetworkMonitor] Internet offline. Retry attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${RETRY_INTERVAL_MS / 1000}s...")
            delay(RETRY_INTERVAL_MS)

            if (checkInternetReachable()) {
                println("[DesktopNetworkMonitor] Internet restored on attempt $attempt/$MAX_RECONNECT_ATTEMPTS.")
                onConnected()
                return
            }
        }

        // All 5 attempts failed
        _reconnectAttempt.value = MAX_RECONNECT_ATTEMPTS
        if (!_isOnline.value && !hasShownPopupForCurrentOutage) {
            hasShownPopupForCurrentOutage = true
            _showOfflinePopup.value = true
            println("[DesktopNetworkMonitor] Still offline after $MAX_RECONNECT_ATTEMPTS attempts. Showing offline popup.")
        }
    }

    private fun onConnected() {
        _isOnline.value = true
        _reconnectAttempt.value = 0
        hasShownPopupForCurrentOutage = false
        _showOfflinePopup.value = false
    }

    fun dismissOfflinePopup() {
        _showOfflinePopup.value = false
    }

    fun retryConnectionNow() {
        if (_isCheckingNow.value) return
        scope.launch {
            _isCheckingNow.value = true
            try {
                val reachable = checkInternetReachable()
                if (reachable) {
                    onConnected()
                } else {
                    _isOnline.value = false
                    _showOfflinePopup.value = false
                    hasShownPopupForCurrentOutage = false
                    runOfflineReconnectSequence()
                }
            } finally {
                _isCheckingNow.value = false
            }
        }
    }

    fun checkInternetReachable(): Boolean {
        return try {
            val hasActiveInterface = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.any { iface ->
                    !iface.isLoopback && iface.isUp && iface.inetAddresses.hasMoreElements()
                } ?: false

            if (!hasActiveInterface) return false

            probeTcpSocket("8.8.8.8", 53, 1500) ||
                probeTcpSocket("1.1.1.1", 53, 1500) ||
                probeTcpSocket("www.googleapis.com", 443, 2000)
        } catch (_: Throwable) {
            false
        }
    }

    private fun probeTcpSocket(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
