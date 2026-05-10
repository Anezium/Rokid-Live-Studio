package com.anezium.rokidlive.phone

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.anezium.rokidlive.shared.H264AnnexB
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoPreviewDecoder(
    initialWidth: Int,
    initialHeight: Int,
    private val onDroppedFrame: () -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val lock = Any()
    private val running = AtomicBoolean(true)
    private val frames = LinkedBlockingDeque<Frame>(FRAME_QUEUE_CAPACITY)
    private val worker = Thread(::decodeLoop, "rls-preview-decoder").also { it.start() }
    private var width = initialWidth
    private var height = initialHeight
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var pendingConfig: ByteArray? = null

    fun setFormatHint(nextWidth: Int, nextHeight: Int) {
        synchronized(lock) {
            if (width == nextWidth && height == nextHeight) return
            width = nextWidth
            height = nextHeight
            frames.clear()
            releaseLocked()
            pendingConfig?.let { configureLocked(it) }
        }
    }

    fun setSurface(nextSurface: Surface?) {
        synchronized(lock) {
            surface = nextSurface
            if (nextSurface == null) {
                frames.clear()
                releaseLocked()
            } else {
                pendingConfig?.let { configureLocked(it) }
            }
        }
    }

    fun configure(configPayload: ByteArray) {
        synchronized(lock) {
            pendingConfig = configPayload
            frames.clear()
            configureLocked(configPayload)
        }
    }

    fun queueFrame(payload: ByteArray, timestampUs: Long) {
        if (!running.get()) return
        val frame = Frame(payload, timestampUs)
        if (!frames.offerLast(frame)) {
            frames.pollFirst()
            onDroppedFrame()
            if (!frames.offerLast(frame)) {
                onDroppedFrame()
            }
        }
    }

    fun release() {
        running.set(false)
        worker.interrupt()
        frames.clear()
        synchronized(lock) {
            releaseLocked()
            pendingConfig = null
        }
    }

    private fun decodeLoop() {
        while (running.get()) {
            val frame = runCatching { frames.poll(250, TimeUnit.MILLISECONDS) }
                .getOrNull()
                ?: continue
            decodeFrame(frame)
        }
    }

    private fun decodeFrame(frame: Frame) {
        synchronized(lock) {
            val decoder = codec ?: run {
                onDroppedFrame()
                return
            }
            runCatching {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex < 0) {
                    onDroppedFrame()
                    return
                }
                val input = decoder.getInputBuffer(inputIndex) ?: run {
                    onDroppedFrame()
                    return
                }
                input.clear()
                input.put(frame.payload)
                decoder.queueInputBuffer(inputIndex, 0, frame.payload.size, frame.timestampUs, 0)
                drainOutputLocked(decoder)
            }.onFailure {
                onDroppedFrame()
                onError("Decoder input failed", it)
            }
        }
    }

    private fun configureLocked(configPayload: ByteArray) {
        val targetSurface = surface ?: return
        val sets = H264AnnexB.parameterSetsFromCodecConfig(configPayload)
        if (!sets.isComplete) {
            Log.w(TAG, "Ignoring incomplete H264 config")
            return
        }
        releaseLocked()
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(H264AnnexB.withStartCode(requireNotNull(sets.sps))))
                setByteBuffer("csd-1", ByteBuffer.wrap(H264AnnexB.withStartCode(requireNotNull(sets.pps))))
            }
            val nextCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            nextCodec.configure(format, targetSurface, null, 0)
            nextCodec.start()
            codec = nextCodec
        }.onFailure {
            onError("Decoder configure failed", it)
        }
    }

    private fun drainOutputLocked(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, info.size > 0)
                }
            }
        }
    }

    private fun releaseLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    companion object {
        private const val TAG = "RLS-PreviewDecoder"
        private const val FRAME_QUEUE_CAPACITY = 3
        private const val DEQUEUE_TIMEOUT_US = 2_000L
    }

    private data class Frame(val payload: ByteArray, val timestampUs: Long)
}
