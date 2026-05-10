package com.anezium.rokidlive.glasses

import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketCodec
import com.anezium.rokidlive.shared.MediaPacketType
import com.anezium.rokidlive.shared.StartStreamConfig
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class MediaSocketClient(
    private val config: StartStreamConfig
) : MediaPacketSink {
    private val sequence = AtomicInteger(0)
    private var socket: Socket? = null

    override val endpoint: String get() = "${config.host}:${config.port}"

    @Synchronized
    fun connect() {
        close()
        val nextSocket = Socket()
        nextSocket.tcpNoDelay = true
        nextSocket.keepAlive = true
        nextSocket.connect(InetSocketAddress(config.host, config.port), 5_000)
        socket = nextSocket
        send(
            MediaPacket(
                type = MediaPacketType.HELLO,
                sequence = nextSequence(),
                timestampUs = nowUs(),
                payload = config.token.toByteArray(Charsets.UTF_8)
            )
        )
    }

    @Synchronized
    override fun send(packet: MediaPacket) {
        val output = socket?.getOutputStream() ?: error("Media socket is not connected")
        MediaPacketCodec.write(output, packet)
    }

    override fun nextSequence(): Int = sequence.incrementAndGet()

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        fun nowUs(): Long = System.nanoTime() / 1_000L
    }
}
