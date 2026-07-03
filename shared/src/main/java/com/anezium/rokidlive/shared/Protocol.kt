package com.anezium.rokidlive.shared

import org.json.JSONObject
import org.json.JSONArray

object Protocol {
    const val CONTROL_CHANNEL = "anezium_rls_control"
    const val STATUS_CHANNEL = "anezium_rls_status"
    const val HELPER_PACKAGE = "com.anezium.rokidlive.glasses"
    const val HELPER_MAIN_ACTIVITY = "com.anezium.rokidlive.glasses.MainActivity"
    const val DEFAULT_PORT = 39440
    const val DEFAULT_REVERSE_PORT = 39441
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val DEFAULT_FPS = 24
    const val DEFAULT_VIDEO_BITRATE = 2_000_000
    const val DEFAULT_IFRAME_INTERVAL_SECONDS = 2
    const val DEFAULT_CHAT_FONT_SIZE_SP = 17
    const val DEFAULT_CHAT_BOTTOM_OFFSET_DP = 0
    const val MIN_CHAT_BOTTOM_OFFSET_DP = 0
    const val MAX_CHAT_BOTTOM_OFFSET_DP = 200
    const val DEFAULT_CHAT_MAX_MESSAGES = 5
    const val MAX_CHAT_MESSAGE_COUNT = 20
}

enum class ControlType {
    HELLO,
    START_P2P,
    STOP_P2P,
    START_STREAM,
    START_REVERSE_STREAM,
    STOP_STREAM,
    SET_BITRATE,
    SET_CHAT_MESSAGES,
    SET_CHAT_STYLE,
    REQUEST_KEYFRAME,
    PING
}

enum class StatusType {
    READY,
    P2P,
    STARTED,
    STOPPED,
    ERROR,
    STATS,
    PONG
}

data class StartStreamConfig(
    val host: String,
    val port: Int,
    val token: String,
    val width: Int = Protocol.DEFAULT_WIDTH,
    val height: Int = Protocol.DEFAULT_HEIGHT,
    val fps: Int = Protocol.DEFAULT_FPS,
    val videoBitrate: Int = Protocol.DEFAULT_VIDEO_BITRATE,
    val iframeIntervalSeconds: Int = Protocol.DEFAULT_IFRAME_INTERVAL_SECONDS
)

data class StartReverseStreamConfig(
    val port: Int,
    val token: String,
    val width: Int = Protocol.DEFAULT_WIDTH,
    val height: Int = Protocol.DEFAULT_HEIGHT,
    val fps: Int = Protocol.DEFAULT_FPS,
    val videoBitrate: Int = Protocol.DEFAULT_VIDEO_BITRATE,
    val iframeIntervalSeconds: Int = Protocol.DEFAULT_IFRAME_INTERVAL_SECONDS
) {
    fun asLoopbackStartConfig(): StartStreamConfig = StartStreamConfig(
        host = "0.0.0.0",
        port = port,
        token = token,
        width = width,
        height = height,
        fps = fps,
        videoBitrate = videoBitrate,
        iframeIntervalSeconds = iframeIntervalSeconds
    )
}

data class StartP2pConfig(
    val sessionId: String,
    val preferGroupOwner: Boolean = true
)

