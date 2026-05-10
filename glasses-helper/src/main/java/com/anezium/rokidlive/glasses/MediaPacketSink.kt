package com.anezium.rokidlive.glasses

import com.anezium.rokidlive.shared.MediaPacket

interface MediaPacketSink {
    val endpoint: String

    fun send(packet: MediaPacket)
    fun nextSequence(): Int
    fun close()
}

