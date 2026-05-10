package com.anezium.rokidlive.glasses

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anezium.rokidlive.shared.Protocol
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var runtime: HelperRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ActivityCompat.requestPermissions(this, requiredPermissions(), 10)
        runtime = HelperRuntime(this)
        runtime.start()
        setContent {
            val state by runtime.state.collectAsState()
            HelperScreen(state)
        }
    }

    override fun onDestroy() {
        runtime.stop()
        super.onDestroy()
    }
}

@Composable
private fun HelperScreen(state: HelperUiState) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
            ) {
                SystemStatusStrip(
                    nowMs = nowMs,
                    batteryPercent = state.batteryPercent,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
                ChatOverlay(
                    messages = state.chatMessages,
                    nowMs = nowMs,
                    fontSizeSp = state.chatFontSizeSp,
                    maxMessages = state.chatMaxMessages,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun SystemStatusStrip(nowMs: Long, batteryPercent: Int, modifier: Modifier = Modifier) {
    val time = remember(nowMs) {
        Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .format(TimeFormatter)
    }
    val battery = if (batteryPercent in 0..100) "$batteryPercent%" else "--%"

    Row(
        modifier = modifier
            .padding(top = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            time,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        BatteryIcon(batteryPercent = batteryPercent)
        Text(
            battery,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BatteryIcon(batteryPercent: Int) {
    val fill = if (batteryPercent in 0..100) batteryPercent / 100f else 0f
    val fillColor = when {
        batteryPercent !in 0..100 -> Color.White.copy(alpha = 0.35f)
        batteryPercent <= 15 -> Color(0xFFFF6B6B)
        batteryPercent <= 35 -> Color(0xFFFACC15)
        else -> Color(0xFF5CF018)
    }
    Canvas(modifier = Modifier.size(width = 24.dp, height = 12.dp)) {
        val strokeWidth = 1.2.dp.toPx()
        val capGap = 1.5.dp.toPx()
        val capWidth = 2.5.dp.toPx()
        val bodyWidth = size.width - capWidth - capGap
        val radius = 2.4.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset.Zero,
            size = Size(bodyWidth, size.height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = strokeWidth)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset(bodyWidth + capGap, size.height * 0.3f),
            size = Size(capWidth, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        )
        if (batteryPercent in 0..100) {
            val inset = 2.3.dp.toPx()
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(inset, inset),
                size = Size((bodyWidth - inset * 2).coerceAtLeast(0f) * fill, (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())
            )
        }
    }
}

@Composable
private fun ChatOverlay(
    messages: List<com.anezium.rokidlive.shared.ChatOverlayMessage>,
    nowMs: Long,
    fontSizeSp: Int,
    maxMessages: Int,
    modifier: Modifier = Modifier
) {
    val normalizedFontSize = fontSizeSp.coerceAtLeast(1)
    val visibleMessages = messages
        .filter { nowMs - it.timestampMs <= CHAT_VISIBLE_MS }
        .takeLast(maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT))
    if (visibleMessages.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Color(0x665CF018), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        visibleMessages.forEach { message ->
            Text(
                text = "${message.author.ifBlank { "Viewer" }}: ${message.text}",
                color = Color.White,
                fontSize = normalizedFontSize.sp,
                lineHeight = (normalizedFontSize + 4).sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val CHAT_VISIBLE_MS = 10_000L

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()
