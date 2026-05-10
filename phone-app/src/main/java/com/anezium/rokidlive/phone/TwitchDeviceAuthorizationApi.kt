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

data class TwitchDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

data class TwitchDeviceTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val scope: String
)

class TwitchDeviceAuthorizationApi {
    suspend fun requestDeviceCode(clientId: String): TwitchDeviceCode =
        withContext(Dispatchers.IO) {
            val json = postForm(
                endpoint = DEVICE_CODE_ENDPOINT,
                params = mapOf(
                    "client_id" to clientId,
                    "scopes" to TWITCH_SCOPE
                )
            )
            TwitchDeviceCode(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = json.optString("verification_uri").ifBlank {
                    json.optString("verification_url").ifBlank { "https://www.twitch.tv/activate" }
                },
                expiresInSeconds = json.optInt("expires_in", 1800),
                intervalSeconds = json.optInt("interval", 5)
            )
        }

    suspend fun pollForTokens(clientId: String, deviceCode: String): TwitchDeviceTokens =
        withContext(Dispatchers.IO) {
            postTokenForm(
                mapOf(
                    "client_id" to clientId,
                    "scope" to TWITCH_SCOPE,
                    "device_code" to deviceCode,
                    "grant_type" to DEVICE_GRANT_TYPE
                )
            ).toTokens()
        }

    suspend fun refreshAccessToken(clientId: String, refreshToken: String): TwitchDeviceTokens =
        withContext(Dispatchers.IO) {
            postTokenForm(
                mapOf(
                    "client_id" to clientId,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token"
                )
            ).toTokens(fallbackRefreshToken = refreshToken)
        }

    suspend fun revoke(clientId: String, token: String) {
        if (clientId.isBlank() || token.isBlank()) return
        withContext(Dispatchers.IO) {
            val connection = (URL(REVOKE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(mapOf("client_id" to clientId, "token" to token).toFormBody())
            }
            connection.responseCode
            connection.disconnect()
        }
    }

    private fun postTokenForm(params: Map<String, String>): JSONObject {
        val response = postFormResponse(TOKEN_ENDPOINT, params)
        val json = if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        if (response.statusCode in 200..299) return json
        val error = json.optString("message").ifBlank {
            json.optString("error").ifBlank { "twitch_device_auth_failed" }
        }
        throw TwitchDeviceAuthException(json.optString("error").ifBlank { error }, error)
    }

    private fun postForm(endpoint: String, params: Map<String, String>): JSONObject {
        val response = postFormResponse(endpoint, params)
        val json = if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        if (response.statusCode !in 200..299) {
            val error = json.optString("message").ifBlank {
                json.optString("error").ifBlank { "twitch_device_auth_failed" }
            }
            throw TwitchDeviceAuthException(json.optString("error").ifBlank { error }, error)
        }
        return json
    }

    private fun postFormResponse(endpoint: String, params: Map<String, String>): FormResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(params.toFormBody()) }
        val status = connection.responseCode
        val body = connection.readResponse(status)
        connection.disconnect()
        return FormResponse(status, body)
    }

    private fun JSONObject.toTokens(fallbackRefreshToken: String = ""): TwitchDeviceTokens =
        TwitchDeviceTokens(
            accessToken = getString("access_token"),
            refreshToken = optString("refresh_token").ifBlank { fallbackRefreshToken },
            expiresInSeconds = optInt("expires_in", 3600),
            scope = optJSONArray("scope")?.joinValues().orEmpty().ifBlank { optString("scope") }
        )

    private fun org.json.JSONArray.joinValues(): String =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString(" ")

    private fun HttpURLConnection.readResponse(code: Int): String {
        val stream = if (code in 200..299) inputStream else errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            buildString {
                while (true) {
                    append(reader.readLine() ?: break)
                }
            }
        }
    }

    private fun Map<String, String>.toFormBody(): String =
        entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class FormResponse(val statusCode: Int, val body: String)

    companion object {
        const val TWITCH_SCOPE = "channel:read:stream_key channel:manage:broadcast user:read:chat"
        private const val DEVICE_CODE_ENDPOINT = "https://id.twitch.tv/oauth2/device"
        private const val TOKEN_ENDPOINT = "https://id.twitch.tv/oauth2/token"
        private const val REVOKE_ENDPOINT = "https://id.twitch.tv/oauth2/revoke"
        private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

class TwitchDeviceAuthException(
    val errorCode: String,
    description: String = ""
) : Exception(if (description.isBlank()) errorCode else "$errorCode: $description")
