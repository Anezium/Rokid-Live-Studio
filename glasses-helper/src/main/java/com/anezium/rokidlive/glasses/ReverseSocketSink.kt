package com.anezium.rokidlive.glasses

import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketCodec
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ReverseSocketSink(
    private val socket: Socket
) : MediaPacketSink {
    private val sequence = AtomicInteger(0)

    override val endpoint: String =
        "${socket.inetAddress.hostAddress}:${socket.port}"

    @Synchronized
    override fun send(packet: MediaPacket) {
        MediaPacketCodec.write(socket.getOutputStream(), packet)
    }

    override fun nextSequence(): Int = sequence.incrementAndGet()

    @Synchronized
    override fun close() {
        runCatching { socket.close() }
    }
}