data class ChatOverlayMessage(
    val author: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface ControlMessage {
    val type: ControlType

    data class Hello(val appVersion: String = "0.1.0") : ControlMessage {
        override val type = ControlType.HELLO
    }

    data class StartP2p(val config: StartP2pConfig) : ControlMessage {
        override val type = ControlType.START_P2P
    }

    data object StopP2p : ControlMessage {
        override val type = ControlType.STOP_P2P
    }

    data class StartStream(val config: StartStreamConfig) : ControlMessage {
        override val type = ControlType.START_STREAM
    }

    data class StartReverseStream(val config: StartReverseStreamConfig) : ControlMessage {
        override val type = ControlType.START_REVERSE_STREAM
    }

    data object StopStream : ControlMessage {
        override val type = ControlType.STOP_STREAM
    }

    data class SetBitrate(val videoBitrate: Int) : ControlMessage {
        override val type = ControlType.SET_BITRATE
    }

    data class SetChatMessages(val messages: List<ChatOverlayMessage>) : ControlMessage {
        override val type = ControlType.SET_CHAT_MESSAGES
    }

    data class SetChatStyle(
        val fontSizeSp: Int,
        val maxMessages: Int = Protocol.DEFAULT_CHAT_MAX_MESSAGES,
        val bottomOffsetDp: Int = Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP
    ) : ControlMessage {
        override val type = ControlType.SET_CHAT_STYLE
    }

    data object RequestKeyframe : ControlMessage {
        override val type = ControlType.REQUEST_KEYFRAME
    }

    data class Ping(val nonce: String) : ControlMessage {
        override val type = ControlType.PING
    }
}

data class StatusMessage(
    val type: StatusType,
    val message: String = "",
    val fps: Int = 0,
    val bitrate: Int = 0,
    val framesSent: Long = 0,
    val bytesSent: Long = 0,
    val droppedFrames: Long = 0,
    val networkInfo: String = "",
    val reversePort: Int = 0,
    val p2pDeviceName: String = "",
    val p2pDeviceAddress: String = "",
    val p2pGroupOwnerAddress: String = "",
    val p2pRole: String = "",
    val p2pConnected: Boolean = false,
    val batteryPercent: Int = -1
)

object JsonProtocol {
    fun encodeControl(message: ControlMessage): String {
        val json = JSONObject()
            .put("type", message.type.name)
        when (message) {
            is ControlMessage.Hello -> json.put("appVersion", message.appVersion)
            is ControlMessage.StartP2p -> json
                .put("sessionId", message.config.sessionId)
                .put("preferGroupOwner", message.config.preferGroupOwner)
            is ControlMessage.StartStream -> json
                .put("host", message.config.host)
                .put("port", message.config.port)
                .put("token", message.config.token)
                .put("width", message.config.width)
                .put("height", message.config.height)
                .put("fps", message.config.fps)
                .put("videoBitrate", message.config.videoBitrate)
                .put("iframeIntervalSeconds", message.config.iframeIntervalSeconds)
            is ControlMessage.StartReverseStream -> json
                .put("port", message.config.port)
                .put("token", message.config.token)
                .put("width", message.config.width)
                .put("height", message.config.height)
                .put("fps", message.config.fps)
                .put("videoBitrate", message.config.videoBitrate)
                .put("iframeIntervalSeconds", message.config.iframeIntervalSeconds)
            is ControlMessage.SetBitrate -> json.put("videoBitrate", message.videoBitrate)
            is ControlMessage.SetChatMessages -> json.put(
                "messages",
                JSONArray().apply {
                    message.messages.forEach { chatMessage ->
                        put(
                            JSONObject()
                                .put("author", chatMessage.author)
                                .put("text", chatMessage.text)
                                .put("timestampMs", chatMessage.timestampMs)
                        )
                    }
                }
            )
            is ControlMessage.SetChatStyle -> json
                .put("fontSizeSp", message.fontSizeSp.sanitizeChatFontSize())
                .put("maxMessages", message.maxMessages.sanitizeChatMaxMessages())
                .put("bottomOffsetDp", message.bottomOffsetDp.sanitizeChatBottomOffset())
            is ControlMessage.Ping -> json.put("nonce", message.nonce)
            ControlMessage.RequestKeyframe,
            ControlMessage.StopP2p,
            ControlMessage.StopStream -> Unit
        }
        return json.toString()
    }

    fun decodeControl(raw: String): ControlMessage {
        val json = JSONObject(raw)
        return when (ControlType.valueOf(json.getString("type"))) {
            ControlType.HELLO -> ControlMessage.Hello(json.optString("appVersion", "unknown"))
            ControlType.START_P2P -> ControlMessage.StartP2p(
                StartP2pConfig(
                    sessionId = json.optString("sessionId"),
                    preferGroupOwner = json.optBoolean("preferGroupOwner", true)
                )
            )
            ControlType.STOP_P2P -> ControlMessage.StopP2p
            ControlType.START_STREAM -> ControlMessage.StartStream(
                StartStreamConfig(
                    host = json.getString("host"),
                    port = json.optInt("port", Protocol.DEFAULT_PORT),
                    token = json.getString("token"),
                    width = json.optInt("width", Protocol.DEFAULT_WIDTH),
                    height = json.optInt("height", Protocol.DEFAULT_HEIGHT),
                    fps = json.optInt("fps", Protocol.DEFAULT_FPS),
                    videoBitrate = json.optInt("videoBitrate", Protocol.DEFAULT_VIDEO_BITRATE),
                    iframeIntervalSeconds = json.optInt(
                        "iframeIntervalSeconds",
                        Protocol.DEFAULT_IFRAME_INTERVAL_SECONDS
                    )
                )
            )
            ControlType.START_REVERSE_STREAM -> ControlMessage.StartReverseStream(
                StartReverseStreamConfig(
                    port = json.optInt("port", Protocol.DEFAULT_REVERSE_PORT),
                    token = json.getString("token"),
                    width = json.optInt("width", Protocol.DEFAULT_WIDTH),
                    height = json.optInt("height", Protocol.DEFAULT_HEIGHT),
                    fps = json.optInt("fps", Protocol.DEFAULT_FPS),
                    videoBitrate = json.optInt("videoBitrate", Protocol.DEFAULT_VIDEO_BITRATE),
                    iframeIntervalSeconds = json.optInt(
                        "iframeIntervalSeconds",
                        Protocol.DEFAULT_IFRAME_INTERVAL_SECONDS
                    )
                )
            )
            ControlType.STOP_STREAM -> ControlMessage.StopStream
            ControlType.SET_BITRATE -> ControlMessage.SetBitrate(json.getInt("videoBitrate"))
            ControlType.SET_CHAT_MESSAGES -> ControlMessage.SetChatMessages(
                buildList {
                    val messages = json.optJSONArray("messages") ?: JSONArray()
                    for (index in 0 until messages.length()) {
                        val item = messages.optJSONObject(index) ?: continue
                        add(
                            ChatOverlayMessage(
                                author = item.optString("author"),
                                text = item.optString("text"),
                                timestampMs = item.optLong("timestampMs", System.currentTimeMillis())
                            )
                        )
                    }
                }
            )
            ControlType.SET_CHAT_STYLE -> ControlMessage.SetChatStyle(
                fontSizeSp = json.optInt("fontSizeSp", Protocol.DEFAULT_CHAT_FONT_SIZE_SP).sanitizeChatFontSize(),
                maxMessages = json.optInt("maxMessages", Protocol.DEFAULT_CHAT_MAX_MESSAGES).sanitizeChatMaxMessages(),
                bottomOffsetDp = json.optInt(
                    "bottomOffsetDp",
                    Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP
                ).sanitizeChatBottomOffset()
            )
            ControlType.REQUEST_KEYFRAME -> ControlMessage.RequestKeyframe
            ControlType.PING -> ControlMessage.Ping(json.optString("nonce"))
        }
    }

    fun encodeStatus(message: StatusMessage): String = JSONObject()
        .put("type", message.type.name)
        .put("message", message.message)
        .put("fps", message.fps)
        .put("bitrate", message.bitrate)
        .put("framesSent", message.framesSent)
        .put("bytesSent", message.bytesSent)
        .put("droppedFrames", message.droppedFrames)
        .put("networkInfo", message.networkInfo)
        .put("reversePort", message.reversePort)
        .put("p2pDeviceName", message.p2pDeviceName)
        .put("p2pDeviceAddress", message.p2pDeviceAddress)
        .put("p2pGroupOwnerAddress", message.p2pGroupOwnerAddress)
        .put("p2pRole", message.p2pRole)
        .put("p2pConnected", message.p2pConnected)
        .put("batteryPercent", message.batteryPercent)
        .toString()

    fun decodeStatus(raw: String): StatusMessage {
        val json = JSONObject(raw)
        return StatusMessage(
            type = StatusType.valueOf(json.getString("type")),
            message = json.optString("message"),
            fps = json.optInt("fps"),
            bitrate = json.optInt("bitrate"),
            framesSent = json.optLong("framesSent"),
            bytesSent = json.optLong("bytesSent"),
            droppedFrames = json.optLong("droppedFrames"),
            networkInfo = json.optString("networkInfo"),
            reversePort = json.optInt("reversePort"),
            p2pDeviceName = json.optString("p2pDeviceName"),
            p2pDeviceAddress = json.optString("p2pDeviceAddress"),
            p2pGroupOwnerAddress = json.optString("p2pGroupOwnerAddress"),
            p2pRole = json.optString("p2pRole"),
            p2pConnected = json.optBoolean("p2pConnected"),
            batteryPercent = json.optInt("batteryPercent", -1)
        )
    }

    private fun Int.sanitizeChatFontSize(): Int =
        coerceAtLeast(1)

    private fun Int.sanitizeChatMaxMessages(): Int =
        coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)

    private fun Int.sanitizeChatBottomOffset(): Int =
        coerceIn(Protocol.MIN_CHAT_BOTTOM_OFFSET_DP, Protocol.MAX_CHAT_BOTTOM_OFFSET_DP)
}
