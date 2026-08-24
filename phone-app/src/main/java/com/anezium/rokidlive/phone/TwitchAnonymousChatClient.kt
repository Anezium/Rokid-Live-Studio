package com.anezium.rokidlive.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class TwitchAnonymousChatClient(
    channel: String,
    private val onStatus: (String) -> Unit,
    private val onMessages: (List<TwitchChatMessage>) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val channel = channel.trim().removePrefix("#").trim().lowercase()
    private val nickname = "justinfan${Random.nextInt(10_000, 100_000)}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val running = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connect(isReconnect = false)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectScheduled.set(false)
        webSocket?.close(1000, "stopped")
        webSocket = null
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    private fun connect(isReconnect: Boolean) {
        if (!running.get()) return
        onStatus(if (isReconnect) "Reconnecting Twitch chat..." else "Connecting Twitch chat (anonymous)...")
        val request = Request.Builder().url(IRC_WEBSOCKET_URL).build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) return
        val delayMs = reconnectDelayMs
        reconnectDelayMs = min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS)
        onStatus("Reconnecting Twitch chat in ${delayMs / 1_000}s...")
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectScheduled.set(false)
            reconnectJob = null
            if (running.get()) connect(isReconnect = true)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            webSocket.send("CAP REQ :twitch.tv/tags")
            webSocket.send("NICK $nickname")
            webSocket.send("JOIN #$channel")
            onStatus("Twitch chat connected (anonymous)")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleFrame(webSocket, text) }
                .onFailure { if (running.get()) onError("Twitch anonymous chat parse failed", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running.get()) return
            if (this@TwitchAnonymousChatClient.webSocket === webSocket) {
                this@TwitchAnonymousChatClient.webSocket = null
            }
            onError("Twitch anonymous chat failed", t)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!running.get()) return
            if (this@TwitchAnonymousChatClient.webSocket === webSocket) {
                this@TwitchAnonymousChatClient.webSocket = null
            }
            scheduleReconnect()
        }
    }

    private fun handleFrame(webSocket: WebSocket, frame: String) {
        frame.split("\r\n", "\n").forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith("PING :")) {
                webSocket.send("PONG :${line.substringAfter("PING :")}")
                return@forEach
            }
            parsePrivmsg(line)?.let { onMessages(listOf(it)) }
        }
    }

    private fun parsePrivmsg(line: String): TwitchChatMessage? {
        val commandIndex = line.indexOf(" PRIVMSG #")
        if (commandIndex < 0) return null
        val textIndex = line.indexOf(" :", startIndex = commandIndex + 10)
        if (textIndex < 0) return null

        val tags = if (line.startsWith('@')) {
            line.substring(1, line.indexOf(' ').takeIf { it > 0 } ?: return null)
                .split(';')
                .mapNotNull { tag ->
                    val separator = tag.indexOf('=')
                    if (separator < 0) null else tag.substring(0, separator) to decodeTag(tag.substring(separator + 1))
                }
                .toMap()
        } else {
            emptyMap()
        }
        val prefixStart = if (line.startsWith('@')) line.indexOf(" :") + 1 else 0
        val username = line.substring(prefixStart)
            .removePrefix(":")
            .substringBefore('!')
            .ifBlank { "Viewer" }
        val author = tags["display-name"].orEmpty().ifBlank { username }
        val messageText = line.substring(textIndex + 2)
        if (messageText.isBlank()) return null
        return TwitchChatMessage(
            id = tags["id"].orEmpty(),
            author = author,
            text = messageText,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun decodeTag(value: String): String {
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                decoded.append(
                    when (value[index + 1]) {
                        's' -> ' '
                        ':' -> ';'
                        'r' -> '\r'
                        'n' -> '\n'
                        '\\' -> '\\'
                        else -> value[index + 1]
                    }
                )
                index += 2
            } else {
                decoded.append(value[index])
                index++
            }
        }
        return decoded.toString()
    }

    private companion object {
        private const val IRC_WEBSOCKET_URL = "wss://irc-ws.chat.twitch.tv"
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
