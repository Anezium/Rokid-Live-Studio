package com.anezium.rokidlive.phone

import com.anezium.rokidlive.shared.VideoPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import kotlin.math.min

class YoutubeLiveApi {
    suspend fun getMineChannel(accessToken: String): YoutubeChannelInfo =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                endpoint = "https://www.googleapis.com/youtube/v3/channels",
                params = mapOf(
                    "part" to "snippet",
                    "mine" to "true"
                )
            )
            val first = json.optJSONArray("items")?.optJSONObject(0)
                ?: throw YoutubeApiException(404, "No YouTube channel found for this OAuth token")
            YoutubeChannelInfo(
                id = first.getString("id"),
                title = first.optJSONObject("snippet")?.optString("title").orEmpty()
            )
        }

    suspend fun createLive(accessToken: String, request: YoutubeLiveRequest): CreatedYoutubeLive =
        withContext(Dispatchers.IO) {
            val broadcast = createBroadcast(accessToken, request)
            updateVideoMetadata(accessToken, broadcast.id, request)
            val stream = createStream(accessToken, request.title, request.preset)
            bindBroadcast(accessToken, broadcast.id, stream.id)
            CreatedYoutubeLive(
                broadcastId = broadcast.id,
                streamId = stream.id,
                title = request.title,
                privacy = request.privacy,
                ingestionAddress = stream.rtmpsIngestionAddress.ifBlank { stream.ingestionAddress },
                streamName = stream.streamName
            )
        }

    suspend fun getLiveChatId(accessToken: String, broadcastId: String): String =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                endpoint = "https://www.googleapis.com/youtube/v3/liveBroadcasts",
                params = mapOf(
                    "part" to "snippet",
                    "id" to broadcastId
                )
            )
            json.optJSONArray("items")
                ?.optJSONObject(0)
                ?.optJSONObject("snippet")
                ?.optString("liveChatId")
                ?.takeIf { it.isNotBlank() }
                ?: throw YoutubeApiException(404, "No live chat found for this broadcast")
        }

    suspend fun listLiveChatMessages(
        accessToken: String,
        liveChatId: String,
        pageToken: String
    ): YoutubeLiveChatPage =
        withContext(Dispatchers.IO) {
            val params = buildMap {
                put("part", "id,snippet,authorDetails")
                put("liveChatId", liveChatId)
                put("maxResults", "200")
                if (pageToken.isNotBlank()) put("pageToken", pageToken)
            }
            val json = getJson(
                accessToken = accessToken,
                endpoint = "https://www.googleapis.com/youtube/v3/liveChat/messages",
                params = params
            )
            val items = json.optJSONArray("items")
            val messages = buildList {
                if (items != null) {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val snippet = item.optJSONObject("snippet") ?: JSONObject()
                        val author = item.optJSONObject("authorDetails") ?: JSONObject()
                        val text = snippet.optString("displayMessage")
                            .ifBlank { snippet.optJSONObject("textMessageDetails")?.optString("messageText").orEmpty() }
                            .trim()
                        if (text.isBlank()) continue
                        add(
                            YoutubeLiveChatMessage(
                                id = item.optString("id"),
                                author = author.optString("displayName").ifBlank { "Viewer" },
                                text = text,
                                timestampMs = snippet.optString("publishedAt").toEpochMillisOrNow()
                            )
                        )
                    }
                }
            }
            YoutubeLiveChatPage(
                messages = messages,
                nextPageToken = json.optString("nextPageToken"),
                pollingIntervalMillis = json.optInt("pollingIntervalMillis", 5_000)
            )
        }

    suspend fun getStreamStatus(accessToken: String, streamId: String): YoutubeStreamStatus =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken = accessToken,
                endpoint = "https://www.googleapis.com/youtube/v3/liveStreams",
                params = mapOf(
                    "part" to "status",
                    "id" to streamId
                )
            )
            val status = json.optJSONArray("items")
                ?.optJSONObject(0)
                ?.optJSONObject("status")
                ?: throw YoutubeApiException(404, "YouTube stream not found")
            YoutubeStreamStatus(
                streamStatus = status.optString("streamStatus"),
                healthStatus = status.optJSONObject("healthStatus")?.optString("status").orEmpty()
            )
        }

    suspend fun transitionBroadcastLive(accessToken: String, broadcastId: String): YoutubeBroadcastStatus =
        transitionBroadcast(accessToken, broadcastId, "live")

    suspend fun transitionBroadcastComplete(accessToken: String, broadcastId: String): YoutubeBroadcastStatus =
        transitionBroadcast(accessToken, broadcastId, "complete")

    private suspend fun transitionBroadcast(
        accessToken: String,
        broadcastId: String,
        broadcastStatus: String
    ): YoutubeBroadcastStatus =
        withContext(Dispatchers.IO) {
            val json = postJson(
                accessToken = accessToken,
                endpoint = "https://www.googleapis.com/youtube/v3/liveBroadcasts/transition",
                params = mapOf(
                    "part" to "id,status",
                    "id" to broadcastId,
                    "broadcastStatus" to broadcastStatus
                ),
                body = null
            )
            YoutubeBroadcastStatus(
                id = json.optString("id"),
                lifeCycleStatus = json.optJSONObject("status")?.optString("lifeCycleStatus").orEmpty()
            )
        }

    private fun createBroadcast(accessToken: String, request: YoutubeLiveRequest): BroadcastRef {
        val body = JSONObject()
            .put(
                "snippet",
                JSONObject()
                    .put("title", request.title)
                    .put("description", request.description)
                    .put("scheduledStartTime", Instant.now().plusSeconds(60).toString())
            )
            .put(
                "status",
                JSONObject()
                    .put("privacyStatus", request.privacy.apiValue)
                    .put("selfDeclaredMadeForKids", false)
            )
            .put(
                "contentDetails",
                JSONObject()
                    .put("enableAutoStart", true)
                    .put("enableAutoStop", true)
                    .put("enableDvr", true)
            )
        val json = postJson(
            accessToken = accessToken,
            endpoint = "https://www.googleapis.com/youtube/v3/liveBroadcasts",
            params = mapOf("part" to "snippet,status,contentDetails"),
            body = body
        )
        return BroadcastRef(id = json.getString("id"))
    }

    private fun createStream(accessToken: String, title: String, preset: VideoPreset): StreamRef {
        val body = JSONObject()
            .put(
                "snippet",
                JSONObject()
                    .put("title", "$title stream")
                    .put("description", "Rokid Live Studio")
            )
            .put(
                "cdn",
                JSONObject()
                    .put("ingestionType", "rtmp")
                    .put("resolution", youtubeResolution(preset))
                    .put("frameRate", youtubeFrameRate(preset))
            )
        val json = postJson(
            accessToken = accessToken,
            endpoint = "https://www.googleapis.com/youtube/v3/liveStreams",
            params = mapOf("part" to "snippet,cdn"),
            body = body
        )
        val ingestion = json.getJSONObject("cdn").getJSONObject("ingestionInfo")
        return StreamRef(
            id = json.getString("id"),
            streamName = ingestion.getString("streamName"),
            ingestionAddress = ingestion.optString("ingestionAddress"),
            rtmpsIngestionAddress = ingestion.optString("rtmpsIngestionAddress")
        )
    }

    private fun bindBroadcast(accessToken: String, broadcastId: String, streamId: String) {
        postJson(
            accessToken = accessToken,
            endpoint = "https://www.googleapis.com/youtube/v3/liveBroadcasts/bind",
            params = mapOf(
                "part" to "id,contentDetails",
                "id" to broadcastId,
                "streamId" to streamId
            ),
            body = null
        )
    }

    private fun updateVideoMetadata(accessToken: String, broadcastId: String, request: YoutubeLiveRequest) {
        val body = JSONObject()
            .put("id", broadcastId)
            .put(
                "snippet",
                JSONObject()
                    .put("title", request.title)
                    .put("description", request.description)
                    .put("categoryId", request.category.id)
            )
        requestJson(
            method = "PUT",
            accessToken = accessToken,
            endpoint = "https://www.googleapis.com/youtube/v3/videos",
            params = mapOf("part" to "snippet"),
            body = body
        )
    }

    private fun postJson(
        accessToken: String,
        endpoint: String,
        params: Map<String, String>,
        body: JSONObject?
    ): JSONObject = requestJson("POST", accessToken, endpoint, params, body)

    private fun getJson(
        accessToken: String,
        endpoint: String,
        params: Map<String, String>
    ): JSONObject = requestJson("GET", accessToken, endpoint, params, null)

    private fun requestJson(
        method: String,
        accessToken: String,
        endpoint: String,
        params: Map<String, String>,
        body: JSONObject?
    ): JSONObject {
        val url = URL("$endpoint?${params.toQueryString()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
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
        if (code !in 200..299) {
            throw YoutubeApiException(code, youtubeErrorMessage(response))
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
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

    private fun youtubeErrorMessage(response: String): String {
        if (response.isBlank()) return "YouTube API request failed"
        return runCatching {
            val error = JSONObject(response).getJSONObject("error")
            val message = error.optString("message").ifBlank { "YouTube API request failed" }
            val reason = error.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
            if (reason.isBlank()) message else "$message ($reason)"
        }.getOrElse { response }
    }

    private fun youtubeResolution(preset: VideoPreset): String =
        when (min(preset.width, preset.height)) {
            in 1080..Int.MAX_VALUE -> "1080p"
            in 720 until 1080 -> "720p"
            in 480 until 720 -> "480p"
            else -> "variable"
        }

    private fun youtubeFrameRate(preset: VideoPreset): String =
        if (preset.fps >= 60) "60fps" else "30fps"

    private fun String.toEpochMillisOrNow(): Long =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrElse { System.currentTimeMillis() }

    private fun Map<String, String>.toQueryString(): String =
        entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class BroadcastRef(val id: String)

    private data class StreamRef(
        val id: String,
        val streamName: String,
        val ingestionAddress: String,
        val rtmpsIngestionAddress: String
    )
}

class YoutubeApiException(
    val statusCode: Int,
    message: String
) : Exception("HTTP $statusCode: $message")
