package org.autojs.autojs.ui.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.smartfinger.blehidhost.ipc.IBleHidCallback
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs6.R

/**
 * Shared BLE HID connection state machine for drawer and settings UI.
 * zh-CN: 侧边栏与设置页共用的 BLE HID 连接状态机.
 */
class BleConnectionStateHelper(
    private val context: Context,
    private val logTag: String = "BleConnection",
    private val onUiUpdate: () -> Unit,
) {

    var onProgressChanged: ((Boolean) -> Unit)? = null

    private val connectTimeoutMs = 35_000L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var connecting: Boolean = false
        private set

    @Volatile
    private var connectTimedOut = false

    @Volatile
    var lastConnectAttemptAddress: String? = null
        private set

    @Volatile
    private var pendingConnectAddress: String? = null

    @Volatile
    private var disconnectingAddress: String? = null

    @Volatile
    var lastFailedAddress: String? = null
        private set

    val isActive: Boolean
        get() = try {
            BleDevicePreference.getBleService()?.isConnected == true ||
                connected ||
                connecting ||
                pendingConnectAddress != null
        } catch (_: Exception) {
            connected || connecting || pendingConnectAddress != null
        }

    val bleCallback = object : IBleHidCallback.Stub() {
        override fun onConnectionStateChanged(state: Int, macAddress: String?) {
            Log.d(
                logTag,
                "onConnectionStateChanged state=$state mac=$macAddress stored=${Pref.getString(R.string.key_ble_device_address, "")} lastAttempt=$lastConnectAttemptAddress pending=$pendingConnectAddress",
            )
            val isForCurrentAttempt = macAddress == null || macAddress == lastConnectAttemptAddress
            if (!isForCurrentAttempt) {
                if (macAddress != null && macAddress == disconnectingAddress && isTerminalBleState(state)) {
                    disconnectingAddress = null
                    handler.post {
                        pendingConnectAddress?.let { nextAddr ->
                            Log.d(logTag, "disconnect finished for previous device, continue pending connect to $nextAddr")
                            connectToAddress(nextAddr)
                        }
                    }
                } else {
                    Log.d(logTag, "ignore stale state callback for mac=$macAddress")
                }
                return
            }
            connected = state == BluetoothProfile.STATE_CONNECTED
            if (connected || isTerminalBleState(state)) {
                connecting = false
                if (connected) {
                    connectTimedOut = false
                    lastFailedAddress = null
                } else if (isTerminalBleState(state) && disconnectingAddress == null) {
                    lastFailedAddress = lastConnectAttemptAddress
                }
                handler.removeCallbacks(connectTimeoutRunnable)
                setProgress(false)
                if (isTerminalBleState(state)) {
                    disconnectingAddress = null
                    if (pendingConnectAddress == null) {
                        lastConnectAttemptAddress = null
                    }
                }
            }
            handler.post {
                notifyUi()
                if (!connected && isTerminalBleState(state) && pendingConnectAddress != null) {
                    pendingConnectAddress?.let { nextAddr ->
                        Log.d(logTag, "current attempt finished, continue pending connect to $nextAddr")
                        connectToAddress(nextAddr)
                    }
                }
            }
        }

        override fun onError(macAddress: String?, message: String?) {
            Log.d(
                logTag,
                "onError mac=$macAddress message=$message stored=${Pref.getString(R.string.key_ble_device_address, "")} lastAttempt=$lastConnectAttemptAddress pending=$pendingConnectAddress",
            )
            val isForCurrentAttempt = macAddress == null || macAddress == lastConnectAttemptAddress
            if (!isForCurrentAttempt) {
                if (macAddress != null && macAddress == disconnectingAddress) {
                    disconnectingAddress = null
                    handler.post {
                        pendingConnectAddress?.let { nextAddr ->
                            Log.d(logTag, "disconnect error received for previous device, continue pending connect to $nextAddr")
                            connectToAddress(nextAddr)
                        }
                    }
                } else {
                    Log.d(logTag, "ignore stale error callback for mac=$macAddress")
                }
                return
            }
            connected = false
            connecting = false
            connectTimedOut = false
            if (disconnectingAddress == null) {
                lastFailedAddress = lastConnectAttemptAddress
            }
            disconnectingAddress = null
            if (pendingConnectAddress == null) {
                lastConnectAttemptAddress = null
            }
            handler.removeCallbacks(connectTimeoutRunnable)
            handler.post {
                setProgress(false)
                notifyUi()
                pendingConnectAddress?.let { nextAddr ->
                    Log.d(logTag, "error received for current attempt, continue pending connect to $nextAddr")
                    connectToAddress(nextAddr)
                }
            }
        }
    }

    private val connectTimeoutRunnable = Runnable {
        if (!connected && connecting) {
            val stuckAddr = lastConnectAttemptAddress
            Log.d(logTag, "connect timeout, lastAttempt=$stuckAddr, stored=${Pref.getString(R.string.key_ble_device_address, "")}")
            connecting = false
            connectTimedOut = true
            lastFailedAddress = stuckAddr
            runCatching { BleDevicePreference.getBleService()?.disconnect() }
            setProgress(false)
            notifyUi()
        }
    }

    fun syncFromService() {
        connected = try {
            BleDevicePreference.getBleService()?.isConnected ?: false
        } catch (_: Exception) {
            false
        }
        if (connected) {
            connecting = false
            connectTimedOut = false
            lastFailedAddress = null
        }
    }

    fun connectToAddress(addr: String) {
        val service = BleDevicePreference.getBleService() ?: return
        connectTimedOut = false
        connecting = true
        connected = false
        lastFailedAddress = null
        setProgress(true)
        lastConnectAttemptAddress = addr
        disconnectingAddress = null
        pendingConnectAddress = null
        Log.d(logTag, "connectToAddress start addr=$addr, stored=${Pref.getString(R.string.key_ble_device_address, "")}")
        service.connectToDevice(addr)
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(connectTimeoutRunnable, connectTimeoutMs)
        notifyUi()
    }

    fun userConnect(addr: String): Boolean = runCatching {
        val service = BleDevicePreference.getBleService() ?: return@runCatching false
        Log.d(
            logTag,
            "userConnect addr=$addr, stored=${Pref.getString(R.string.key_ble_device_address, "")}, lastAttempt=$lastConnectAttemptAddress, pending=$pendingConnectAddress, disconnecting=$disconnectingAddress, timedOut=$connectTimedOut, connected=$connected, connecting=$connecting",
        )
        val shouldWaitForPreviousAttempt = (disconnectingAddress != null) ||
            ((connected || connecting) && lastConnectAttemptAddress != null && lastConnectAttemptAddress != addr) ||
            (connectTimedOut && lastConnectAttemptAddress != null)
        if (shouldWaitForPreviousAttempt) {
            pendingConnectAddress = addr
            connecting = false
            setProgress(true)
            notifyUi()
            if (disconnectingAddress == null) {
                disconnectingAddress = lastConnectAttemptAddress
                Log.d(logTag, "queue pending connect to $addr and disconnect previous attempt $lastConnectAttemptAddress")
                runCatching { service.disconnect() }
            } else {
                Log.d(logTag, "queue pending connect to $addr while waiting disconnect of $disconnectingAddress")
            }
        } else {
            connectTimedOut = false
            lastFailedAddress = null
            connecting = true
            setProgress(true)
            notifyUi()
            connectToAddress(addr)
        }
        true
    }.getOrDefault(false)

    fun userDisconnect(): Boolean = runCatching {
        val service = BleDevicePreference.getBleService() ?: return@runCatching false
        Log.d(logTag, "userDisconnect, stored=${Pref.getString(R.string.key_ble_device_address, "")}, lastAttempt=$lastConnectAttemptAddress pending=$pendingConnectAddress")
        handler.removeCallbacks(connectTimeoutRunnable)
        connecting = false
        connectTimedOut = false
        lastFailedAddress = null
        pendingConnectAddress = null
        setProgress(false)
        disconnectingAddress = lastConnectAttemptAddress
        service.disconnect()
        notifyUi()
        true
    }.getOrDefault(false)

    fun resolveDeviceName(address: String?): String? {
        if (address.isNullOrEmpty()) return null
        return BluetoothAdapter.getDefaultAdapter()
            ?.bondedDevices
            ?.find { it.address == address }
            ?.name ?: address
    }

    fun buildSubtitle(addr: String?, name: String?): String {
        val displayName = name ?: addr?.let { resolveDeviceName(it) }
        val pendingName = pendingConnectAddress?.let { resolveDeviceName(it) }
        return when {
            disconnectingAddress != null && pendingName != null ->
                context.getString(R.string.text_connecting_to_host, pendingName)
            connecting && displayName != null ->
                context.getString(R.string.text_connecting_to_host, displayName)
            connected && displayName != null ->
                context.getString(R.string.text_connected_to, displayName)
            lastFailedAddress != null && displayName != null && lastFailedAddress == addr ->
                "$displayName | ${context.getString(R.string.text_connection_failed)}"
            displayName != null -> displayName
            else -> context.getString(R.string.text_no_device_selected)
        }
    }

    private fun isTerminalBleState(state: Int): Boolean {
        return state == BluetoothProfile.STATE_DISCONNECTED || state == 4
    }

    private fun setProgress(show: Boolean) {
        onProgressChanged?.invoke(show)
    }

    private fun notifyUi() {
        onUiUpdate()
    }
}
