package com.anezium.rokidlive.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.anezium.rokidlive.shared.H264AnnexB
import com.anezium.rokidlive.shared.MediaPacket
import com.anezium.rokidlive.shared.MediaPacketFlags
import com.anezium.rokidlive.shared.MediaPacketType
import com.anezium.rokidlive.shared.StartStreamConfig
import java.nio.ByteBuffer

class CameraH264Streamer(
    private val context: Context,
    private val packetSender: () -> MediaPacketSink,
    private val onStats: (frames: Long, bytes: Long, dropped: Long) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private var cameraThread: HandlerThread? = null
    private var encoderThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderHandler: Handler? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var latestConfigPayload: ByteArray? = null
    private var currentConfig: StartStreamConfig? = null
    private var framesSent = 0L
    private var bytesSent = 0L
    private var droppedFrames = 0L

    fun start(config: StartStreamConfig) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error("Camera permission is missing")
        }
        stop()
        currentConfig = config
        cameraThread = HandlerThread("rls-camera").also { it.start() }
        encoderThread = HandlerThread("rls-encoder").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        encoderHandler = Handler(encoderThread!!.looper)
        prepareEncoder(config)
        openCamera()
    }

    fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun setBitrate(bitRate: Int) {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate)
            })
        }
    }

    fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        session = null
        camera = null
        encoder = null
        inputSurface = null
        latestConfigPayload = null
        currentConfig = null
        cameraThread?.quitSafely()
        encoderThread?.quitSafely()
        cameraThread = null
        encoderThread = null
        cameraHandler = null
        encoderHandler = null
    }

    private fun prepareEncoder(config: StartStreamConfig) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iframeIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    sendEncodedOutput(buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError("Encoder error: ${e.diagnosticInfo}", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val payload = codecConfigFromFormat(format)
                latestConfigPayload = payload
                sendPacket(MediaPacketType.VIDEO_CONFIG, 0, nowUs(), payload)
            }
        }, encoderHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        encoder = codec
        codec.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull() ?: error("No camera found")
        val rotateAndCropMode = selectRotateAndCropMode(manager, cameraId)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createCaptureSession(device, rotateAndCropMode)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
                onError("Camera disconnected", null)
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                onError("Camera open error: $error", null)
            }
        }, cameraHandler)
    }

    private fun createCaptureSession(device: CameraDevice, rotateAndCropMode: Int?) {
        val surface = inputSurface ?: error("Encoder surface not ready")
        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(nextSession: CameraCaptureSession) {
                    session = nextSession
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        currentConfig?.fps?.let { fps ->
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        }
                        rotateAndCropMode?.let {
                            set(CaptureRequest.SCALER_ROTATE_AND_CROP, it)
                        }
                    }.build()
                    nextSession.setRepeatingRequest(request, null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("Camera capture session configure failed", null)
                }
            },
            cameraHandler
        )
    }

    private fun selectRotateAndCropMode(manager: CameraManager, cameraId: String): Int? {
        val supportedModes = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                ?.toSet()
                .orEmpty()
        }.getOrElse {
            Log.w(TAG, "Cannot read rotate-and-crop modes", it)
            emptySet()
        }
        val mode = when {
            CaptureRequest.SCALER_ROTATE_AND_CROP_270 in supportedModes ->
                CaptureRequest.SCALER_ROTATE_AND_CROP_270
            CaptureRequest.SCALER_ROTATE_AND_CROP_AUTO in supportedModes ->
                CaptureRequest.SCALER_ROTATE_AND_CROP_AUTO
            else -> null
        }
        Log.i(TAG, "Camera rotate-and-crop mode: ${mode ?: "unsupported"}")
        return mode
    }

    private fun sendEncodedOutput(encoded: ByteBuffer, info: MediaCodec.BufferInfo) {
        encoded.position(info.offset)
        encoded.limit(info.offset + info.size)
        val payload = ByteArray(info.size)
        encoded.get(payload)
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig) {
            latestConfigPayload = payload
            sendPacket(MediaPacketType.VIDEO_CONFIG, 0, info.presentationTimeUs, payload)
            return
        }
        if (isKeyFrame) {
            latestConfigPayload?.let {
                sendPacket(MediaPacketType.VIDEO_CONFIG, 0, info.presentationTimeUs, it)
            }
        }
        val flags = if (isKeyFrame) MediaPacketFlags.KEY_FRAME else 0
        sendPacket(MediaPacketType.VIDEO_FRAME, flags, info.presentationTimeUs, payload)
    }

    private fun sendPacket(type: MediaPacketType, flags: Int, timestampUs: Long, payload: ByteArray) {
        runCatching {
            val client = packetSender()
            client.send(
                MediaPacket(
                    type = type,
                    sequence = client.nextSequence(),
                    timestampUs = timestampUs,
                    flags = flags,
                    payload = payload
                )
            )
            if (type == MediaPacketType.VIDEO_FRAME) {
                framesSent++
                bytesSent += payload.size
                onStats(framesSent, bytesSent, droppedFrames)
            }
        }.onFailure {
            droppedFrames++
            Log.w(TAG, "Dropping encoded packet", it)
            onStats(framesSent, bytesSent, droppedFrames)
        }
    }

    private fun codecConfigFromFormat(format: MediaFormat): ByteArray {
        val sps = format.getByteBuffer("csd-0")?.copyBytes()
        val pps = format.getByteBuffer("csd-1")?.copyBytes()
        return buildList {
            if (sps != null) add(H264AnnexB.withStartCode(sps))
            if (pps != null) add(H264AnnexB.withStartCode(pps))
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    companion object {
        private const val TAG = "RLS-CameraStreamer"

        fun nowUs(): Long = System.nanoTime() / 1_000L
    }
}
