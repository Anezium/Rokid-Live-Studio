package com.anezium.rokidlive.phone

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Bundle
import android.view.Surface
import com.anezium.rokidlive.shared.H264AnnexB
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class YoutubeVideoRotationTranscoder(
    private val platformName: String = "YouTube",
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val iframeIntervalSeconds: Int,
    private val rotationDegrees: Int,
    private val mirrorHorizontally: Boolean,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (payload: ByteArray, timestampUs: Long, keyFrame: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val onInputBackpressure: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val commands = LinkedBlockingQueue<Command>(QUEUE_CAPACITY)
    private val inputKeyframeRequired = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastConfigPayload: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        inputKeyframeRequired.set(false)
        lastConfigPayload = null
        thread = Thread(::runLoop, "rls-${platformName.lowercase()}-rotator").also { it.start() }
    }

    fun configure(configPayload: ByteArray) {
        val previous = lastConfigPayload
        if (previous != null && previous.contentEquals(configPayload)) return
        val storedConfig = configPayload.copyOf()
        lastConfigPayload = storedConfig
        offerControl(Command.Configure(storedConfig))
    }

    fun queueFrame(payload: ByteArray, timestampUs: Long, keyFrame: Boolean) {
        if (inputKeyframeRequired.get() && !keyFrame) return
        offerFrame(Command.Frame(payload, timestampUs, keyFrame))
    }

    fun requestKeyFrame() {
        offerControl(Command.RequestKeyframe)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        inputKeyframeRequired.set(false)
        lastConfigPayload = null
        commands.offer(Command.Stop)
        thread?.interrupt()
        thread = null
    }

    private fun offerControl(command: Command) {
        if (!running.get()) return
        if (commands.offer(command)) return
        when (command) {
            is Command.Configure -> {
                requestInputResync("$platformName rotation config changed; waiting for a clean Rokid keyframe")
                commands.offer(command)
            }
            Command.RequestKeyframe -> Unit
            Command.Stop -> commands.offer(command)
            is Command.Frame -> Unit
        }
    }

    private fun offerFrame(command: Command.Frame) {
        if (!running.get()) return
        if (commands.offer(command)) {
            if (command.keyFrame) inputKeyframeRequired.set(false)
            return
        }
        requestInputResync("$platformName rotation queue is full; waiting for a clean Rokid keyframe")
        if (command.keyFrame && commands.offer(command)) {
            inputKeyframeRequired.set(false)
        }
    }

    private fun runLoop() {
        var worker: Worker? = null
        runCatching {
            val mirrorLabel = if (mirrorHorizontally) ", mirror fix" else ""
            onStatus(
                "$platformName rotation pipeline: ${inputWidth}x${inputHeight} -> " +
                    "${outputWidth}x${outputHeight}, rot ${rotationDegrees}$mirrorLabel"
            )
            while (running.get()) {
                when (val command = commands.poll(250, TimeUnit.MILLISECONDS)) {
                    null -> worker?.drainEncoder(endOfStream = false)
                    is Command.Configure -> {
                        worker?.release()
                        worker = Worker(command.payload).also { it.start() }
                    }
                    is Command.Frame -> {
                        val localWorker = worker
                        if (localWorker != null) {
                            if (command.keyFrame) localWorker.requestKeyFrame()
                            if (!localWorker.processFrame(command.payload, command.timestampUs)) {
                                requestInputResync("$platformName decoder is busy; waiting for a clean Rokid keyframe")
                            }
                        }
                    }
                    Command.RequestKeyframe -> worker?.requestKeyFrame()
                    Command.Stop -> break
                }
            }
        }.onFailure {
            if (running.get()) onError("$platformName rotation pipeline failed", it)
        }
        running.set(false)
        worker?.release()
    }

    private fun requestInputResync(message: String) {
        commands.removeIf { it is Command.Frame }
        if (inputKeyframeRequired.compareAndSet(false, true)) {
            onStatus(message)
        }
        onInputBackpressure()
    }

    private inner class Worker(private val configPayload: ByteArray) {
        private val frameSync = Object()
        private var frameAvailable = false
        private var egl: EncoderEgl? = null
        private var renderer: TextureRenderer? = null
        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var encoderInputSurface: Surface? = null
        private var decoder: MediaCodec? = null
        private var encoder: MediaCodec? = null
        private val decoderInfo = MediaCodec.BufferInfo()
        private val encoderInfo = MediaCodec.BufferInfo()

        fun start() {
            val sets = H264AnnexB.parameterSetsFromCodecConfig(configPayload)
            if (!sets.isComplete) error("Input H.264 config incomplete")

            val nextEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encoderFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outputWidth,
                outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeIntervalSeconds)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            nextEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val nextEncoderInputSurface = nextEncoder.createInputSurface()

            val nextEgl = EncoderEgl(nextEncoderInputSurface).also { it.makeCurrent() }
            val nextRenderer = TextureRenderer(
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = mirrorHorizontally
            ).also { it.init() }
            val nextSurfaceTexture = SurfaceTexture(nextRenderer.textureId).apply {
                setDefaultBufferSize(inputWidth, inputHeight)
                setOnFrameAvailableListener {
                    synchronized(frameSync) {
                        frameAvailable = true
                        frameSync.notifyAll()
                    }
                }
            }
            val nextDecoderSurface = Surface(nextSurfaceTexture)

            val nextDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val decoderFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                inputWidth,
                inputHeight
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(H264AnnexB.withStartCode(requireNotNull(sets.sps))))
                setByteBuffer("csd-1", ByteBuffer.wrap(H264AnnexB.withStartCode(requireNotNull(sets.pps))))
            }
            nextDecoder.configure(decoderFormat, nextDecoderSurface, null, 0)

            egl = nextEgl
            renderer = nextRenderer
            surfaceTexture = nextSurfaceTexture
            decoderSurface = nextDecoderSurface
            encoderInputSurface = nextEncoderInputSurface
            decoder = nextDecoder
            encoder = nextEncoder

            nextEncoder.start()
            nextDecoder.start()
            requestKeyFrame()
        }

        fun processFrame(payload: ByteArray, timestampUs: Long): Boolean {
            val localDecoder = decoder ?: return false
            drainEncoder(endOfStream = false)
            val inputIndex = localDecoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) return false
            val input = localDecoder.getInputBuffer(inputIndex) ?: return false
            input.clear()
            input.put(payload)
            localDecoder.queueInputBuffer(inputIndex, 0, payload.size, timestampUs, 0)
            drainDecoder()
            drainEncoder(endOfStream = false)
            return true
        }

        private fun drainDecoder() {
            val localDecoder = decoder ?: return
            while (true) {
                when (val outputIndex = localDecoder.dequeueOutputBuffer(decoderInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val render = decoderInfo.size > 0
                        localDecoder.releaseOutputBuffer(outputIndex, render)
                        if (render) renderDecodedFrame(decoderInfo.presentationTimeUs)
                    }
                }
            }
        }

        private fun renderDecodedFrame(timestampUs: Long) {
            val localEgl = egl ?: return
            val localRenderer = renderer ?: return
            val localSurfaceTexture = surfaceTexture ?: return
            if (!awaitFrame()) return
            localSurfaceTexture.updateTexImage()
            val textureTransform = FloatArray(16)
            localSurfaceTexture.getTransformMatrix(textureTransform)
            localEgl.makeCurrent()
            localRenderer.draw(textureTransform, outputWidth, outputHeight)
            localEgl.setPresentationTime(timestampUs * 1_000L)
            localEgl.swapBuffers()
        }

        private fun awaitFrame(): Boolean {
            synchronized(frameSync) {
                var waits = 0
                while (!frameAvailable && waits < MAX_FRAME_WAITS) {
                    frameSync.wait(FRAME_WAIT_MS)
                    waits++
                }
                if (!frameAvailable) return false
                frameAvailable = false
                return true
            }
        }

        fun drainEncoder(endOfStream: Boolean) {
            val localEncoder = encoder ?: return
            if (endOfStream) localEncoder.signalEndOfInputStream()
            while (true) {
                when (val outputIndex = localEncoder.dequeueOutputBuffer(encoderInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onConfig(codecConfigFromFormat(localEncoder.outputFormat))
                    else -> if (outputIndex >= 0) {
                        val buffer = localEncoder.getOutputBuffer(outputIndex)
                        if (buffer != null && encoderInfo.size > 0) {
                            buffer.position(encoderInfo.offset)
                            buffer.limit(encoderInfo.offset + encoderInfo.size)
                            val payload = buffer.copyBytes()
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                onConfig(ensureAnnexB(payload))
                            } else {
                                val keyFrame = encoderInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                onFrame(ensureAnnexB(payload), encoderInfo.presentationTimeUs, keyFrame)
                            }
                        }
                        localEncoder.releaseOutputBuffer(outputIndex, false)
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun requestKeyFrame() {
            runCatching {
                encoder?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }

        fun release() {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { encoderInputSurface?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            decoder = null
            encoder = null
            decoderSurface = null
            surfaceTexture = null
            encoderInputSurface = null
            renderer = null
            egl = null
        }
    }

    private sealed interface Command {
        data class Configure(val payload: ByteArray) : Command
        data class Frame(val payload: ByteArray, val timestampUs: Long, val keyFrame: Boolean) : Command
        data object RequestKeyframe : Command
        data object Stop : Command
    }

    companion object {
        private const val TAG = "RLS-YtRotator"
        private const val QUEUE_CAPACITY = 300
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val FRAME_WAIT_MS = 100L
        private const val MAX_FRAME_WAITS = 5

        private fun codecConfigFromFormat(format: MediaFormat): ByteArray {
            val sps = format.getByteBuffer("csd-0")?.copyBytes()
            val pps = format.getByteBuffer("csd-1")?.copyBytes()
            return buildList {
                if (sps != null) add(H264AnnexB.withStartCode(sps))
                if (pps != null) add(H264AnnexB.withStartCode(pps))
            }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        }

        private fun ensureAnnexB(payload: ByteArray): ByteArray =
            if (H264AnnexB.splitNalUnits(payload).isNotEmpty()) payload else H264AnnexB.withStartCode(payload)

        private fun ByteBuffer.copyBytes(): ByteArray {
            val duplicate = duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            return bytes
        }
    }
}

private class EncoderEgl(private val surface: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
        val config = chooseConfig()
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL window surface failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL makeCurrent failed" }
    }

    fun setPresentationTime(timestampNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNs)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)) {
            "EGL chooseConfig failed"
        }
        return requireNotNull(configs[0]) { "No EGL config" }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

private class TextureRenderer(
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val rotationDegrees: Int,
    private val mirrorHorizontally: Boolean
) {
    var textureId: Int = 0
        private set
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var stMatrixHandle = 0
    private val mvpMatrix = FloatArray(16)
    private val vertices: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val texCoords: FloatBuffer = floatBufferOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    fun init() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        Matrix.setIdentityM(mvpMatrix, 0)
        val (fitScaleX, fitScaleY) = fitScale()
        Matrix.scaleM(mvpMatrix, 0, fitScaleX, fitScaleY, 1f)
        if (mirrorHorizontally) {
            Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }
        Matrix.rotateM(mvpMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
    }

    fun draw(textureTransform: FloatArray, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, textureTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val nextProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(nextProgram, vertexShader)
        GLES20.glAttachShader(nextProgram, fragmentShader)
        GLES20.glLinkProgram(nextProgram)
        val status = IntArray(1)
        GLES20.glGetProgramiv(nextProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(nextProgram)
            GLES20.glDeleteProgram(nextProgram)
            error("GL program link failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return nextProgram
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }

    private fun fitScale(): Pair<Float, Float> {
        val visualAspect = if (rotationDegrees.normalizedRotation().isQuarterTurn()) {
            inputHeight.toFloat() / inputWidth.toFloat()
        } else {
            inputWidth.toFloat() / inputHeight.toFloat()
        }
        val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
        return if (visualAspect > outputAspect) {
            1f to (outputAspect / visualAspect)
        } else {
            (visualAspect / outputAspect) to 1f
        }
    }

    private fun Int.normalizedRotation(): Int = ((this % 360) + 360) % 360

    private fun Int.isQuarterTurn(): Boolean = this == 90 || this == 270
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
