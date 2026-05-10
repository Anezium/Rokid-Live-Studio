package com.anezium.rokidlive.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.anezium.rokidlive.shared.ChatOverlayMessage
import com.anezium.rokidlive.shared.ControlMessage
import com.anezium.rokidlive.shared.JsonProtocol
import com.anezium.rokidlive.shared.Protocol
import com.anezium.rokidlive.shared.StartP2pConfig
import com.anezium.rokidlive.shared.StartReverseStreamConfig
import com.anezium.rokidlive.shared.StartStreamConfig
import com.anezium.rokidlive.shared.StatusMessage
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import java.io.File

class CxrPhoneController(
    private val context: Context,
    private val onAuthorized: (Boolean, String) -> Unit,
    private val onConnectionChanged: (cxr: Boolean, bt: Boolean) -> Unit,
    private val onHelperStatus: (StatusMessage) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private var cxrConnected = false
    private var btConnected = false
    private var token: String = ""
    private var link: CXRLink? = null
    private var pendingInstallApk: File? = null
    private var installStarted = false

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            onLog("CXR-L service connected: $connected")
            onConnectionChanged(cxrConnected, btConnected)
            maybeUploadPendingHelper()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            btConnected = connected
            onLog("Glasses Bluetooth connected: $connected")
            onConnectionChanged(cxrConnected, btConnected)
            maybeUploadPendingHelper()
        }

        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
    }

    private val commandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != Protocol.STATUS_CHANNEL || payload == null) return
            runCatching {
                val caps = Caps.fromBytes(payload)
                val raw = caps.readStringPairPayload() ?: return
                onHelperStatus(JsonProtocol.decodeStatus(raw))
            }.onFailure {
                onError("Failed to parse helper status", it)
            }
        }
    }

    private val appCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            installStarted = false
            pendingInstallApk?.delete()
            pendingInstallApk = null
            onLog("Helper install: $success")
        }
        override fun onUnInstallAppResult(success: Boolean) = onLog("Helper uninstall: $success")
        override fun onOpenAppResult(success: Boolean) = onLog("Helper open: $success")
        override fun onStopAppResult(success: Boolean) = onLog("Helper stop: $success")
        override fun onGlassAppResume(resumed: Boolean) = onLog("Helper resumed: $resumed")
        override fun onQueryAppResult(installed: Boolean) = onLog("Helper installed: $installed")
    }

    fun isRequiredRokidAppInstalled(activity: Activity): Boolean =
        isGlobalHiRokidInstalled(activity)

    fun requestAuthorization(activity: Activity, requestCode: Int) {
        if (!isGlobalHiRokidInstalled(activity)) {
            onError("Global Hi Rokid is not installed", null)
            return
        }
        runCatching {
            val intent = Intent().setComponent(ComponentName(GLOBAL_AI_APP_PACKAGE, AUTH_ACTIVITY_CLASS))
            activity.startActivityForResult(intent, requestCode)
        }.recoverCatching {
            val fallback = Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE)
            activity.startActivityForResult(fallback, requestCode)
        }.onSuccess {
            onLog("Hi Rokid authorization opened")
        }.onFailure {
            onError("Failed to open Hi Rokid authorization", it)
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        val result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        when (result) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                onAuthorized(true, result.token)
            }
            is AuthResult.AuthFail -> onAuthorized(false, "")
            else -> onAuthorized(false, "")
        }
    }

    fun connect() {
        if (token.isBlank()) {
            onError("Missing CXR authorization token", null)
            return
        }
        if (!isWifiEnabled()) {
            onError("Turn on phone Wi-Fi first. Hi Rokid needs it for CXR-L install.", null)
            return
        }
        val nextLink = CXRLink(context).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    Protocol.HELPER_PACKAGE
                )
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(commandCallback)
        }
        link = nextLink
        cxrConnected = false
        btConnected = false
        onConnectionChanged(false, false)
        val bound = bindGlobalHiRokidService(nextLink, token)
        if (bound) {
            onLog("Binding to global Hi Rokid service...")
        } else {
            onError("CXR-L service bind failed. Open/force-close Hi Rokid, then retry.", null)
        }
    }

    fun installHelper() {
        if (link == null) {
            connect()
        }
        val apk = extractBundledHelperApk() ?: helperApkCandidates().firstOrNull { it.exists() && it.isFile }
        if (apk == null) {
            onError("Helper APK not found. Put glasses-helper-debug.apk in app files, Download, or DCIM/Rokid.", null)
            return
        }
        val packageName = readPackageName(apk)
        if (packageName != Protocol.HELPER_PACKAGE) {
            onError("Bundled helper package mismatch: $packageName", null)
            return
        }
        pendingInstallApk = apk
        installStarted = false
        onLog("Helper staged. Waiting CXR-L + BT before upload...")
        maybeUploadPendingHelper()
    }

    fun launchHelper() {
        link?.appStart(Protocol.HELPER_MAIN_ACTIVITY, appCallback)
            ?: onError("CXR is not connected", null)
    }

    fun stopHelper() {
        link?.appStop(appCallback)
            ?: onError("CXR is not connected", null)
    }

    fun sendStartStream(config: StartStreamConfig) {
        sendControl(ControlMessage.StartStream(config))
    }

    fun sendStartP2p(config: StartP2pConfig) {
        sendControl(ControlMessage.StartP2p(config))
    }

    fun sendStopP2p() {
        sendControl(ControlMessage.StopP2p)
    }

    fun sendStartReverseStream(config: StartReverseStreamConfig) {
        sendControl(ControlMessage.StartReverseStream(config))
    }

    fun sendStopStream() {
        sendControl(ControlMessage.StopStream)
    }

    fun requestKeyFrame() {
        sendControl(ControlMessage.RequestKeyframe)
    }

    fun sendChatMessages(messages: List<ChatOverlayMessage>) {
        sendControl(ControlMessage.SetChatMessages(messages))
    }

    fun sendChatStyle(fontSizeSp: Int, maxMessages: Int) {
        sendControl(ControlMessage.SetChatStyle(fontSizeSp, maxMessages))
    }

    private fun sendControl(message: ControlMessage) {
        val raw = JsonProtocol.encodeControl(message)
        val caps = Caps().apply {
            write("json")
            write(raw)
        }
        val result = link?.sendCustomCmd(Protocol.CONTROL_CHANNEL, caps.serialize())
        Log.d(TAG, "sendControl ${message.type}: $result")
        onLog("Sent ${message.type}")
    }

    private fun helperApkCandidates(): List<File> {
        val appContext = context.applicationContext
        return listOfNotNull(
            appContext.getExternalFilesDir(null)?.resolve("glasses-helper-debug.apk"),
            appContext.filesDir.resolve("glasses-helper-debug.apk"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "glasses-helper-debug.apk"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Rokid/glasses-helper-debug.apk")
        )
    }

    private fun extractBundledHelperApk(): File? {
        val dir = File(context.cacheDir, "cxrl-upload").apply { mkdirs() }
        val output = dir.resolve("glasses-helper-${System.currentTimeMillis()}.apk")
        return runCatching {
            context.assets.open("glasses-helper-debug.apk").use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
            output
        }.getOrNull()
    }

    private fun maybeUploadPendingHelper() {
        val apk = pendingInstallApk ?: return
        val activeLink = link ?: return
        if (installStarted || !cxrConnected || !btConnected) return
        installStarted = true
        onLog("Uploading helper APK through Hi Rokid...")
        activeLink.appUploadAndInstall(apk.absolutePath, appCallback)
    }

    private fun bindGlobalHiRokidService(link: CXRLink, authToken: String): Boolean {
        return runCatching {
            val connection = findServiceConnection(link)
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(GLOBAL_AI_APP_PACKAGE)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            onError("Reflection bind failed", it)
            false
        }
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection {
        var type: Class<*>? = link.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { field ->
                ServiceConnection::class.java.isAssignableFrom(field.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(link) as ServiceConnection
            }
            type = type.superclass
        }
        error("CXR-L ServiceConnection field not found")
    }

    private fun isGlobalHiRokidInstalled(activity: Activity): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(
                    GLOBAL_AI_APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0)
            }
        }.isSuccess
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun readPackageName(apkFile: File): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES
        )
        return info?.packageName?.takeIf { it.isNotBlank() }
            ?: error("Cannot read helper APK package name")
    }

    private fun Caps.readStringPairPayload(): String? {
        if (size() == 0) return null
        if (size() == 1) return at(0).string
        return at(1).string ?: at(0).string
    }

    companion object {
        const val AUTH_REQUEST_CODE = 2001
        private const val TAG = "RLS-CXRPhone"
        private const val GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
    }
}
