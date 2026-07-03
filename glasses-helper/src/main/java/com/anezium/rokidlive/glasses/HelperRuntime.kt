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
import com.anezium.rokidlive.shared.StartStreamConfig
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class HelperRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(HelperUiState())
    val state = _state.asStateFlow()

    private val mediaSink = AtomicReference<MediaPacketSink?>(null)
    private val transportLost = AtomicBoolean(true)
    private var reverseServerSocket: ServerSocket? = null
    private var batteryJob: Job? = null
    private var cameraRetryJob: Job? = null
    private var cameraRetryAttempts = 0
    private var activeVideoConfig: StartStreamConfig? = null
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
        onRunning = {
            handleCameraRunning()
        },
        onError = { message, throwable ->
            if (message.isRecoverableCameraError()) {
                handleCameraInterrupted(message, throwable)
            } else {
                _state.value = _state.value.copy(error = throwable?.message ?: message, streamState = "Error")
                sendStatus(StatusMessage(StatusType.ERROR, message, networkInfo = NetworkAddresses.describeIpv4Addresses()))
            }
        },
        onTransportLost = { message, throwable ->
            handleTransportLost(message, throwable)
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
        },
        onTransportLost = { message, throwable ->
            handleTransportLost(message, throwable)
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
            is ControlMessage.SetChatStyle -> setChatStyle(
                message.fontSizeSp,
                message.maxMessages,
                message.bottomOffsetDp
            )
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

    private fun setChatStyle(fontSizeSp: Int, maxMessages: Int, bottomOffsetDp: Int) {
        val normalizedFontSize = fontSizeSp.coerceAtLeast(1)
        val normalizedMaxMessages = maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        val normalizedBottomOffset = bottomOffsetDp.coerceIn(
            Protocol.MIN_CHAT_BOTTOM_OFFSET_DP,
            Protocol.MAX_CHAT_BOTTOM_OFFSET_DP
        )
        _state.value = _state.value.copy(
            chatFontSizeSp = normalizedFontSize,
            chatBottomOffsetDp = normalizedBottomOffset,
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
                transportLost.set(false)
                activeVideoConfig = message.config
                cameraRetryAttempts = 0
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
                transportLost.set(false)
                activeVideoConfig = message.config.asLoopbackStartConfig()
                cameraRetryAttempts = 0
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
                streamer.start(activeVideoConfig ?: message.config.asLoopbackStartConfig())
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
        transportLost.set(true)
        cameraRetryJob?.cancel()
        cameraRetryJob = null
        cameraRetryAttempts = 0
        activeVideoConfig = null
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

    private fun handleTransportLost(message: String, throwable: Throwable?) {
        if (!transportLost.compareAndSet(false, true)) return
        scope.launch {
            stopStreaming(sendStatus = false)
            val detail = throwable?.message?.takeIf { it.isNotBlank() }
            val statusMessage = if (detail != null) "$message: $detail" else message
            _state.value = _state.value.copy(streamState = "Error", error = statusMessage)
            sendStatus(
                StatusMessage(
                    StatusType.ERROR,
                    statusMessage,
                    networkInfo = NetworkAddresses.describeIpv4Addresses()
                )
            )
        }
    }

    private fun handleCameraRunning() {
        cameraRetryAttempts = 0
        cameraRetryJob?.cancel()
        cameraRetryJob = null
        _state.value = _state.value.copy(streamState = "Streaming", error = "")
        sendStatus(
            StatusMessage(
                StatusType.STARTED,
                "Camera streaming",
                networkInfo = NetworkAddresses.describeIpv4Addresses()
            )
        )
    }

    private fun handleCameraInterrupted(message: String, throwable: Throwable?) {
        if (transportLost.get()) return
        val config = activeVideoConfig
        if (config == null || mediaSink.get() == null) {
            val detail = throwable?.message?.takeIf { it.isNotBlank() }
            val statusMessage = if (detail != null) "$message: $detail" else message
            _state.value = _state.value.copy(streamState = "Error", error = statusMessage)
            sendStatus(StatusMessage(StatusType.ERROR, statusMessage, networkInfo = NetworkAddresses.describeIpv4Addresses()))
            return
        }
        if (cameraRetryJob?.isActive == true) return
        val nextAttempt = cameraRetryAttempts + 1
        if (nextAttempt > CAMERA_RETRY_MAX_ATTEMPTS) {
            val statusMessage = "Camera recovery failed after $CAMERA_RETRY_MAX_ATTEMPTS attempts. Rokid system camera may still be active."
            _state.value = _state.value.copy(streamState = "Error", error = statusMessage)
            sendStatus(StatusMessage(StatusType.ERROR, statusMessage, networkInfo = NetworkAddresses.describeIpv4Addresses()))
            return
        }
        cameraRetryAttempts = nextAttempt
        val reason = throwable?.message?.takeIf { it.isNotBlank() } ?: message
        val waitingMessage = "Camera interrupted: retry $nextAttempt/$CAMERA_RETRY_MAX_ATTEMPTS in ${CAMERA_RETRY_DELAY_MS / 1000}s ($reason)"
        _state.value = _state.value.copy(streamState = "Camera retry $nextAttempt/$CAMERA_RETRY_MAX_ATTEMPTS", error = waitingMessage)
        sendStatus(StatusMessage(StatusType.STATS, waitingMessage, networkInfo = NetworkAddresses.describeIpv4Addresses()))
        cameraRetryJob = scope.launch {
            delay(CAMERA_RETRY_DELAY_MS)
            if (transportLost.get() || mediaSink.get() == null || activeVideoConfig == null) return@launch
            val retryMessage = "Retrying camera $nextAttempt/$CAMERA_RETRY_MAX_ATTEMPTS"
            _state.value = _state.value.copy(streamState = retryMessage, error = "")
            sendStatus(StatusMessage(StatusType.STATS, retryMessage, networkInfo = NetworkAddresses.describeIpv4Addresses()))
            runCatching {
                streamer.start(config)
            }.onFailure {
                cameraRetryJob = null
                handleCameraInterrupted("Camera retry failed", it)
            }
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

    private fun String.isRecoverableCameraError(): Boolean =
        startsWith("Camera disconnected") ||
            startsWith("Camera open error") ||
            startsWith("Camera capture session configure failed")

    companion object {
        private const val CAMERA_RETRY_MAX_ATTEMPTS = 5
        private const val CAMERA_RETRY_DELAY_MS = 10_000L
    }
}
