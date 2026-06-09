package com.anezium.rokidlive.glasses

import android.util.Log
import com.anezium.rokidlive.shared.ControlMessage
import com.anezium.rokidlive.shared.JsonProtocol
import com.anezium.rokidlive.shared.Protocol
import com.anezium.rokidlive.shared.StatusMessage
import com.anezium.rokidlive.shared.StatusType
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

class CxrBridgeController(
    private val onControlMessage: (ControlMessage) -> Unit,
    private val onBridgeState: (String) -> Unit
) {
    private val bridge = CXRServiceBridge()

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(mac: String?, name: String?, type: Int) {
            onBridgeState("Bridge connected: ${name ?: mac ?: "unknown"}")
        }

        override fun onConnecting(mac: String?, name: String?, type: Int) {
            onBridgeState("Bridge connecting")
        }

        override fun onDisconnected() {
            onBridgeState("Bridge disconnected")
        }

        override fun onARTCStatus(health: Float, reset: Boolean) = Unit
        override fun onAudioNoise(noise: Float) = Unit
        override fun onRokidAccountChanged(account: String?) = Unit
    }

    private val messageCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
            if (name != Protocol.CONTROL_CHANNEL) return
            val raw = args?.readStringPairPayload()
            if (raw.isNullOrBlank()) {
                Log.w(TAG, "Ignoring empty control payload")
                return
            }
            runCatching {
                onControlMessage(JsonProtocol.decodeControl(raw))
            }.onFailure {
                Log.e(TAG, "Failed to parse control payload: $raw", it)
                sendStatus(StatusMessage(StatusType.ERROR, "Bad control payload: ${it.message}"))
            }
        }
    }

    fun start() {
        bridge.setStatusListener(statusListener)
        val result = bridge.subscribe(Protocol.CONTROL_CHANNEL, messageCallback)
        onBridgeState("Subscribed ${Protocol.CONTROL_CHANNEL}: $result")
        sendStatus(StatusMessage(StatusType.READY, "Helper ready"))
    }

    fun sendStatus(message: StatusMessage) {
        val payload = JsonProtocol.encodeStatus(message)
        val caps = Caps().apply {
            write("json")
            write(payload)
        }
        bridge.sendMessage(Protocol.STATUS_CHANNEL, caps)
    }

    private fun Caps.readStringPairPayload(): String? {
        if (size() == 0) return null
        if (size() == 1) return at(0).string
        return at(1).string ?: at(0).string
    }

    companion object {
        private const val TAG = "RLS-CxrBridge"
    }
}
