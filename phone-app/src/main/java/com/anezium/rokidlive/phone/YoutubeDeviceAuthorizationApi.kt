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

data class YoutubeDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

data class YoutubeDeviceTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val scope: String
)

class YoutubeDeviceAuthorizationApi {
    suspend fun requestDeviceCode(clientId: String): YoutubeDeviceCode =
        withContext(Dispatchers.IO) {
            val json = postForm(
                endpoint = DEVICE_CODE_ENDPOINT,
                params = mapOf(
                    "client_id" to clientId,
                    "scope" to YoutubeAuthorizationManager.YOUTUBE_SCOPE
                )
            )
            YoutubeDeviceCode(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = json.optString("verification_url").ifBlank {
                    json.optString("verification_uri").ifBlank { "https://www.google.com/device" }
                },
                expiresInSeconds = json.optInt("expires_in", 1800),
                intervalSeconds = json.optInt("interval", 5)
            )
        }

    suspend fun pollForTokens(
        clientId: String,
        clientSecret: String,
        deviceCode: String
    ): YoutubeDeviceTokens =
        withContext(Dispatchers.IO) {
            val params = buildMap {
                put("client_id", clientId)
                if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
                put("device_code", deviceCode)
                put("grant_type", DEVICE_GRANT_TYPE)
            }
            postTokenForm(params).toTokens()
        }

    suspend fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): YoutubeDeviceTokens =
        withContext(Dispatchers.IO) {
            val params = buildMap {
                put("client_id", clientId)
                if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
                put("refresh_token", refreshToken)
                put("grant_type", "refresh_token")
            }
            postTokenForm(params).toTokens(fallbackRefreshToken = refreshToken)
        }

    suspend fun revoke(token: String) {
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            val connection = (URL(REVOKE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(mapOf("token" to token).toFormBody())
            }
            connection.responseCode
            connection.disconnect()
        }
    }

    private fun postTokenForm(params: Map<String, String>): JSONObject {
        val response = postFormResponse(TOKEN_ENDPOINT, params)
        val json = if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        if (response.statusCode in 200..299) return json
        val error = json.optString("error").ifBlank { "youtube_device_auth_failed" }
        val description = json.optString("error_description")
        throw YoutubeDeviceAuthException(error, description)
    }

    private fun postForm(endpoint: String, params: Map<String, String>): JSONObject {
        val response = postFormResponse(endpoint, params)
        val json = if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        if (response.statusCode !in 200..299) {
            val error = json.optString("error").ifBlank { "youtube_device_auth_failed" }
            val description = json.optString("error_description").ifBlank { response.body }
            throw YoutubeDeviceAuthException(error, description)
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

    private fun JSONObject.toTokens(fallbackRefreshToken: String = ""): YoutubeDeviceTokens =
        YoutubeDeviceTokens(
            accessToken = getString("access_token"),
            refreshToken = optString("refresh_token").ifBlank { fallbackRefreshToken },
            expiresInSeconds = optInt("expires_in", 3600),
            scope = optString("scope")
        )

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
        private const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
        private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

class YoutubeDeviceAuthException(
    val errorCode: String,
    description: String = ""
) : Exception(if (description.isBlank()) errorCode else "$errorCode: $description")
