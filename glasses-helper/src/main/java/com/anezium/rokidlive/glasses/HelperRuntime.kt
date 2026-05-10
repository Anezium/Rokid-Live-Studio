package com.anezium.rokidlive.glasses

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.anezium.rokidlive.shared.ControlMessage
import com.anezium.rokidlive.shared.ChatOverlayMessage
import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketCodec
import com.anezium.rokidlive.shared.MediaPacketType
import com.anezium.rokidlive.shared.NetworkAddresses
import com.anezium.rokidlive.shared.Protocol
import com.anezium.rokidlive.shared.StatusMessage
import com.anezium.rokidlive.shared.StatusType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class HelperRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(HelperUiState())
    val state = _state.asStateFlow()

    private val mediaSink = AtomicReference<MediaPacketSink?>(null)
    private var reverseServerSocket: ServerSocket? = null
    private var batteryJob: Job? = null
    private val streamer = CameraH264Streamer(
        context = appContext,
        packetSender = { mediaSink.get() ?: error("Media socket not connected") },
        onStats = { frames, bytes, dropped ->
            _state.value = _state.value.copy(framesSent = frames, bytesSent = bytes, droppedFrames = dropped)
            sendStatus(
                StatusMessage(
                    StatusType.STATS,
                    fps = 0,
                    framesSent = frames,
                    bytesSent = bytes,
                    droppedFrames = dropped,
                    networkInfo = NetworkAddresses.describeIpv4Addresses()
                )
            )
        },
        onError = { message, throwable ->
            _state.value = _state.value.copy(error = throwable?.message ?: message, streamState = "Error")
            sendStatus(StatusMessage(StatusType.ERROR, message, networkInfo = NetworkAddresses.describeIpv4Addresses()))
        }
    )
    private val audioStreamer = GlassesAacAudioStreamer(
        context = appContext,
        packetSender = { mediaSink.get() ?: error("Media socket not connected") },
        onStatus = { message ->
            _state.value = _state.value.copy(streamState = message)
        },
        onError = { message, throwable ->
            _state.value = _state.value.copy(error = throwable?.message ?: message)
            sendStatus(StatusMessage(StatusType.ERROR, message, networkInfo = NetworkAddresses.describeIpv4Addresses()))
        }
    )

    private val bridge = CxrBridgeController(
        onControlMessage = ::handleControlMessage,
        onBridgeState = { state ->
            _state.value = _state.value.copy(bridgeState = state)
        }
    )
    private val p2pController = GlassesP2pController(appContext) { status ->
        _state.value = _state.value.copy(p2pStatus = status.message)
        sendStatus(status)
    }

    fun start() {
        bridge.start()
        p2pController.prepareWifi()
        startBatteryUpdates()
        sendReadyStatus("Helper ready")
    }

    fun stop() {
        batteryJob?.cancel()
        batteryJob = null
        stopStreaming(sendStatus = false)
        p2pController.stop()
    }

    private fun handleControlMessage(message: ControlMessage) {
        _state.value = _state.value.copy(lastCommand = message.type.name)
        when (message) {
            is ControlMessage.Hello -> sendReadyStatus("Helper ready")
            is ControlMessage.StartP2p -> startP2p()
            ControlMessage.StopP2p -> p2pController.stop()
            is ControlMessage.StartStream -> startStreaming(message)
            is ControlMessage.StartReverseStream -> startReverseStreaming(message)
            ControlMessage.StopStream -> stopStreaming(sendStatus = true)
            is ControlMessage.SetBitrate -> streamer.setBitrate(message.videoBitrate)
            is ControlMessage.SetChatMessages -> setChatMessages(message.messages)
            is ControlMessage.SetChatStyle -> setChatStyle(message.fontSizeSp, message.maxMessages)
            ControlMessage.RequestKeyframe -> streamer.requestKeyFrame()
            is ControlMessage.Ping -> sendStatus(StatusMessage(StatusType.PONG, message.nonce))
        }
    }

    private fun setChatMessages(messages: List<ChatOverlayMessage>) {
        val maxMessages = _state.value.chatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        _state.value = _state.value.copy(
            chatMessages = messages.takeLast(maxMessages)
        )
    }

    private fun setChatStyle(fontSizeSp: Int, maxMessages: Int) {
        val normalizedFontSize = fontSizeSp.coerceAtLeast(1)
        val normalizedMaxMessages = maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        _state.value = _state.value.copy(
            chatFontSizeSp = normalizedFontSize,
            chatMaxMessages = normalizedMaxMessages,
            chatMessages = _state.value.chatMessages.takeLast(normalizedMaxMessages)
        )
    }

    private fun startBatteryUpdates() {
        batteryJob?.cancel()
        batteryJob = scope.launch {
            while (true) {
                _state.value = _state.value.copy(batteryPercent = batteryPercent())
                delay(30_000)
            }
        }
    }

    private fun startP2p() {
        scope.launch {
            p2pController.startGroup()
        }
    }

    private fun startStreaming(message: ControlMessage.StartStream) {
        scope.launch {
            runCatching {
                stopStreaming(sendStatus = false)
                val client = MediaSocketClient(message.config)
                client.connect()
                _state.value = _state.value.copy(
                    streamState = "Streaming",
                    phoneEndpoint = client.endpoint,
                    error = ""
                )
                mediaSink.set(client)
                sendStatus(
                    StatusMessage(
                        StatusType.STARTED,
                        "Streaming to ${client.endpoint}",
                        networkInfo = NetworkAddresses.describeIpv4Addresses()
                    )
                )
                streamer.start(message.config)
                audioStreamer.start()
            }.onFailure {
                _state.value = _state.value.copy(streamState = "Error", error = it.message ?: "Start failed")
                sendStatus(
                    StatusMessage(
                        StatusType.ERROR,
                        "Start failed: ${it.message}",
                        networkInfo = NetworkAddresses.describeIpv4Addresses()
                    )
                )
            }
        }
    }

    private fun startReverseStreaming(message: ControlMessage.StartReverseStream) {
        scope.launch {
            runCatching {
                stopStreaming(sendStatus = false)
                val port = message.config.port
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                reverseServerSocket = server
                _state.value = _state.value.copy(
                    streamState = "Reverse waiting",
                    phoneEndpoint = "listen:$port",
                    error = ""
                )
                sendStatus(
                    StatusMessage(
                        StatusType.READY,
                        "Reverse server listening on $port",
                        networkInfo = NetworkAddresses.describeIpv4Addresses(),
                        reversePort = port
                    )
                )
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val hello = MediaPacketCodec.read(socket.getInputStream())
                if (hello.type != MediaPacketType.HELLO) error("Reverse client did not send HELLO")
                val token = hello.payload.toString(Charsets.UTF_8)
                if (token != message.config.token) error("Reverse media token mismatch")
                val sink = ReverseSocketSink(socket)
                mediaSink.set(sink)
                _state.value = _state.value.copy(
                    streamState = "Streaming",
                    phoneEndpoint = sink.endpoint,
                    error = ""
                )
                sendStatus(
                    StatusMessage(
                        StatusType.STARTED,
                        "Reverse streaming to ${sink.endpoint}",
                        networkInfo = NetworkAddresses.describeIpv4Addresses(),
                        reversePort = port
                    )
                )
                streamer.start(message.config.asLoopbackStartConfig())
                audioStreamer.start()
            }.onFailure {
                _state.value = _state.value.copy(streamState = "Error", error = it.message ?: "Reverse start failed")
                sendStatus(
                    StatusMessage(
                        StatusType.ERROR,
                        "Reverse start failed: ${it.message}",
                        networkInfo = NetworkAddresses.describeIpv4Addresses(),
                        reversePort = message.config.port
                    )
                )
            }
        }
    }

    private fun stopStreaming(sendStatus: Boolean) {
        runCatching { audioStreamer.stop() }
        runCatching { streamer.stop() }
        runCatching { reverseServerSocket?.close() }
        reverseServerSocket = null
        mediaSink.getAndSet(null)?.let { sink ->
            runCatching {
                sink.send(
                    MediaPacket(
                        type = MediaPacketType.END,
                        sequence = sink.nextSequence(),
                        timestampUs = MediaSocketClient.nowUs()
                    )
                )
            }
            sink.close()
        }
        _state.value = _state.value.copy(streamState = "Idle", phoneEndpoint = "")
        if (sendStatus) {
            sendStatus(StatusMessage(StatusType.STOPPED, "Stopped", networkInfo = NetworkAddresses.describeIpv4Addresses()))
        }
    }

    private fun sendReadyStatus(message: String) {
        sendStatus(StatusMessage(StatusType.READY, message, networkInfo = NetworkAddresses.describeIpv4Addresses()))
    }

    private fun sendStatus(message: StatusMessage) {
        val battery = batteryPercent()
        _state.value = _state.value.copy(batteryPercent = battery)
        bridge.sendStatus(message.copy(batteryPercent = battery))
    }

    private fun batteryPercent(): Int {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
            }
        }
        val manager = appContext.getSystemService(BatteryManager::class.java)
        return manager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: -1
    }
}
