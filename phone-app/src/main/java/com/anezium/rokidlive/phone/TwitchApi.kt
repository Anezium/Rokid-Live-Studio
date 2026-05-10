package com.anezium.rokidlive.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class TwitchApi {
    suspend fun getMe(accessToken: String, clientId: String): TwitchUserInfo =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                clientId = clientId,
                endpoint = "$HELIX/users",
                params = emptyMap()
            )
            val first = json.optJSONArray("data")?.optJSONObject(0)
                ?: throw TwitchApiException(404, "No Twitch user found for this token")
            TwitchUserInfo(
                id = first.getString("id"),
                login = first.optString("login"),
                displayName = first.optString("display_name")
            )
        }

    suspend fun getChannel(accessToken: String, clientId: String, broadcasterId: String): TwitchChannelInfo =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                clientId = clientId,
                endpoint = "$HELIX/channels",
                params = mapOf("broadcaster_id" to broadcasterId)
            )
            val first = json.optJSONArray("data")?.optJSONObject(0)
                ?: throw TwitchApiException(404, "No Twitch channel found")
            TwitchChannelInfo(
                id = first.optString("broadcaster_id").ifBlank { broadcasterId },
                title = first.optString("title"),
                categoryId = first.optString("game_id"),
                categoryName = first.optString("game_name")
            )
        }

    suspend fun getStreamKey(accessToken: String, clientId: String, broadcasterId: String): String =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                clientId = clientId,
                endpoint = "$HELIX/streams/key",
                params = mapOf("broadcaster_id" to broadcasterId)
            )
            json.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("stream_key")
                ?.takeIf { it.isNotBlank() }
                ?: throw TwitchApiException(404, "Twitch stream key not found")
        }

    suspend fun updateChannel(
        accessToken: String,
        clientId: String,
        broadcasterId: String,
        title: String,
        category: TwitchCategory
    ) {
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("title", title)
                .put("game_id", category.id)
            requestJson(
                method = "PATCH",
                accessToken = accessToken,
                clientId = clientId,
                endpoint = "$HELIX/channels",
                params = mapOf("broadcaster_id" to broadcasterId),
                body = body,
                allowEmpty = true
            )
        }
    }

    suspend fun createChatSubscription(
        accessToken: String,
        clientId: String,
        broadcasterId: String,
        userId: String,
        sessionId: String
    ) {
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("type", "channel.chat.message")
                .put("version", "1")
                .put(
                    "condition",
                    JSONObject()
                        .put("broadcaster_user_id", broadcasterId)
                        .put("user_id", userId)
                )
                .put(
                    "transport",
                    JSONObject()
                        .put("method", "websocket")
                        .put("session_id", sessionId)
                )
            requestJson(
                method = "POST",
                accessToken = accessToken,
                clientId = clientId,
                endpoint = "$HELIX/eventsub/subscriptions",
                params = emptyMap(),
                body = body,
                allowEmpty = true
            )
        }
    }

    suspend fun getIngestServers(): List<TwitchIngestServer> =
        withContext(Dispatchers.IO) {
            val connection = (URL(INGESTS).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val body = connection.readResponse(code)
            connection.disconnect()
            if (code !in 200..299) throw TwitchApiException(code, twitchErrorMessage(body))
            val ingests = JSONObject(body).optJSONArray("ingests") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until ingests.length()) {
                    val item = ingests.optJSONObject(index) ?: continue
                    val template = item.optString("url_template")
                    val serverUrl = template
                        .replace("/{stream_key}", "")
                        .replace("{stream_key}", "")
                        .trimEnd('/')
                    if (serverUrl.isBlank()) continue
                    add(
                        TwitchIngestServer(
                            name = item.optString("name"),
                            serverUrl = serverUrl,
                            priority = item.optInt("priority", Int.MAX_VALUE),
                            default = item.optBoolean("default", false)
                        )
                    )
                }
            }.sortedWith(compareBy<TwitchIngestServer> { !it.default }.thenBy { it.priority })
        }

    private fun getJson(
        accessToken: String,
        clientId: String,
        endpoint: String,
        params: Map<String, String>
    ): JSONObject = requestJson("GET", accessToken, clientId, endpoint, params, null)

    private fun requestJson(
        method: String,
        accessToken: String,
        clientId: String,
        endpoint: String,
        params: Map<String, String>,
        body: JSONObject?,
        allowEmpty: Boolean = false
    ): JSONObject {
        val query = params.toQueryString()
        val url = URL(if (query.isBlank()) endpoint else "$endpoint?$query")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Client-Id", clientId)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        }
        val code = connection.responseCode
        val response = connection.readResponse(code)
        connection.disconnect()
        if (code !in 200..299) throw TwitchApiException(code, twitchErrorMessage(response))
        return if (response.isBlank()) {
            if (allowEmpty) JSONObject() else JSONObject("{}")
        } else {
            JSONObject(response)
        }
    }

    private fun HttpURLConnection.readResponse(code: Int): String {
        val stream = if (code in 200..299) inputStream else errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }
    }

    private fun twitchErrorMessage(response: String): String {
        if (response.isBlank()) return "Twitch API request failed"
        return runCatching {
            val json = JSONObject(response)
            json.optString("message").ifBlank { json.optString("error").ifBlank { response } }
        }.getOrElse { response }
    }

    private fun Map<String, String>.toQueryString(): String =
        entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    companion object {
        const val DEFAULT_INGEST_SERVER = "rtmp://ingest.global-contribute.live-video.net/app"
        const val LEGACY_INGEST_SERVER = "rtmp://live.twitch.tv/app"
        private const val HELIX = "https://api.twitch.tv/helix"
        private const val INGESTS = "https://ingest.twitch.tv/ingests"
    }
}

class TwitchApiException(
    val statusCode: Int,
    message: String
) : Exception("HTTP $statusCode: $message")
