package com.anezium.rokidlive.shared

object H264AnnexB {
    data class ParameterSets(
        val sps: ByteArray?,
        val pps: ByteArray?
    ) {
        val isComplete: Boolean get() = sps != null && pps != null
    }

    fun parameterSetsFromCodecConfig(config: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitNalUnits(config)) {
            when (nal.firstOrNull()?.toInt()?.and(0x1f)) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        return ParameterSets(sps, pps)
    }

    fun splitNalUnits(annexB: ByteArray): List<ByteArray> {
        val starts = findStartCodes(annexB)
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexedNotNull { index, startCode ->
            val nalStart = startCode.index + startCode.length
            val next = starts.getOrNull(index + 1)?.index ?: annexB.size
            if (nalStart >= next) null else annexB.copyOfRange(nalStart, next)
        }
    }

    fun withStartCode(nal: ByteArray): ByteArray {
        val start = byteArrayOf(0, 0, 0, 1)
        return start + nal
    }

    private data class StartCode(val index: Int, val length: Int)

    private fun findStartCodes(bytes: ByteArray): List<StartCode> {
        val starts = mutableListOf<StartCode>()
        var i = 0
        while (i <= bytes.size - 3) {
            val three = bytes[i] == 0.toByte() &&
                bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 1.toByte()
            val four = i <= bytes.size - 4 &&
                bytes[i] == 0.toByte() &&
                bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 0.toByte() &&
                bytes[i + 3] == 1.toByte()
            if (four) {
                starts += StartCode(i, 4)
                i += 4
            } else if (three) {
                starts += StartCode(i, 3)
                i += 3
            } else {
                i++
            }
        }
        return starts
    }
}
