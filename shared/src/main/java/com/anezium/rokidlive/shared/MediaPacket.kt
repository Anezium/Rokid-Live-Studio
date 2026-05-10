package com.anezium.rokidlive.shared

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaPacketType(val id: Int) {
    HELLO(1),
    VIDEO_CONFIG(2),
    VIDEO_FRAME(3),
    HEARTBEAT(4),
    END(5),
    AUDIO_CONFIG(6),
    AUDIO_FRAME(7);

    companion object {
        fun fromId(id: Int): MediaPacketType =
            entries.firstOrNull { it.id == id } ?: error("Unknown media packet type: $id")
    }
}

object MediaPacketFlags {
    const val KEY_FRAME = 1
}

data class MediaPacket(
    val type: MediaPacketType,
    val sequence: Int,
    val timestampUs: Long,
    val flags: Int = 0,
    val payload: ByteArray = ByteArray(0)
) {
    fun isKeyFrame(): Boolean = flags and MediaPacketFlags.KEY_FRAME != 0
}

object MediaPacketCodec {
    private const val MAGIC = 0x524C5331 // RLS1
    private const val VERSION = 1
    private const val HEADER_SIZE = 24
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    fun write(output: OutputStream, packet: MediaPacket) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(packet.type.id.toByte())
            .putShort(packet.flags.toShort())
            .putInt(packet.sequence)
            .putLong(packet.timestampUs)
            .putInt(packet.payload.size)
            .array()
        output.write(header)
        if (packet.payload.isNotEmpty()) {
            output.write(packet.payload)
        }
        output.flush()
    }

    fun read(input: InputStream): MediaPacket {
        val header = readFully(input, HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        if (magic != MAGIC) error("Invalid media stream magic: 0x${magic.toString(16)}")
        val version = buffer.get().toInt() and 0xff
        if (version != VERSION) error("Unsupported media stream version: $version")
        val type = MediaPacketType.fromId(buffer.get().toInt() and 0xff)
        val flags = buffer.short.toInt() and 0xffff
        val sequence = buffer.int
        val timestampUs = buffer.long
        val payloadSize = buffer.int
        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_BYTES) {
            error("Invalid media packet payload size: $payloadSize")
        }
        return MediaPacket(type, sequence, timestampUs, flags, readFully(input, payloadSize))
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("Stream ended while reading $size bytes")
            offset += read
        }
        return bytes
    }
}
