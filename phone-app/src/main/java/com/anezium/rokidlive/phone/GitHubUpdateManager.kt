package com.anezium.rokidlive.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class InstalledAppVersion(
    val versionName: String,
    val versionCode: Long
)

data class GitHubReleaseUpdate(
    val tagName: String,
    val versionName: String,
    val versionCode: Long?,
    val title: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String
) {
    fun isNewerThan(installed: InstalledAppVersion): Boolean {
        versionCode?.let { code ->
            if (installed.versionCode > 0L) return code > installed.versionCode
        }
        val versionCompare = compareVersions(versionName, installed.versionName)
        if (versionCompare != 0) return versionCompare > 0
        return tagName.isNotBlank() && tagName != installed.versionName && tagName != "v${installed.versionName}"
    }
}

data class AppUpdateUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0L,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val available: Boolean = false,
    val latestTag: String = "",
    val latestVersionName: String = "",
    val latestVersionCode: Long? = null,
    val releaseUrl: String = "",
    val releaseNotes: String = "",
    val apkName: String = "",
    val apkUrl: String = "",
    val apkPath: String = "",
    val status: String = ""
)

class GitHubUpdateManager(private val context: Context) {
    suspend fun fetchLatestRelease(): GitHubReleaseUpdate =
        withContext(Dispatchers.IO) {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            val body = connection.inputStreamOrError(code).bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            if (code !in 200..299) error("GitHub releases request failed: HTTP $code")
            parseRelease(JSONObject(body))
        }

    suspend fun downloadApk(update: GitHubReleaseUpdate): File =
        withContext(Dispatchers.IO) {
            val outputDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val output = File(outputDir, update.apkName.sanitizeFileName())
            val connection = (URL(update.apkDownloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                error("APK download failed: HTTP $code")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(output).use { fileOutput ->
                    input.copyTo(fileOutput)
                }
            }
            connection.disconnect()
            output
        }

    fun installedVersion(): InstalledAppVersion {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return InstalledAppVersion(
            versionName = info.versionName.orEmpty().ifBlank { "0.0.0" },
            versionCode = info.longVersionCode
        )
    }

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun parseRelease(json: JSONObject): GitHubReleaseUpdate {
        val tagName = json.optString("tag_name")
        val body = json.optString("body")
        val assets = json.optJSONArray("assets") ?: error("Release has no assets")
        val apkAsset = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) add(asset)
            }
        }.sortedWith(compareByDescending<JSONObject> { asset ->
            val name = asset.optString("name")
            name.contains("phone", ignoreCase = true) ||
                name.contains("rokid-live-studio", ignoreCase = true)
        }.thenBy { asset ->
            asset.optString("name").contains("helper", ignoreCase = true) ||
                asset.optString("name").contains("glasses", ignoreCase = true)
        }).firstOrNull() ?: error("Latest release has no APK asset")

        val apkName = apkAsset.optString("name").ifBlank { "rokid-live-studio.apk" }
        val versionName = normalizeVersionName(tagName)
        return GitHubReleaseUpdate(
            tagName = tagName,
            versionName = versionName,
            versionCode = extractVersionCode(tagName, body, apkName),
            title = json.optString("name").ifBlank { tagName },
            releaseUrl = json.optString("html_url"),
            releaseNotes = body,
            apkName = apkName,
            apkDownloadUrl = apkAsset.getString("browser_download_url")
        )
    }

    private fun HttpURLConnection.inputStreamOrError(code: Int) =
        if (code in 200..299) inputStream else errorStream ?: inputStream

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[^A-Za-z0-9._-]"""), "_")

    companion object {
        private const val OWNER = "Anezium"
        private const val REPO = "Rokid-Live-Studio"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val USER_AGENT = "RokidLiveStudio-Android"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

private fun normalizeVersionName(raw: String): String =
    raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore("+")
        .substringBefore(" ")

private fun extractVersionCode(vararg values: String): Long? {
    val patterns = listOf(
        Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\bvc[_-]?(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\+(\d+)\b""")
    )
    values.forEach { value ->
        patterns.forEach { pattern ->
            pattern.find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        }
    }
    return null
}

private fun compareVersions(candidate: String, installed: String): Int {
    val candidateParts = candidate.versionParts()
    val installedParts = installed.versionParts()
    val count = maxOf(candidateParts.size, installedParts.size)
    for (index in 0 until count) {
        val left = candidateParts.getOrElse(index) { 0 }
        val right = installedParts.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}

private fun String.versionParts(): List<Int> =
    normalizeVersionName(this)
        .split(".", "-", "_")
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
