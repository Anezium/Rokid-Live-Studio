package com.anezium.rokidlive.phone

import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketCodec
import com.anezium.rokidlive.shared.MediaPacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ReverseMediaClient(
    private val hosts: List<String>,
    private val port: Int,
    private val expectedToken: String,
    private val decoder: VideoPreviewDecoder,
    private val onRunningChanged: (Boolean) -> Unit,
    private val onStats: (frames: Long, bytes: Long, dropped: Long) -> Unit,
    private val onVideoConfig: (ByteArray) -> Unit = {},
    private val onVideoFrame: (payload: ByteArray, timestampUs: Long, keyFrame: Boolean) -> Unit = { _, _, _ -> },
    private val onAudioConfig: (ByteArray) -> Unit = {},
    private val onAudioFrame: (payload: ByteArray, timestampUs: Long) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var socket: Socket? = null
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            runCatching {
                val connectedSocket = connectWithRetries()
                socket = connectedSocket
                onRunningChanged(true)
                sendHello(connectedSocket)
                readPackets(connectedSocket)
            }.onFailure {
                if (running.get()) onError("Reverse media failed", it)
            }
            running.set(false)
            onRunningChanged(false)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        job?.cancel()
        socket = null
        onRunningChanged(false)
    }

    fun recordDroppedFrame() {
        dropped++
        onStats(frames, bytes, dropped)
    }

    private suspend fun connectWithRetries(): Socket {
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) {
            for (host in hosts.distinct()) {
                if (!running.get()) error("Reverse media stopped")
                runCatching {
                    val nextSocket = Socket()
                    nextSocket.tcpNoDelay = true
                    nextSocket.keepAlive = true
                    nextSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    onLog("Reverse media connected: $host:$port")
                    return nextSocket
                }.onFailure { throwable ->
                    lastError = throwable
                    onLog("Reverse media retry: $host:$port")
                }
            }
            delay(RETRY_DELAY_MS)
        }
        throw IllegalStateException("No reverse endpoint reachable: ${hosts.joinToString()}:$port", lastError)
    }

    private fun sendHello(connectedSocket: Socket) {
        MediaPacketCodec.write(
            connectedSocket.getOutputStream(),
            MediaPacket(
                type = MediaPacketType.HELLO,
                sequence = 1,
                timestampUs = System.nanoTime() / 1_000L,
                payload = expectedToken.toByteArray(Charsets.UTF_8)
            )
        )
    }

    private fun readPackets(connectedSocket: Socket) {
        connectedSocket.use { client ->
            val input = client.getInputStream()
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

    companion object {
        private const val CONNECT_ATTEMPTS = 12
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val RETRY_DELAY_MS = 500L
    }
}
