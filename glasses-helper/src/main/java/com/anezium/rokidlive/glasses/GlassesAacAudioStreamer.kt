package com.anezium.rokidlive.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketType
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class GlassesAacAudioStreamer(
    private val context: Context,
    private val packetSender: () -> MediaPacketSink,
    private val onStatus: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Glasses mic permission is missing", null)
            return
        }
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runAudioLoop, "rls-glasses-aac").also { it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun runAudioLoop() {
        var recorder: AudioRecord? = null
        var codec: MediaCodec? = null
        runCatching {
            val nextRecorder = createRecorder()
            recorder = nextRecorder
            val nextCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = nextCodec
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, OUTPUT_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, STEREO_PCM_BYTES_PER_FRAME)
            }
            nextCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            nextCodec.start()
            sendPacket(MediaPacketType.AUDIO_CONFIG, 0L, audioSpecificConfig())

            val monoPcm = ByteArray(MONO_PCM_BYTES_PER_FRAME)
            val info = MediaCodec.BufferInfo()
            var baseTimestampUs = -1L
            nextRecorder.startRecording()
            onStatus("Glasses mic streaming")

            while (running.get()) {
                val inputIndex = nextCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = nextCodec.getInputBuffer(inputIndex)
                    val read = nextRecorder.read(monoPcm, 0, monoPcm.size)
                    if (input != null && read > 0) {
                        if (baseTimestampUs < 0L) baseTimestampUs = nowUs()
                        input.clear()
                        val stereoBytes = copyMonoToStereo(monoPcm, read, input)
                        val timestampUs = (nowUs() - baseTimestampUs).coerceAtLeast(0L)
                        nextCodec.queueInputBuffer(inputIndex, 0, stereoBytes, timestampUs, 0)
                    } else {
                        nextCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                    }
                }
                drainEncoder(nextCodec, info)
            }
        }.onFailure {
            if (running.get()) onError("Glasses mic failed", it)
        }
        running.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuffer.coerceAtLeast(MONO_PCM_BYTES_PER_FRAME * 4)
        listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC).forEach { source ->
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()
            if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
                return recorder
            }
            runCatching { recorder?.release() }
        }
        error("No usable glasses microphone input")
    }

    private fun drainEncoder(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val csd = codec.outputFormat.getByteBuffer("csd-0")?.copyBytes()
                    if (csd != null) sendPacket(MediaPacketType.AUDIO_CONFIG, 0L, csd)
                }
                else -> if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val payload = output.copyBytes()
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendPacket(MediaPacketType.AUDIO_CONFIG, 0L, payload)
                        } else {
                            sendPacket(MediaPacketType.AUDIO_FRAME, info.presentationTimeUs, payload)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                } else {
                    return
                }
            }
        }
    }

    private fun sendPacket(type: MediaPacketType, timestampUs: Long, payload: ByteArray) {
        runCatching {
            val sink = packetSender()
            sink.send(
                MediaPacket(
                    type = type,
                    sequence = sink.nextSequence(),
                    timestampUs = timestampUs,
                    payload = payload
                )
            )
        }.onFailure {
            Log.w(TAG, "Dropping audio packet", it)
        }
    }

    private fun copyMonoToStereo(source: ByteArray, sourceBytes: Int, target: ByteBuffer): Int {
        val sampleBytes = sourceBytes - (sourceBytes % BYTES_PER_SAMPLE)
        var offset = 0
        while (offset < sampleBytes) {
            val low = source[offset]
            val high = source[offset + 1]
            target.put(low)
            target.put(high)
            target.put(low)
            target.put(high)
            offset += BYTES_PER_SAMPLE
        }
        return sampleBytes * OUTPUT_CHANNELS
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun audioSpecificConfig(): ByteArray {
        val audioObjectType = 2
        val samplingFrequencyIndex = 3
        val channelConfiguration = OUTPUT_CHANNELS
        val value = (audioObjectType shl 11) or (samplingFrequencyIndex shl 7) or (channelConfiguration shl 3)
        return byteArrayOf(((value shr 8) and 0xff).toByte(), (value and 0xff).toByte())
    }

    companion object {
        private const val TAG = "RLS-GlassesAudio"
        private const val SAMPLE_RATE = 48_000
        private const val OUTPUT_CHANNELS = 2
        private const val BITRATE = 96_000
        private const val SAMPLES_PER_FRAME = 1_024
        private const val BYTES_PER_SAMPLE = 2
        private const val MONO_PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
        private const val STEREO_PCM_BYTES_PER_FRAME = MONO_PCM_BYTES_PER_FRAME * OUTPUT_CHANNELS
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        private fun nowUs(): Long = System.nanoTime() / 1_000L
    }
}
