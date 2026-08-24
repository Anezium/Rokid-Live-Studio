package com.anezium.rokidlive.phone

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.anezium.rokidlive.shared.H264AnnexB
import com.anezium.rokidlive.shared.VideoPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min
import kotlin.random.Random

data class RtmpDiagnostics(
    val endpoint: String = "",
    val network: String = "",
    val videoBitrate: Int = 0,
    val reconnects: Long = 0,
    val droppedVideoFrames: Long = 0
)

class YoutubeRtmpPublisher(
    private val platformName: String = "YouTube",
    private val onStatus: (String) -> Unit,
    private val onLiveChanged: (Boolean) -> Unit,
    private val onStats: (bytes: Long) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val networkBindingProvider: () -> YoutubeNetworkBinding = { YoutubeNetworkBinding(null, "default network") },
    private val onReady: () -> Unit = {},
    private val onVideoBackpressure: () -> Unit = {},
    private val onDiagnostics: (RtmpDiagnostics) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<OutboundMessage>(QUEUE_CAPACITY)
    private var job: Job? = null
    private var connection: RtmpConnection? = null
    private var silentAudio: SilentAacSource? = null
    private var lastVideoConfig: ByteArray? = null
    private var lastAudioConfig: ByteArray? = null
    private var baseVideoTimestampUs: Long = -1L
    private var baseAudioTimestampUs: Long = -1L
    private val externalAudioActive = AtomicBoolean(false)
    private val videoResyncRequired = AtomicBoolean(false)
    private val videoConfigRequired = AtomicBoolean(true)
    private val droppedVideoFrames = AtomicLong(0L)
    private val reconnects = AtomicLong(0L)
    private var activeEndpoint = ""
    private var activeNetwork = ""
    private var activeVideoBitrate = 0
    private var bytesSent = 0L

    fun start(
        streamKey: String,
        preset: VideoPreset,
        serverUrl: String = DEFAULT_RTMPS_SERVER,
        fallbackServerUrls: List<String> = emptyList(),
        videoBitrate: Int = preset.youtubeVideoBitrate.takeIf { it > 0 } ?: preset.videoBitrate
    ) {
        val key = streamKey.trim()
        if (key.isBlank()) {
            onError("$platformName stream key missing", null)
            return
        }
        val endpoints = rtmpEndpoints(serverUrl, fallbackServerUrls)
        if (endpoints.isEmpty()) {
            onError("$platformName RTMP server missing", null)
            return
        }
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        baseVideoTimestampUs = -1L
        baseAudioTimestampUs = -1L
        activeEndpoint = ""
        activeNetwork = ""
        activeVideoBitrate = videoBitrate
        droppedVideoFrames.set(0L)
        reconnects.set(0L)
        videoResyncRequired.set(false)
        videoConfigRequired.set(true)
        bytesSent = 0L
        emitDiagnostics()
        onLiveChanged(true)
        status("Waiting for Rokid video keyframe...")
        job = scope.launch {
            try {
                publishWithReconnect(key, preset, endpoints)
            } finally {
                finishStopped()
                job = null
            }
        }
    }

    fun stop() {
        finishStopped()
        job?.cancel()
        job = null
    }

    private suspend fun publishWithReconnect(
        streamKey: String,
        preset: VideoPreset,
        endpoints: List<String>
    ) {
        var endpointIndex = 0
        var reconnectAttempt = 0
        var connectedAtMs = 0L
        while (running.get()) {
            runCatching {
                connectedAtMs = 0L
                val initialVideo = waitForInitialVideo()
                val networkBinding = runCatching { networkBindingProvider() }
                    .getOrElse { YoutubeNetworkBinding(null, "default network") }
                val endpoint = endpoints[endpointIndex % endpoints.size]
                activeEndpoint = endpoint.rtmpEndpointLabel()
                activeNetwork = networkBinding.label
                emitDiagnostics()
                status("Connecting $platformName RTMP to $activeEndpoint via ${networkBinding.label}...")
                val nextConnection = RtmpConnection(
                    serverUrl = endpoint,
                    streamKey = streamKey,
                    platformName = platformName,
                    onStatus = ::status,
                    socketFactory = networkBinding.socketFactory
                )
                connection = nextConnection
                nextConnection.connectAndPublish()
                connectedAtMs = System.currentTimeMillis()
                status("$platformName RTMP connected")
                val configBytes = nextConnection.sendAvcConfig(initialVideo.config)
                if (configBytes <= 0) error("Rokid H.264 config incomplete")
                recordBytes(configBytes)
                videoConfigRequired.set(false)
                val frameBytes = nextConnection.sendAvcFrame(initialVideo.keyFrame.payload, 0, true)
                if (frameBytes <= 0) error("Rokid keyframe was empty")
                recordBytes(frameBytes)
                status("$platformName ready: ${preset.label}")
                onReady()
                resetQueuedAudioForLiveStart()
                val audioConfig = lastAudioConfig
                if (externalAudioActive.get() && audioConfig != null) {
                    recordBytes(nextConnection.sendAacConfig(audioConfig))
                    status("Using Rokid glasses mic")
                } else {
                    startSilentAudio()
                }
                publishLoop(nextConnection, preset, timestampOffsetMs = initialVideo.keyFrame.timestampMs)
                return
            }.onFailure { throwable ->
                if (throwable is CancellationException || !running.get()) return
                stopSilentAudio()
                runCatching { connection?.close() }
                connection = null
                val connectedForMs = if (connectedAtMs > 0L) System.currentTimeMillis() - connectedAtMs else 0L
                if (connectedForMs >= RECONNECT_STABLE_RESET_MS) reconnectAttempt = 0
                if (!throwable.isRecoverableRtmpFailure() || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                    reportError("$platformName live failed: ${throwable.rtmpUserMessage()}", throwable)
                    return
                }
                reconnectAttempt++
                reconnects.incrementAndGet()
                endpointIndex = (endpointIndex + 1) % endpoints.size
                resetForReconnect()
                val nextEndpoint = endpoints[endpointIndex % endpoints.size].rtmpEndpointLabel()
                status(
                    "$platformName connection lost: ${throwable.rtmpUserMessage()}. " +
                        "Reconnecting $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS to $nextEndpoint..."
                )
                emitDiagnostics()
                delay(RECONNECT_DELAY_MS * reconnectAttempt)
            }
        }
    }

    private fun finishStopped() {
        if (!running.getAndSet(false)) return
        stopSilentAudio()
        queue.offer(OutboundMessage.Stop)
        runCatching { connection?.close() }
        connection = null
        onLiveChanged(false)
        status("$platformName stopped")
        activeEndpoint = ""
        activeNetwork = ""
        activeVideoBitrate = 0
        emitDiagnostics()
    }

    fun clearVideoState() {
        lastVideoConfig = null
        baseVideoTimestampUs = -1L
        videoResyncRequired.set(false)
        videoConfigRequired.set(true)
        queue.removeIf { it is OutboundMessage.VideoConfig || it is OutboundMessage.VideoFrame }
    }

    fun configureVideo(configPayload: ByteArray) {
        val previous = lastVideoConfig
        val unchanged = previous?.contentEquals(configPayload) == true
        if (unchanged && !videoConfigRequired.get()) return
        val storedConfig = if (unchanged) requireNotNull(previous) else configPayload.copyOf()
        lastVideoConfig = storedConfig
        videoConfigRequired.set(true)
        if (running.get() && !videoResyncRequired.get()) {
            if (offerVideoConfig(storedConfig, allowResync = false)) {
                videoConfigRequired.set(false)
            } else {
                requestVideoResync("$platformName queue is full; waiting for a clean keyframe")
            }
        }
    }

    fun publishVideoFrame(payload: ByteArray, timestampUs: Long, keyFrame: Boolean) {
        if (!running.get()) return
        if (baseVideoTimestampUs < 0L) {
            baseVideoTimestampUs = timestampUs
        }
        val timestampMs = ((timestampUs - baseVideoTimestampUs).coerceAtLeast(0L) / 1_000L).toInt()
        if (videoResyncRequired.get() && !keyFrame) {
            droppedVideoFrames.incrementAndGet()
            emitDiagnostics()
            return
        }
        if (keyFrame) {
            val needsConfig = videoResyncRequired.getAndSet(false) || videoConfigRequired.get()
            if (needsConfig && lastVideoConfig != null) {
                val config = requireNotNull(lastVideoConfig)
                if (!offerVideoConfig(config, allowResync = true)) {
                    requestVideoResync("$platformName queue is full; waiting for a clean keyframe")
                    return
                }
                videoConfigRequired.set(false)
            }
        }
        if (!offerVideoFrame(OutboundMessage.VideoFrame(payload, timestampMs, keyFrame))) {
            requestVideoResync("$platformName queue is full; waiting for a clean keyframe")
        }
    }

    fun configureAudio(configPayload: ByteArray) {
        lastAudioConfig = configPayload.copyOf()
        externalAudioActive.set(true)
        stopSilentAudio()
        if (running.get()) {
            offerAudio(OutboundMessage.AacConfig(requireNotNull(lastAudioConfig)))
        }
    }

    fun publishAudioFrame(payload: ByteArray, timestampUs: Long) {
        externalAudioActive.set(true)
        stopSilentAudio()
        if (!running.get()) return
        if (baseAudioTimestampUs < 0L) {
            baseAudioTimestampUs = timestampUs
        }
        val timestampMs = ((timestampUs - baseAudioTimestampUs).coerceAtLeast(0L) / 1_000L).toInt()
        offerAudio(OutboundMessage.AacFrame(payload, timestampMs))
    }

    private fun waitForInitialVideo(): InitialVideo {
        var config = lastVideoConfig
        while (running.get()) {
            when (val message = queue.poll(500, TimeUnit.MILLISECONDS)) {
                null -> Unit
                is OutboundMessage.VideoConfig -> {
                    config = message.payload
                    lastVideoConfig = message.payload
                    status("Rokid video config ready")
                }
                is OutboundMessage.VideoFrame -> {
                    if (message.keyFrame) {
                        val readyConfig = config ?: lastVideoConfig
                        if (readyConfig != null && H264AnnexB.parameterSetsFromCodecConfig(readyConfig).isComplete) {
                            return InitialVideo(readyConfig, message)
                        }
                        status("Waiting for Rokid H.264 config...")
                    }
                }
                is OutboundMessage.AacConfig -> {
                    lastAudioConfig = message.payload
                    externalAudioActive.set(true)
                }
                is OutboundMessage.AacFrame -> externalAudioActive.set(true)
                OutboundMessage.Stop -> error("$platformName start cancelled")
            }
        }
        error("$platformName start cancelled")
    }

    private fun resetQueuedAudioForLiveStart() {
        baseAudioTimestampUs = -1L
        queue.removeIf { it is OutboundMessage.AacConfig || it is OutboundMessage.AacFrame }
    }

    private fun startSilentAudio() {
        if (silentAudio != null) return
        silentAudio = SilentAacSource(
            onConfig = { payload -> offerAudio(OutboundMessage.AacConfig(payload)) },
            onFrame = { payload, timestampMs -> offerAudio(OutboundMessage.AacFrame(payload, timestampMs)) },
            onError = { message, throwable -> reportError(message, throwable) }
        ).also { it.start() }
    }

    private fun stopSilentAudio() {
        silentAudio?.stop()
        silentAudio = null
    }

    private fun publishLoop(connection: RtmpConnection, preset: VideoPreset, timestampOffsetMs: Int) {
        while (running.get()) {
            when (val message = queue.poll(500, TimeUnit.MILLISECONDS)) {
                null -> Unit
                is OutboundMessage.AacConfig -> {
                    val bytes = connection.sendAacConfig(message.payload)
                    recordBytes(bytes)
                }
                is OutboundMessage.AacFrame -> {
                    val bytes = connection.sendAacFrame(message.payload, message.timestampMs)
                    recordBytes(bytes)
                }
                is OutboundMessage.VideoConfig -> {
                    val bytes = connection.sendAvcConfig(message.payload)
                    recordBytes(bytes)
                    status("$platformName ready: ${preset.label}")
                }
                is OutboundMessage.VideoFrame -> {
                    val timestampMs = (message.timestampMs - timestampOffsetMs).coerceAtLeast(0)
                    val bytes = connection.sendAvcFrame(message.payload, timestampMs, message.keyFrame)
                    if (bytes > 0) recordBytes(bytes)
                }
                OutboundMessage.Stop -> return
            }
        }
    }

    private fun offerVideoConfig(payload: ByteArray, allowResync: Boolean): Boolean {
        val message = OutboundMessage.VideoConfig(payload)
        if (queue.offer(message)) return true
        if (!allowResync) return false
        clearQueuedMediaForVideoResync()
        return queue.offer(message)
    }

    private fun offerVideoFrame(message: OutboundMessage.VideoFrame): Boolean {
        if (queue.offer(message)) return true
        if (!message.keyFrame) return false
        clearQueuedMediaForVideoResync()
        return queue.offer(message)
    }

    private fun offerAudio(message: OutboundMessage) {
        if (queue.offer(message)) return
        queue.removeIf { it is OutboundMessage.AacFrame }
        queue.offer(message)
    }

    private fun requestVideoResync(message: String) {
        clearQueuedMediaForVideoResync()
        if (videoResyncRequired.compareAndSet(false, true)) {
            status(message)
        }
        onVideoBackpressure()
    }

    private fun clearQueuedMediaForVideoResync() {
        var dropped = 0L
        queue.removeIf {
            val remove =
                it is OutboundMessage.VideoConfig ||
                it is OutboundMessage.VideoFrame ||
                it is OutboundMessage.AacFrame
            if (remove && it is OutboundMessage.VideoFrame) dropped++
            remove
        }
        if (dropped > 0L) {
            droppedVideoFrames.addAndGet(dropped)
            emitDiagnostics()
        }
    }

    private fun recordBytes(bytes: Int) {
        bytesSent += bytes
        onStats(bytesSent)
    }

    private fun status(message: String) {
        Log.i(TAG, message)
        onStatus(message)
    }

    private fun reportError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
        onError(message, throwable)
    }

    private fun resetForReconnect() {
        baseVideoTimestampUs = -1L
        baseAudioTimestampUs = -1L
        videoConfigRequired.set(true)
        videoResyncRequired.set(true)
        clearQueuedMediaForVideoResync()
        onVideoBackpressure()
    }

    private fun emitDiagnostics() {
        onDiagnostics(
            RtmpDiagnostics(
                endpoint = activeEndpoint,
                network = activeNetwork,
                videoBitrate = activeVideoBitrate,
                reconnects = reconnects.get(),
                droppedVideoFrames = droppedVideoFrames.get()
            )
        )
    }

    private fun rtmpEndpoints(primary: String, fallbacks: List<String>): List<String> =
        (listOf(primary) + fallbacks)
            .map { it.trim().removeStreamKeyPlaceholder().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.removeStreamKeyPlaceholder(): String =
        replace("/{stream_key}", "")
            .replace("{stream_key}", "")

    private fun String.rtmpEndpointLabel(): String =
        runCatching {
            val uri = URI(this)
            val host = uri.host ?: return@runCatching this
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.path.trimEnd('/').ifBlank { "/" }
            "${uri.scheme}://$host$port$path"
        }.getOrDefault(this)

    private fun Throwable.isRecoverableRtmpFailure(): Boolean =
        this is IOException ||
            this is SocketException ||
            this is SocketTimeoutException ||
            this is SSLException ||
            this is UnknownHostException ||
            message.orEmpty().contains("Broken pipe", ignoreCase = true) ||
            message.orEmpty().contains("socket is closed", ignoreCase = true)

    private fun Throwable.rtmpUserMessage(): String {
        val detail = message.orEmpty()
        return when {
            detail.contains("Broken pipe", ignoreCase = true) ->
                "network socket closed while sending media"
            detail.contains("socket is closed", ignoreCase = true) ->
                "RTMP socket closed"
            this is SocketTimeoutException ->
                "RTMP server timed out"
            this is UnknownHostException ->
                "RTMP host not reachable"
            this is SSLException ->
                "secure RTMP handshake failed"
            this is SocketException ->
                "network socket failed${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
            else ->
                detail.ifBlank { this::class.java.simpleName }
        }
    }

    private data class InitialVideo(
        val config: ByteArray,
        val keyFrame: OutboundMessage.VideoFrame
    )

    private sealed interface OutboundMessage {
        data class VideoConfig(val payload: ByteArray) : OutboundMessage
        data class VideoFrame(val payload: ByteArray, val timestampMs: Int, val keyFrame: Boolean) : OutboundMessage
        data class AacConfig(val payload: ByteArray) : OutboundMessage
        data class AacFrame(val payload: ByteArray, val timestampMs: Int) : OutboundMessage
        data object Stop : OutboundMessage
    }

    companion object {
        private const val TAG = "RLS-RTMP"
        private const val QUEUE_CAPACITY = 600
        private const val DEFAULT_RTMPS_SERVER = "rtmps://a.rtmps.youtube.com/live2"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val RECONNECT_STABLE_RESET_MS = 30_000L
    }

    private class SilentAacSource(
        private val onConfig: (ByteArray) -> Unit,
        private val onFrame: (payload: ByteArray, timestampMs: Int) -> Unit,
        private val onError: (String, Throwable?) -> Unit
    ) {
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null

        fun start() {
            if (!running.compareAndSet(false, true)) return
            thread = Thread(::runEncoder, "rls-silent-aac").also { it.start() }
        }

        fun stop() {
            running.set(false)
            thread?.interrupt()
            thread = null
        }

        private fun runEncoder() {
            var codec: MediaCodec? = null
            runCatching {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES_PER_FRAME)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                onConfig(audioSpecificConfig())
                var ptsUs = 0L
                while (running.get()) {
                    feedSilence(codec, ptsUs)
                    drain(codec, ptsUs)
                    ptsUs += FRAME_DURATION_US
                    Thread.sleep(FRAME_DURATION_US / 1_000L)
                }
            }.onFailure {
                if (running.get()) onError("Silent AAC failed", it)
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }

        private fun feedSilence(codec: MediaCodec?, ptsUs: Long) {
            val localCodec = codec ?: return
            val inputIndex = localCodec.dequeueInputBuffer(5_000)
            if (inputIndex < 0) return
            val input = localCodec.getInputBuffer(inputIndex) ?: return
            input.clear()
            repeat(PCM_BYTES_PER_FRAME) { input.put(0.toByte()) }
            localCodec.queueInputBuffer(inputIndex, 0, PCM_BYTES_PER_FRAME, ptsUs, 0)
        }

        private fun drain(codec: MediaCodec?, fallbackPtsUs: Long) {
            val localCodec = codec ?: return
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outputIndex = localCodec.dequeueOutputBuffer(info, 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val csd = localCodec.outputFormat.getByteBuffer("csd-0")?.copyBytes()
                        if (csd != null) onConfig(csd)
                    }
                    outputIndex >= 0 -> {
                        val output = localCodec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            val timestampUs = if (info.presentationTimeUs >= 0) info.presentationTimeUs else fallbackPtsUs
                            onFrame(bytes, (timestampUs / 1_000L).toInt())
                        }
                        localCodec.releaseOutputBuffer(outputIndex, false)
                    }
                    else -> return
                }
            }
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
            val channelConfiguration = CHANNELS
            val value = (audioObjectType shl 11) or (samplingFrequencyIndex shl 7) or (channelConfiguration shl 3)
            return byteArrayOf(((value shr 8) and 0xff).toByte(), (value and 0xff).toByte())
        }

        companion object {
            private const val SAMPLE_RATE = 48_000
            private const val CHANNELS = 2
            private const val BITRATE = 96_000
            private const val SAMPLES_PER_FRAME = 1_024
            private const val BYTES_PER_SAMPLE = 2
            private const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * CHANNELS * BYTES_PER_SAMPLE
            private const val FRAME_DURATION_US = SAMPLES_PER_FRAME * 1_000_000L / SAMPLE_RATE
        }
    }

    private class RtmpConnection(
        private val serverUrl: String,
        private val streamKey: String,
        private val platformName: String,
        private val onStatus: (String) -> Unit,
        private val socketFactory: SocketFactory?
    ) {
        private val outputLock = Any()
        private val chunks = mutableMapOf<Int, ChunkState>()
        private var socket: Socket? = null
        private var input: DataInputStream? = null
        private var output: OutputStream? = null
        private var publishStreamId = 1
        @Volatile private var inboundChunkSize = 128
        private var outboundChunkSize = 128
        @Volatile private var backgroundReaderRunning = false

        fun connectAndPublish() {
            val uri = URI(serverUrl)
            val scheme = uri.scheme.lowercase()
            val host = uri.host ?: error("Invalid $platformName RTMP host")
            val port = if (uri.port > 0) uri.port else if (scheme == "rtmps") 443 else 1935
            val app = uri.path.trim('/').ifBlank { "live2" }
            val tcUrl = "$scheme://$host/$app"
            socket = openSocket(scheme, host, port).apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = COMMAND_TIMEOUT_MS
            }
            input = DataInputStream(socket!!.getInputStream())
            output = socket!!.getOutputStream()
            handshake()
            sendConnect(app, tcUrl)
            waitForCommandResult(transactionId = 1)
            sendSetChunkSize(4096)
            sendReleaseStream()
            sendFCPublish()
            sendCreateStream()
            publishStreamId = waitForCreateStreamResult(transactionId = 2)
            sendPublish()
            waitForPublishStatus()
            socket?.soTimeout = 0
            startBackgroundReader()
        }

        private fun openSocket(scheme: String, host: String, port: Int): Socket {
            // A Network-bound factory can fail at createSocket() with EPERM when its
            // Network handle has gone stale; fall back to the default network rather
            // than failing every reconnect attempt with the same dead binding.
            val rawSocket = socketFactory?.let { factory ->
                runCatching { factory.createSocket() }.getOrElse { throwable ->
                    if (throwable !is SocketException) throw throwable
                    Log.i(TAG, "$platformName network binding unavailable, using default network")
                    null
                }
            } ?: Socket()
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.sendBufferSize = SOCKET_SEND_BUFFER_BYTES
            rawSocket.receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
            if (scheme != "rtmps") return rawSocket

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            return (sslFactory.createSocket(rawSocket, host, port, true) as Socket).apply {
                tcpNoDelay = true
                keepAlive = true
                sendBufferSize = SOCKET_SEND_BUFFER_BYTES
                receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
                (this as? SSLSocket)?.startHandshake()
            }
        }

        fun sendAvcConfig(configPayload: ByteArray): Int {
            val sets = H264AnnexB.parameterSetsFromCodecConfig(configPayload)
            if (!sets.isComplete) return 0
            val sps = requireNotNull(sets.sps)
            val pps = requireNotNull(sets.pps)
            val avcConfig = ByteArrayOutputStream().apply {
                write(1)
                write(sps.getOrNull(1)?.toInt()?.and(0xff) ?: 0x42)
                write(sps.getOrNull(2)?.toInt()?.and(0xff) ?: 0)
                write(sps.getOrNull(3)?.toInt()?.and(0xff) ?: 0x1f)
                write(0xff)
                write(0xe1)
                writeShort(sps.size)
                write(sps)
                write(1)
                writeShort(pps.size)
                write(pps)
            }.toByteArray()
            val payload = flvVideoPayload(frameHeader = 0x17, avcPacketType = 0, compositionTime = 0, data = avcConfig)
            writeMessage(chunkStreamId = 6, typeId = RTMP_TYPE_VIDEO, streamId = publishStreamId, timestamp = 0, payload = payload)
            return payload.size
        }

        fun sendAvcFrame(annexB: ByteArray, timestampMs: Int, keyFrame: Boolean): Int {
            val nalPayload = ByteArrayOutputStream()
            H264AnnexB.splitNalUnits(annexB)
                .filter { nal ->
                    when (nal.firstOrNull()?.toInt()?.and(0x1f)) {
                        7, 8 -> false
                        else -> nal.isNotEmpty()
                    }
                }
                .forEach { nal ->
                    nalPayload.writeInt(nal.size)
                    nalPayload.write(nal)
                }
            val data = nalPayload.toByteArray()
            if (data.isEmpty()) return 0
            val frameHeader = if (keyFrame) 0x17 else 0x27
            val payload = flvVideoPayload(frameHeader = frameHeader, avcPacketType = 1, compositionTime = 0, data = data)
            writeMessage(chunkStreamId = 6, typeId = RTMP_TYPE_VIDEO, streamId = publishStreamId, timestamp = timestampMs, payload = payload)
            return payload.size
        }

        fun sendAacConfig(config: ByteArray): Int {
            val payload = ByteArrayOutputStream().apply {
                write(0xaf)
                write(0)
                write(config)
            }.toByteArray()
            writeMessage(chunkStreamId = 4, typeId = RTMP_TYPE_AUDIO, streamId = publishStreamId, timestamp = 0, payload = payload)
            return payload.size
        }

        fun sendAacFrame(frame: ByteArray, timestampMs: Int): Int {
            val payload = ByteArrayOutputStream().apply {
                write(0xaf)
                write(1)
                write(frame)
            }.toByteArray()
            writeMessage(chunkStreamId = 4, typeId = RTMP_TYPE_AUDIO, streamId = publishStreamId, timestamp = timestampMs, payload = payload)
            return payload.size
        }

        fun close() {
            backgroundReaderRunning = false
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }

        private fun handshake() {
            val localInput = input ?: error("RTMP input missing")
            val localOutput = output ?: error("RTMP output missing")
            val c1 = ByteArray(1536)
            val time = (System.currentTimeMillis() / 1_000L).toInt()
            c1[0] = ((time ushr 24) and 0xff).toByte()
            c1[1] = ((time ushr 16) and 0xff).toByte()
            c1[2] = ((time ushr 8) and 0xff).toByte()
            c1[3] = (time and 0xff).toByte()
            Random.nextBytes(c1, fromIndex = 8, toIndex = c1.size)
            localOutput.write(3)
            localOutput.write(c1)
            localOutput.flush()

            val s0 = localInput.readUnsignedByte()
            if (s0 != 3) error("Unexpected RTMP version: $s0")
            val s1 = ByteArray(1536)
            val s2 = ByteArray(1536)
            localInput.readFully(s1)
            localInput.readFully(s2)
            localOutput.write(s1)
            localOutput.flush()
        }

        private fun sendConnect(app: String, tcUrl: String) {
            val payload = amfCommand {
                writeStringValue("connect")
                writeNumberValue(1.0)
                writeObject(
                    linkedMapOf(
                        "app" to app,
                        "type" to "nonprivate",
                        "flashVer" to "FMLE/3.0 (compatible; RokidLiveStudio)",
                        "tcUrl" to tcUrl,
                        "fpad" to false,
                        "capabilities" to 239.0,
                        "audioCodecs" to 3575.0,
                        "videoCodecs" to 252.0,
                        "videoFunction" to 1.0
                    )
                )
            }
            writeMessage(chunkStreamId = 3, typeId = RTMP_TYPE_COMMAND_AMF0, streamId = 0, timestamp = 0, payload = payload)
        }

        private fun sendCreateStream() {
            val payload = amfCommand {
                writeStringValue("createStream")
                writeNumberValue(2.0)
                writeNullValue()
            }
            writeMessage(chunkStreamId = 3, typeId = RTMP_TYPE_COMMAND_AMF0, streamId = 0, timestamp = 0, payload = payload)
        }

        private fun sendReleaseStream() {
            val payload = amfCommand {
                writeStringValue("releaseStream")
                writeNumberValue(0.0)
                writeNullValue()
                writeStringValue(streamKey)
            }
            writeMessage(chunkStreamId = 3, typeId = RTMP_TYPE_COMMAND_AMF0, streamId = 0, timestamp = 0, payload = payload)
        }

        private fun sendFCPublish() {
            val payload = amfCommand {
                writeStringValue("FCPublish")
                writeNumberValue(0.0)
                writeNullValue()
                writeStringValue(streamKey)
            }
            writeMessage(chunkStreamId = 3, typeId = RTMP_TYPE_COMMAND_AMF0, streamId = 0, timestamp = 0, payload = payload)
        }

        private fun sendPublish() {
            val payload = amfCommand {
                writeStringValue("publish")
                writeNumberValue(0.0)
                writeNullValue()
                writeStringValue(streamKey)
                writeStringValue("live")
            }
            writeMessage(
                chunkStreamId = 3,
                typeId = RTMP_TYPE_COMMAND_AMF0,
                streamId = publishStreamId,
                timestamp = 0,
                payload = payload
            )
            onStatus("$platformName publish command sent")
        }

        private fun sendSetChunkSize(size: Int) {
            val payload = ByteArrayOutputStream().apply { writeInt(size) }.toByteArray()
            writeMessage(chunkStreamId = 2, typeId = RTMP_TYPE_SET_CHUNK_SIZE, streamId = 0, timestamp = 0, payload = payload)
            outboundChunkSize = size
        }

        private fun waitForCommandResult(transactionId: Int): List<Any?> {
            while (true) {
                val message = readMessage() ?: continue
                handleInboundControl(message)
                if (message.typeId != RTMP_TYPE_COMMAND_AMF0 && message.typeId != RTMP_TYPE_COMMAND_AMF3) continue
                val values = Amf0Reader(message.payload.dropAmf3MarkerIfNeeded(message.typeId)).readAll()
                val command = values.getOrNull(0) as? String ?: continue
                val transaction = (values.getOrNull(1) as? Double)?.toInt() ?: continue
                if (transaction == transactionId) {
                    when (command) {
                        "_result", "onStatus" -> return values
                        "_error" -> error("RTMP command $transactionId failed: ${describeStatus(values)}")
                    }
                }
            }
        }

        private fun waitForCreateStreamResult(transactionId: Int): Int {
            val values = waitForCommandResult(transactionId)
            return (values.lastOrNull { it is Double } as? Double)?.toInt()?.takeIf { it > 0 }
                ?: error("RTMP createStream did not return a stream id")
        }

        private fun waitForPublishStatus() {
            while (true) {
                val message = try {
                    readMessage()
                } catch (_: SocketTimeoutException) {
                    onStatus("$platformName publish pending; sending media")
                    return
                } ?: continue
                handleInboundControl(message)
                if (!handleInboundCommand(message)) continue
                val values = Amf0Reader(message.payload.dropAmf3MarkerIfNeeded(message.typeId)).readAll()
                val command = values.getOrNull(0) as? String ?: continue
                if (command == "_error") error("$platformName rejected stream: ${describeStatus(values)}")
                if (command != "onStatus") continue
                val code = statusString(values, "code").orEmpty()
                val level = statusString(values, "level").orEmpty()
                if (level.equals("error", ignoreCase = true) ||
                    code.contains("BadName", ignoreCase = true) ||
                    code.contains("Failed", ignoreCase = true)
                ) {
                    error("$platformName rejected stream: ${describeStatus(values)}")
                }
                if (code.contains("Publish.Start", ignoreCase = true) ||
                    code.contains("Publish", ignoreCase = true)
                ) {
                    return
                }
            }
        }

        private fun startBackgroundReader() {
            backgroundReaderRunning = true
            Thread {
                while (backgroundReaderRunning) {
                    runCatching {
                        val message = readMessage() ?: return@runCatching
                        handleInboundControl(message)
                        handleInboundCommand(message)
                    }.onFailure {
                        backgroundReaderRunning = false
                    }
                }
            }.apply {
                name = "rls-rtmp-reader"
                isDaemon = true
                start()
            }
        }

        private fun handleInboundControl(message: RtmpMessage) {
            when (message.typeId) {
                RTMP_TYPE_SET_CHUNK_SIZE -> {
                    if (message.payload.size >= 4) {
                        inboundChunkSize = ByteBuffer.wrap(message.payload).int
                    }
                }
                RTMP_TYPE_USER_CONTROL -> handleUserControl(message.payload)
            }
        }

        private fun handleInboundCommand(message: RtmpMessage): Boolean {
            if (message.typeId != RTMP_TYPE_COMMAND_AMF0 && message.typeId != RTMP_TYPE_COMMAND_AMF3) return false
            val values = Amf0Reader(message.payload.dropAmf3MarkerIfNeeded(message.typeId)).readAll()
            val command = values.getOrNull(0) as? String ?: return false
            if (command == "onStatus" || command == "_error") {
                onStatus("$platformName: ${describeStatus(values)}")
            }
            return true
        }

        private fun describeStatus(values: List<Any?>): String {
            val code = statusString(values, "code")
            val description = statusString(values, "description")
            val level = statusString(values, "level")
            return listOfNotNull(level, code, description).joinToString(" | ").ifBlank {
                values.firstOrNull().toString()
            }
        }

        private fun statusString(values: List<Any?>, key: String): String? {
            val statusObject = values.lastOrNull { it is Map<*, *> } as? Map<*, *> ?: return null
            return statusObject[key] as? String
        }

        private fun handleUserControl(payload: ByteArray) {
            if (payload.size < 6) return
            val eventType = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            if (eventType != 6) return
            val timestamp = ByteBuffer.wrap(payload, 2, 4).int
            val response = ByteArrayOutputStream().apply {
                writeShort(7)
                writeInt(timestamp)
            }.toByteArray()
            writeMessage(chunkStreamId = 2, typeId = RTMP_TYPE_USER_CONTROL, streamId = 0, timestamp = 0, payload = response)
        }

        private fun readMessage(): RtmpMessage? {
            val localInput = input ?: return null
            val basic = localInput.readUnsignedByte()
            val fmt = (basic and 0xc0) ushr 6
            var csid = basic and 0x3f
            if (csid == 0) csid = localInput.readUnsignedByte() + 64
            if (csid == 1) csid = localInput.readUnsignedByte() + 64 + localInput.readUnsignedByte() * 256

            val state = chunks.getOrPut(csid) { ChunkState() }
            var timestamp = state.timestamp
            var messageLength = state.messageLength
            var typeId = state.typeId
            var streamId = state.streamId
            var extendedTimestamp = false

            when (fmt) {
                0 -> {
                    timestamp = localInput.readMediumInt()
                    messageLength = localInput.readMediumInt()
                    typeId = localInput.readUnsignedByte()
                    streamId = localInput.readLittleEndianInt()
                    extendedTimestamp = timestamp == 0xffffff
                    if (extendedTimestamp) timestamp = localInput.readInt()
                    state.reset(timestamp, messageLength, typeId, streamId)
                }
                1 -> {
                    val delta = localInput.readMediumInt()
                    messageLength = localInput.readMediumInt()
                    typeId = localInput.readUnsignedByte()
                    extendedTimestamp = delta == 0xffffff
                    timestamp = state.timestamp + if (extendedTimestamp) localInput.readInt() else delta
                    streamId = state.streamId
                    state.reset(timestamp, messageLength, typeId, streamId)
                }
                2 -> {
                    val delta = localInput.readMediumInt()
                    extendedTimestamp = delta == 0xffffff
                    timestamp = state.timestamp + if (extendedTimestamp) localInput.readInt() else delta
                    state.reset(timestamp, messageLength, typeId, streamId)
                }
                3 -> {
                    if (state.messageLength <= 0) return null
                }
            }

            val remaining = state.messageLength - state.buffer.size()
            val readSize = min(inboundChunkSize, remaining)
            if (readSize <= 0) return null
            val chunk = ByteArray(readSize)
            localInput.readFully(chunk)
            state.buffer.write(chunk)
            return if (state.buffer.size() == state.messageLength) {
                RtmpMessage(state.typeId, state.streamId, state.timestamp, state.buffer.toByteArray()).also {
                    state.buffer.reset()
                }
            } else {
                null
            }
        }

        private fun writeMessage(chunkStreamId: Int, typeId: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
            val localOutput = output ?: error("RTMP output missing")
            synchronized(outputLock) {
                var offset = 0
                var first = true
                val useExtendedTimestamp = timestamp >= 0xffffff
                while (offset < payload.size || (payload.isEmpty() && first)) {
                    val chunkSize = min(outboundChunkSize, payload.size - offset)
                    writeBasicHeader(localOutput, if (first) 0 else 3, chunkStreamId)
                    if (first) {
                        localOutput.writeMediumInt(if (useExtendedTimestamp) 0xffffff else timestamp)
                        localOutput.writeMediumInt(payload.size)
                        localOutput.write(typeId)
                        localOutput.writeLittleEndianInt(streamId)
                    }
                    if (useExtendedTimestamp) {
                        localOutput.writeInt(timestamp)
                    }
                    if (chunkSize > 0) {
                        localOutput.write(payload, offset, chunkSize)
                        offset += chunkSize
                    }
                    first = false
                }
                localOutput.flush()
            }
        }

        private fun writeBasicHeader(output: OutputStream, fmt: Int, csid: Int) {
            require(csid in 2..63) { "Only RTMP short chunk stream ids are supported" }
            output.write(((fmt and 0x03) shl 6) or csid)
        }

        private fun flvVideoPayload(frameHeader: Int, avcPacketType: Int, compositionTime: Int, data: ByteArray): ByteArray =
            ByteArrayOutputStream().apply {
                write(frameHeader)
                write(avcPacketType)
                writeMediumInt(compositionTime)
                write(data)
            }.toByteArray()

        private fun ByteArray.dropAmf3MarkerIfNeeded(typeId: Int): ByteArray =
            if (typeId == RTMP_TYPE_COMMAND_AMF3 && isNotEmpty()) drop(1).toByteArray() else this

        private fun amfCommand(block: Amf0Writer.() -> Unit): ByteArray =
            Amf0Writer().apply(block).toByteArray()

        private data class RtmpMessage(
            val typeId: Int,
            val streamId: Int,
            val timestamp: Int,
            val payload: ByteArray
        )

        private class ChunkState {
            var timestamp: Int = 0
            var messageLength: Int = 0
            var typeId: Int = 0
            var streamId: Int = 0
            val buffer = ByteArrayOutputStream()

            fun reset(nextTimestamp: Int, nextMessageLength: Int, nextTypeId: Int, nextStreamId: Int) {
                timestamp = nextTimestamp
                messageLength = nextMessageLength
                typeId = nextTypeId
                streamId = nextStreamId
                buffer.reset()
            }
        }

        companion object {
            private const val RTMP_TYPE_SET_CHUNK_SIZE = 1
            private const val RTMP_TYPE_USER_CONTROL = 4
            private const val RTMP_TYPE_AUDIO = 8
            private const val RTMP_TYPE_VIDEO = 9
            private const val RTMP_TYPE_COMMAND_AMF3 = 17
            private const val RTMP_TYPE_COMMAND_AMF0 = 20
            private const val CONNECT_TIMEOUT_MS = 10_000
            private const val COMMAND_TIMEOUT_MS = 12_000
            private const val SOCKET_SEND_BUFFER_BYTES = 1024 * 1024
            private const val SOCKET_RECEIVE_BUFFER_BYTES = 256 * 1024
        }
    }

    private class Amf0Writer {
        private val output = ByteArrayOutputStream()

        fun writeStringValue(value: String) {
            output.write(2)
            output.writeShort(value.toByteArray(Charsets.UTF_8).size)
            output.write(value.toByteArray(Charsets.UTF_8))
        }

        fun writeNumberValue(value: Double) {
            output.write(0)
            output.write(ByteBuffer.allocate(8).putDouble(value).array())
        }

        fun writeBooleanValue(value: Boolean) {
            output.write(1)
            output.write(if (value) 1 else 0)
        }

        fun writeNullValue() {
            output.write(5)
        }

        fun writeObject(values: Map<String, Any?>) {
            output.write(3)
            values.forEach { (key, value) ->
                output.writeShort(key.toByteArray(Charsets.UTF_8).size)
                output.write(key.toByteArray(Charsets.UTF_8))
                when (value) {
                    is String -> writeStringValue(value)
                    is Double -> writeNumberValue(value)
                    is Boolean -> writeBooleanValue(value)
                    null -> writeNullValue()
                    else -> writeStringValue(value.toString())
                }
            }
            output.write(byteArrayOf(0, 0, 9))
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class Amf0Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun readAll(): List<Any?> {
            val values = mutableListOf<Any?>()
            while (offset < bytes.size) {
                values += readValue()
            }
            return values
        }

        private fun readValue(): Any? {
            if (offset >= bytes.size) return null
            return when (val type = readUnsignedByte()) {
                0 -> readDouble()
                1 -> readUnsignedByte() != 0
                2 -> readString()
                3 -> readObject()
                5, 6 -> null
                8 -> readEcmaArray()
                10 -> readStrictArray()
                else -> {
                    Log.d(TAG, "Skipping unsupported AMF0 type $type")
                    null
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            while (offset + 3 <= bytes.size) {
                if (bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() && bytes[offset + 2] == 9.toByte()) {
                    offset += 3
                    break
                }
                val key = readUtf8(readUnsignedShort())
                map[key] = readValue()
            }
            return map
        }

        private fun readEcmaArray(): Map<String, Any?> {
            offset += 4
            return readObject()
        }

        private fun readStrictArray(): List<Any?> {
            val count = readInt()
            return List(count) { readValue() }
        }

        private fun readString(): String = readUtf8(readUnsignedShort())

        private fun readUtf8(length: Int): String {
            val value = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            offset += length
            return value
        }

        private fun readDouble(): Double {
            val value = ByteBuffer.wrap(bytes, offset, 8).double
            offset += 8
            return value
        }

        private fun readInt(): Int {
            val value = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
            return value
        }

        private fun readUnsignedShort(): Int {
            val value = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
            offset += 2
            return value
        }

        private fun readUnsignedByte(): Int = bytes[offset++].toInt() and 0xff
    }
}

private fun DataInputStream.readMediumInt(): Int =
    (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()

private fun DataInputStream.readLittleEndianInt(): Int {
    val b0 = readUnsignedByte()
    val b1 = readUnsignedByte()
    val b2 = readUnsignedByte()
    val b3 = readUnsignedByte()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun OutputStream.writeShort(value: Int) {
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun OutputStream.writeMediumInt(value: Int) {
    write((value ushr 16) and 0xff)
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun OutputStream.writeInt(value: Int) {
    write((value ushr 24) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 8) and 0xff)
    write(value and 0xff)
}

private fun OutputStream.writeLittleEndianInt(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
}
