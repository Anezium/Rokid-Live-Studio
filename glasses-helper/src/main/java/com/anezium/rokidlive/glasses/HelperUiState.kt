package com.anezium.rokidlive.glasses

import com.anezium.rokidlive.shared.ChatOverlayMessage
import com.anezium.rokidlive.shared.Protocol

data class HelperUiState(
    val bridgeState: String = "Bridge starting",
    val p2pStatus: String = "",
    val streamState: String = "Idle",
    val lastCommand: String = "",
    val phoneEndpoint: String = "",
    val framesSent: Long = 0,
    val bytesSent: Long = 0,
    val droppedFrames: Long = 0,
    val error: String = "",
    val batteryPercent: Int = -1,
    val chatFontSizeSp: Int = Protocol.DEFAULT_CHAT_FONT_SIZE_SP,
    val chatMaxMessages: Int = Protocol.DEFAULT_CHAT_MAX_MESSAGES,
    val chatMessages: List<ChatOverlayMessage> = emptyList()
)
