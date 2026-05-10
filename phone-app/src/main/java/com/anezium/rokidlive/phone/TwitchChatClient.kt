package com.anezium.rokidlive.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TwitchChatClient(
    private val api: TwitchApi,
    private val accessToken: String,
    private val clientId: String,
    private val broadcasterId: String,
    private val userId: String,
    private val onStatus: (String) -> Unit,
    private val onMessages: (List<TwitchChatMessage>) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val running = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        connect(EVENTSUB_WS)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        webSocket?.close(1000, "stopped")
        webSocket = null
        scope.cancel()
    }

    private fun connect(url: String) {
        if (!running.get()) return
        onStatus("Connecting Twitch chat...")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("Twitch EventSub connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleMessage(text) }
                .onFailure { if (running.get()) onError("Twitch chat parse failed", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (running.get()) onError("Twitch chat failed", t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running.get()) onStatus("Twitch chat closed: $reason")
        }
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        when (metadata.optString("message_type")) {
            "session_welcome" -> {
                val sessionId = json.optJSONObject("payload")
                    ?.optJSONObject("session")
                    ?.optString("id")
                    .orEmpty()
                if (sessionId.isBlank()) {
                    onError("Twitch chat session missing", null)
                    return
                }
                scope.launch {
                    runCatching {
                        api.createChatSubscription(
                            accessToken = accessToken,
                            clientId = clientId,
                            broadcasterId = broadcasterId,
                            userId = userId,
                            sessionId = sessionId
                        )
                        onStatus("Twitch chat connected")
                    }.onFailure {
                        if (running.get()) onError("Twitch chat subscription failed", it)
                    }
                }
            }
            "session_keepalive" -> Unit
            "session_reconnect" -> {
                val reconnectUrl = json.optJSONObject("payload")
                    ?.optJSONObject("session")
                    ?.optString("reconnect_url")
                    .orEmpty()
                if (reconnectUrl.isNotBlank()) {
                    onStatus("Reconnecting Twitch chat...")
                    webSocket?.close(1000, "reconnect")
                    connect(reconnectUrl)
                }
            }
            "notification" -> {
                val event = json.optJSONObject("payload")?.optJSONObject("event") ?: return
                val textValue = event.optJSONObject("message")?.optString("text").orEmpty().trim()
                if (textValue.isBlank()) return
                onMessages(
                    listOf(
                        TwitchChatMessage(
                            id = event.optString("message_id"),
                            author = event.optString("chatter_user_name").ifBlank {
                                event.optString("chatter_user_login").ifBlank { "Viewer" }
                            },
                            text = textValue,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                )
            }
            "revocation" -> onError("Twitch chat subscription revoked", null)
        }
    }

    companion object {
        private const val EVENTSUB_WS = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30"
    }
}
