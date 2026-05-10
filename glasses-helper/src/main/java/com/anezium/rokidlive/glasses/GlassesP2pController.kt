package com.anezium.rokidlive.glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.rokidlive.shared.NetworkAddresses
import com.anezium.rokidlive.shared.StatusMessage
import com.anezium.rokidlive.shared.StatusType

private const val TAG = "RLS-HelperP2P"
private const val MAX_WIFI_WAIT_ATTEMPTS = 6
private const val WIFI_WAIT_MS = 1_500L
private const val MAX_CREATE_GROUP_ATTEMPTS = 8
private const val CREATE_GROUP_RETRY_MS = 1_500L

class GlassesP2pController(
    context: Context,
    private val onStatus: (StatusMessage) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var running = false
    private var localDeviceName = ""
    private var localDeviceAddress = ""
    private var connectionInfoPoll: Runnable? = null
    private var wifiReadyPoll: Runnable? = null
    private var createGroupRetry: Runnable? = null
    private var wifiWaitAttempts = 0
    private var createGroupAttempts = 0

    fun prepareWifi() {
        ensureWifiReady()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    updateLocalDevice(device)
                    sendP2pStatus("Helper Wi-Fi Direct peer ready")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true || running) {
                        requestConnectionInfo()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running) {
                        if (enabled) {
                            sendP2pStatus("Helper Wi-Fi Direct enabled")
                            resetGroupThenCreate()
                        } else {
                            sendP2pStatus("Helper Wi-Fi Direct disabled")
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGroup() {
        if (running) {
            sendP2pStatus("Helper Wi-Fi Direct group already starting")
            return
        }
        running = true
        wifiWaitAttempts = 0
        createGroupAttempts = 0
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            fail("WifiP2pManager unavailable on glasses")
            return
        }

        registerReceiver()
        requestDeviceInfo()
        sendP2pStatus("Preparing helper Wi-Fi Direct group")
        if (ensureWifiReady()) {
            resetGroupThenCreate()
        } else {
            waitForWifiThenCreate()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        clearWifiReadyPoll()
        clearCreateGroupRetry()
        clearConnectionInfoPoll()
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            runCatching { localManager.removeGroup(localChannel, null) }
        }
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        sendP2pStatus("Helper Wi-Fi Direct stopped")
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenCreate() {
        if (!running) return
        if (!ensureWifiReady()) {
            waitForWifiThenCreate()
            return
        }
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        clearCreateGroupRetry()
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mainHandler.postDelayed({ createGroup() }, 500L)
            }

            override fun onFailure(reason: Int) {
                mainHandler.postDelayed({ createGroup() }, 500L)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!running) return
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        createGroupAttempts += 1
        sendP2pStatus("Creating helper Wi-Fi Direct group ($createGroupAttempts/$MAX_CREATE_GROUP_ATTEMPTS)")
        localManager.createGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                sendP2pStatus("Helper Wi-Fi Direct group requested")
                scheduleConnectionInfoPoll(immediate = true)
            }

            override fun onFailure(reason: Int) {
                val reasonName = reasonName(reason)
                if (shouldRetryCreateGroup(reason)) {
                    sendP2pStatus(
                        "Helper Wi-Fi Direct createGroup busy ($reasonName); retrying"
                    )
                    scheduleCreateGroupRetry()
                    return
                }
                fail("Helper Wi-Fi Direct createGroup failed: $reasonName ($reason)")
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiReady(): Boolean {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return true
        if (wifiManager.isWifiEnabled) return true

        sendP2pStatus("Helper Wi-Fi is disabled; trying to enable it")
        val enabled = runCatching { wifiManager.setWifiEnabled(true) }.getOrDefault(false)
        if (!enabled) {
            sendP2pStatus("Helper Wi-Fi enable request was refused by Android")
        }
        return wifiManager.isWifiEnabled
    }

    private fun waitForWifiThenCreate() {
        if (!running) return
        clearWifiReadyPoll()
        wifiWaitAttempts += 1
        if (wifiWaitAttempts > MAX_WIFI_WAIT_ATTEMPTS) {
            fail("Helper Wi-Fi is disabled; enable Wi-Fi on glasses and retry P2P")
            return
        }
        sendP2pStatus("Waiting for helper Wi-Fi (${wifiWaitAttempts}/$MAX_WIFI_WAIT_ATTEMPTS)")
        wifiReadyPoll = Runnable {
            wifiReadyPoll = null
            if (!running) return@Runnable
            if (ensureWifiReady()) {
                resetGroupThenCreate()
            } else {
                waitForWifiThenCreate()
            }
        }.also { mainHandler.postDelayed(it, WIFI_WAIT_MS) }
    }

    private fun shouldRetryCreateGroup(reason: Int): Boolean {
        val retriable = reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR
        return retriable && createGroupAttempts < MAX_CREATE_GROUP_ATTEMPTS
    }

    private fun scheduleCreateGroupRetry() {
        clearCreateGroupRetry()
        createGroupRetry = Runnable {
            createGroupRetry = null
            if (running) {
                resetGroupThenCreate()
            }
        }.also { mainHandler.postDelayed(it, CREATE_GROUP_RETRY_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun requestDeviceInfo() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        runCatching {
            localManager.requestDeviceInfo(localChannel) { device ->
                updateLocalDevice(device)
                sendP2pStatus("Helper Wi-Fi Direct peer ready")
            }
        }
    }

    private fun requestConnectionInfo() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestConnectionInfo(localChannel, ::handleConnectionInfo)
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (!running) return
        if (info?.groupFormed != true) {
            scheduleConnectionInfoPoll(immediate = false)
            return
        }
        clearConnectionInfoPoll()
        val role = if (info.isGroupOwner) "helper-go" else "helper-client"
        val ownerAddress = info.groupOwnerAddress?.hostAddress
            ?: NetworkAddresses.firstP2pIpv4Address().orEmpty()
        onStatus(
            p2pStatus(
                message = "Helper Wi-Fi Direct group ready",
                connected = true,
                role = role,
                groupOwnerAddress = ownerAddress
            )
        )
    }

    private fun updateLocalDevice(device: WifiP2pDevice?) {
        localDeviceName = device?.deviceName.orEmpty().ifBlank { localDeviceName }
        localDeviceAddress = device?.deviceAddress.orEmpty().ifBlank { localDeviceAddress }
    }

    private fun scheduleConnectionInfoPoll(immediate: Boolean) {
        clearConnectionInfoPoll()
        connectionInfoPoll = Runnable {
            connectionInfoPoll = null
            if (running) {
                requestConnectionInfo()
            }
        }.also { mainHandler.postDelayed(it, if (immediate) 0L else 1_000L) }
    }

    private fun clearConnectionInfoPoll() {
        connectionInfoPoll?.let(mainHandler::removeCallbacks)
        connectionInfoPoll = null
    }

    private fun clearWifiReadyPoll() {
        wifiReadyPoll?.let(mainHandler::removeCallbacks)
        wifiReadyPoll = null
    }

    private fun clearCreateGroupRetry() {
        createGroupRetry?.let(mainHandler::removeCallbacks)
        createGroupRetry = null
    }

    private fun sendP2pStatus(message: String) {
        Log.i(TAG, message)
        onStatus(p2pStatus(message = message, connected = false, role = "", groupOwnerAddress = ""))
    }

    private fun p2pStatus(
        message: String,
        connected: Boolean,
        role: String,
        groupOwnerAddress: String
    ): StatusMessage = StatusMessage(
        type = StatusType.P2P,
        message = message,
        networkInfo = NetworkAddresses.describeIpv4Addresses(),
        p2pDeviceName = localDeviceName,
        p2pDeviceAddress = localDeviceAddress,
        p2pGroupOwnerAddress = groupOwnerAddress,
        p2pRole = role,
        p2pConnected = connected
    )

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun fail(message: String) {
        running = false
        clearWifiReadyPoll()
        clearCreateGroupRetry()
        clearConnectionInfoPoll()
        Log.e(TAG, message)
        onStatus(
            StatusMessage(
                type = StatusType.ERROR,
                message = message,
                networkInfo = NetworkAddresses.describeIpv4Addresses(),
                p2pDeviceName = localDeviceName,
                p2pDeviceAddress = localDeviceAddress
            )
        )
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "UNKNOWN"
    }
}
