package com.anezium.rokidlive.phone

import com.anezium.rokidlive.shared.MediaPacketCodec
import com.anezium.rokidlive.shared.MediaPacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class MediaIngressServer(
    private val port: Int,
    private val expectedToken: String,
    private val decoder: VideoPreviewDecoder,
    private val onRunningChanged: (Boolean) -> Unit,
    private val onStats: (frames: Long, bytes: Long, dropped: Long) -> Unit,
    private val onVideoConfig: (ByteArray) -> Unit = {},
    private val onVideoFrame: (payload: ByteArray, timestampUs: Long, keyFrame: Boolean) -> Unit = { _, _, _ -> },
    private val onAudioConfig: (ByteArray) -> Unit = {},
    private val onAudioFrame: (payload: ByteArray, timestampUs: Long) -> Unit = { _, _ -> },
    private val onError: (String, Throwable?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            runCatching {
                ServerSocket(port).use { server ->
                    server.reuseAddress = true
                    serverSocket = server
                    onRunningChanged(true)
                    while (isActive && running.get()) {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        client.keepAlive = true
                        clientSocket?.close()
                        clientSocket = client
                        handleClient(client)
                    }
                }
            }.onFailure {
                if (running.get()) onError("Ingress server failed", it)
            }
            running.set(false)
            onRunningChanged(false)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        job?.cancel()
        clientSocket = null
        serverSocket = null
        onRunningChanged(false)
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val hello = MediaPacketCodec.read(input)
            if (hello.type != MediaPacketType.HELLO) error("First media packet must be HELLO")
            val token = hello.payload.toString(Charsets.UTF_8)
            if (token != expectedToken) error("Media token mismatch")
            while (running.get()) {
                val packet = MediaPacketCodec.read(input)
                when (packet.type) {
                    MediaPacketType.VIDEO_CONFIG -> {
                        decoder.configure(packet.payload)
                        onVideoConfig(packet.payload)
                    }
                    MediaPacketType.VIDEO_FRAME -> {
                        onVideoFrame(packet.payload, packet.timestampUs, packet.isKeyFrame())
                        decoder.queueFrame(packet.payload, packet.timestampUs)
                        frames++
                        bytes += packet.payload.size
                        onStats(frames, bytes, dropped)
                    }
                    MediaPacketType.AUDIO_CONFIG -> onAudioConfig(packet.payload)
                    MediaPacketType.AUDIO_FRAME -> onAudioFrame(packet.payload, packet.timestampUs)
                    MediaPacketType.HEARTBEAT -> Unit
                    MediaPacketType.END -> return
                    MediaPacketType.HELLO -> Unit
                }
            }
        }
    }

    fun recordDroppedFrame() {
        dropped++
        onStats(frames, bytes, dropped)
    }
}
