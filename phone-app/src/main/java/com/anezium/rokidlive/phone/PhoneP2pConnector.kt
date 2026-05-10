package com.anezium.rokidlive.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.anezium.rokidlive.shared.NetworkAddresses

data class PhoneP2pConnection(
    val groupOwnerAddress: String,
    val phoneLocalAddress: String,
    val phoneIsGroupOwner: Boolean,
    val role: String
) {
    val mediaHost: String = if (phoneIsGroupOwner) phoneLocalAddress else groupOwnerAddress
}

class PhoneP2pConnector(
    context: Context,
    private val targetDeviceName: String?,
    private val targetDeviceAddress: String?,
    private val onStatus: (String) -> Unit,
    private val onConnected: (PhoneP2pConnection) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var running = false
    private var discovering = false
    private var connecting = false
    private var connectionInfoPoll: Runnable? = null
    private var peerPoll: Runnable? = null

    private val timeout = Runnable {
        fail("Wi-Fi Direct timeout")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (enabled && running && !discovering && !connecting) {
                        startDiscovery()
                    } else if (!enabled && running) {
                        onStatus("Wi-Fi Direct disabled on phone")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (discovering) requestPeers()
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
                    if (networkInfo?.isConnected == true) {
                        onStatus("Wi-Fi Direct link up, resolving IP...")
                        requestConnectionInfo()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            fail("WifiP2pManager unavailable on phone")
            return
        }
        registerReceiver()
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
        onStatus("Phone scanning Wi-Fi Direct peers...")
        resetGroupThenDiscover()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        discovering = false
        connecting = false
        mainHandler.removeCallbacks(timeout)
        clearPeerPoll()
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
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenDiscover() {
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mainHandler.postDelayed({ startDiscovery() }, 500L)
            }

            override fun onFailure(reason: Int) {
                mainHandler.postDelayed({ startDiscovery() }, 500L)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!running) return
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        discovering = true
        onStatus("Looking for helper peer: ${targetDeviceName ?: targetDeviceAddress ?: "unknown"}")
        localManager.discoverPeers(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeers()
                schedulePeerPoll()
            }

            override fun onFailure(reason: Int) {
                onStatus("Wi-Fi Direct discovery retry: $reason")
                mainHandler.postDelayed({ startDiscovery() }, RETRY_MS)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestPeers(localChannel, ::handlePeers)
    }

    private fun handlePeers(peers: WifiP2pDeviceList) {
        if (!running || connecting) return
        val devices = peers.deviceList.toList()
        val match = devices.firstOrNull(::isTargetDevice)
        if (match == null) {
            val seen = devices.joinToString { "${it.deviceName} (${it.deviceAddress})" }.ifBlank { "none" }
            onStatus("Helper Wi-Fi Direct peer not visible yet. Seen: $seen")
            return
        }
        clearPeerPoll()
        discovering = false
        connect(match)
    }

    private fun isTargetDevice(device: WifiP2pDevice): Boolean {
        val addressMatches = !targetDeviceAddress.isNullOrBlank() &&
            device.deviceAddress.equals(targetDeviceAddress, ignoreCase = true)
        val nameMatches = !targetDeviceName.isNullOrBlank() &&
            device.deviceName.equals(targetDeviceName, ignoreCase = true)
        val fuzzyNameMatches = !targetDeviceName.isNullOrBlank() &&
            device.deviceName.isNotBlank() &&
            (device.deviceName.contains(targetDeviceName, ignoreCase = true) ||
                targetDeviceName.contains(device.deviceName, ignoreCase = true))
        return addressMatches || nameMatches || fuzzyNameMatches
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: WifiP2pDevice) {
        if (!running) return
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        connecting = true
        onStatus("Joining helper Wi-Fi Direct group: ${device.deviceName}")
        localManager.connect(localChannel, buildConfig(device), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                runCatching { localManager.stopPeerDiscovery(localChannel, null) }
                scheduleConnectionInfoPoll(immediate = false)
            }

            override fun onFailure(reason: Int) {
                connecting = false
                onStatus("Wi-Fi Direct connect retry: $reason")
                mainHandler.postDelayed({ connect(device) }, RETRY_MS)
            }
        })
    }

    private fun buildConfig(device: WifiP2pDevice): WifiP2pConfig {
        return runCatching {
            WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(device.deviceAddress))
                .enablePersistentMode(false)
                .build()
        }.getOrElse {
            WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
        }
    }

    private fun requestConnectionInfo() {
        val localManager = manager ?: return fail("Wi-Fi Direct manager lost")
        val localChannel = channel ?: return fail("Wi-Fi Direct channel lost")
        localManager.requestConnectionInfo(localChannel, ::handleConnectionInfo)
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?) {
        if (!running) return
        if (info?.groupFormed != true) {
            scheduleConnectionInfoPoll(immediate = false)
            return
        }

        val ownerAddress = info.groupOwnerAddress?.hostAddress.orEmpty()
        val localAddress = NetworkAddresses.firstP2pIpv4Address().orEmpty()
        if (ownerAddress.isBlank() || (info.isGroupOwner && localAddress.isBlank())) {
            scheduleConnectionInfoPoll(immediate = false, delayMs = 500L)
            return
        }

        connecting = false
        discovering = false
        clearConnectionInfoPoll()
        mainHandler.removeCallbacks(timeout)
        val role = if (info.isGroupOwner) "phone-go" else "phone-client"
        onStatus("Wi-Fi Direct connected: owner=$ownerAddress local=${localAddress.ifBlank { "-" }} role=$role")
        onConnected(
            PhoneP2pConnection(
                groupOwnerAddress = ownerAddress,
                phoneLocalAddress = localAddress.ifBlank { ownerAddress },
                phoneIsGroupOwner = info.isGroupOwner,
                role = role
            )
        )
    }

    private fun schedulePeerPoll() {
        clearPeerPoll()
        peerPoll = Runnable {
            peerPoll = null
            if (running && discovering) {
                requestPeers()
                schedulePeerPoll()
            }
        }.also { mainHandler.postDelayed(it, PEER_POLL_MS) }
    }

    private fun clearPeerPoll() {
        peerPoll?.let(mainHandler::removeCallbacks)
        peerPoll = null
    }

    private fun scheduleConnectionInfoPoll(immediate: Boolean, delayMs: Long = 1_000L) {
        clearConnectionInfoPoll()
        connectionInfoPoll = Runnable {
            connectionInfoPoll = null
            if (running && connecting) requestConnectionInfo()
        }.also { mainHandler.postDelayed(it, if (immediate) 0L else delayMs) }
    }

    private fun clearConnectionInfoPoll() {
        connectionInfoPoll?.let(mainHandler::removeCallbacks)
        connectionInfoPoll = null
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun fail(message: String) {
        if (!running) return
        stop()
        onFailure(message)
    }

    companion object {
        private const val TIMEOUT_MS = 60_000L
        private const val RETRY_MS = 1_000L
        private const val PEER_POLL_MS = 1_500L
    }
}
