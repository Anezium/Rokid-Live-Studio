package com.anezium.rokidlive.phone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Surface as AndroidSurface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anezium.rokidlive.shared.ChatOverlayMessage
import com.anezium.rokidlive.shared.NetworkAddresses
import com.anezium.rokidlive.shared.Protocol
import com.anezium.rokidlive.shared.StartP2pConfig
import com.anezium.rokidlive.shared.StatusMessage
import com.anezium.rokidlive.shared.StatusType
import com.anezium.rokidlive.shared.VideoPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(PhoneUiState())
    private var ingressServer: MediaIngressServer? = null
    private var reverseClient: ReverseMediaClient? = null
    private var p2pConnector: PhoneP2pConnector? = null
    private var lastVideoConfig: ByteArray? = null
    private var p2pAutoStream = false
    private var reverseAttempted = false
    private var reversePortCursor = (System.currentTimeMillis() % REVERSE_PORT_SPREAD.toLong()).toInt()
    private lateinit var decoder: VideoPreviewDecoder
    private lateinit var cxr: CxrPhoneController
    private lateinit var youtubePublisher: YoutubeRtmpPublisher
    private lateinit var youtubeNetworkSelector: YoutubeNetworkSelector
    private lateinit var youtubeAuth: YoutubeAuthorizationManager
    private lateinit var youtubeDeviceAuthApi: YoutubeDeviceAuthorizationApi
    private lateinit var youtubeLiveApi: YoutubeLiveApi
    private lateinit var twitchPublisher: YoutubeRtmpPublisher
    private lateinit var twitchDeviceAuthApi: TwitchDeviceAuthorizationApi
    private lateinit var twitchApi: TwitchApi
    private lateinit var customPublisher: YoutubeRtmpPublisher
    private lateinit var updateManager: GitHubUpdateManager
    private lateinit var preferences: SharedPreferences
    private lateinit var secretStore: SecretStore
    private var youtubeDeviceAuthJob: Job? = null
    private var youtubeGoLiveJob: Job? = null
    private var youtubeCompleteJob: Job? = null
    private var youtubeChatJob: Job? = null
    private var youtubeChatStopRequested = false
    private var youtubeVideoTranscoder: YoutubeVideoRotationTranscoder? = null
    private var twitchDeviceAuthJob: Job? = null
    private var twitchChatClient: TwitchChatClient? = null
    private var twitchChatStopRequested = false
    private var twitchVideoTranscoder: YoutubeVideoRotationTranscoder? = null
    private var customChatClient: TwitchAnonymousChatClient? = null
    private var customChatStopRequested = false
    private var customVideoTranscoder: YoutubeVideoRotationTranscoder? = null
    private var youtubeDeviceAccessToken: String = ""
    private var youtubeDeviceRefreshToken: String = ""
    private var youtubeDeviceAccessTokenExpiresAtMs: Long = 0L
    private var twitchDeviceAccessToken: String = ""
    private var twitchDeviceRefreshToken: String = ""
    private var twitchDeviceAccessTokenExpiresAtMs: Long = 0L
    private var connectAfterAuthorization = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 7, 6)
        window.navigationBarColor = android.graphics.Color.rgb(5, 7, 6)
        preferences = getSharedPreferences("rokid_live_studio", MODE_PRIVATE)
        secretStore = SecretStore(preferences)
        updateManager = GitHubUpdateManager(this)
        val installedVersion = updateManager.installedVersion()
        youtubeDeviceRefreshToken = secretStore.getString(PREF_YOUTUBE_DEVICE_REFRESH_TOKEN)
        twitchDeviceRefreshToken = secretStore.getString(PREF_TWITCH_DEVICE_REFRESH_TOKEN)
        val selectedPreset = VideoPreset.fromName(preferences.getString(PREF_VIDEO_PRESET, "").orEmpty())
        val phoneIp = NetworkAddresses.firstIpv4Address().orEmpty()
        uiState = uiState.copy(
            phoneIp = phoneIp,
            update = AppUpdateUiState(
                currentVersionName = installedVersion.versionName,
                currentVersionCode = installedVersion.versionCode,
                status = "Current version ${installedVersion.versionName} (${installedVersion.versionCode})"
            ),
            sessionToken = UUID.randomUUID().toString(),
            selectedPreset = selectedPreset,
            previewRotationDegrees = loadPreviewRotation(selectedPreset),
            youtubeStreamKey = secretStore.getString(PREF_YOUTUBE_STREAM_KEY),
            youtubeTitle = preferences.getString(PREF_YOUTUBE_TITLE, defaultYoutubeTitle()).orEmpty(),
            youtubeDescription = preferences.getString(PREF_YOUTUBE_DESCRIPTION, "").orEmpty(),
            youtubePrivacy = YoutubePrivacy.fromName(preferences.getString(PREF_YOUTUBE_PRIVACY, "").orEmpty()),
            youtubeCategoryId = preferences.getString(PREF_YOUTUBE_CATEGORY_ID, YoutubeCategory.Default.id).orEmpty()
                .ifBlank { YoutubeCategory.Default.id },
            youtubeDeviceClientId = preferences.getString(PREF_YOUTUBE_DEVICE_CLIENT_ID, "").orEmpty(),
            youtubeDeviceClientSecret = secretStore.getString(PREF_YOUTUBE_DEVICE_CLIENT_SECRET),
            youtubeConnected = youtubeDeviceRefreshToken.isNotBlank(),
            youtubeAccount = if (youtubeDeviceRefreshToken.isBlank()) "" else "device linked",
            youtubeBroadcastId = preferences.getString(PREF_YOUTUBE_BROADCAST_ID, "").orEmpty(),
            youtubeStreamId = preferences.getString(PREF_YOUTUBE_STREAM_ID, "").orEmpty(),
            youtubeWatchUrl = preferences.getString(PREF_YOUTUBE_WATCH_URL, "").orEmpty(),
            youtubeIngestionAddress = preferences.getString(PREF_YOUTUBE_INGESTION_ADDRESS, "").orEmpty(),
            youtubeVideoBitrateOverride = preferences.getInt(PREF_YOUTUBE_VIDEO_BITRATE, 0),
            youtubeChatEnabled = preferences.getBoolean(PREF_YOUTUBE_CHAT_ENABLED, false),
            youtubeChatFontSizeSp = preferences.getInt(
                PREF_YOUTUBE_CHAT_FONT_SIZE_SP,
                Protocol.DEFAULT_CHAT_FONT_SIZE_SP
            ).coerceAtLeast(1),
            youtubeChatMaxMessages = preferences.getInt(
                PREF_YOUTUBE_CHAT_MAX_MESSAGES,
                Protocol.DEFAULT_CHAT_MAX_MESSAGES
            ).coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT),
            chatBottomOffsetDp = preferences.getInt(
                PREF_CHAT_BOTTOM_OFFSET_DP,
                Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP
            ).coerceIn(Protocol.MIN_CHAT_BOTTOM_OFFSET_DP, Protocol.MAX_CHAT_BOTTOM_OFFSET_DP),
            twitchStreamKey = secretStore.getString(PREF_TWITCH_STREAM_KEY),
            twitchTitle = preferences.getString(PREF_TWITCH_TITLE, defaultTwitchTitle()).orEmpty(),
            twitchCategoryId = preferences.getString(PREF_TWITCH_CATEGORY_ID, TwitchCategory.Default.id).orEmpty()
                .ifBlank { TwitchCategory.Default.id },
            twitchDeviceClientId = preferences.getString(PREF_TWITCH_DEVICE_CLIENT_ID, "").orEmpty(),
            twitchConnected = twitchDeviceRefreshToken.isNotBlank(),
            twitchAccount = if (twitchDeviceRefreshToken.isBlank()) "" else "device linked",
            twitchUserId = preferences.getString(PREF_TWITCH_USER_ID, "").orEmpty(),
            twitchUserLogin = preferences.getString(PREF_TWITCH_USER_LOGIN, "").orEmpty(),
            twitchChannelTitle = preferences.getString(PREF_TWITCH_CHANNEL_TITLE, "").orEmpty(),
            twitchIngestServerUrl = preferences.getString(PREF_TWITCH_INGEST_SERVER_URL, TwitchApi.DEFAULT_INGEST_SERVER)
                .orEmpty()
                .ifBlank { TwitchApi.DEFAULT_INGEST_SERVER },
            twitchVideoBitrateOverride = preferences.getInt(PREF_TWITCH_VIDEO_BITRATE, 0),
            twitchChatEnabled = preferences.getBoolean(PREF_TWITCH_CHAT_ENABLED, false),
            twitchChatFontSizeSp = preferences.getInt(
                PREF_TWITCH_CHAT_FONT_SIZE_SP,
                Protocol.DEFAULT_CHAT_FONT_SIZE_SP
            ).coerceAtLeast(1),
            twitchChatMaxMessages = preferences.getInt(
                PREF_TWITCH_CHAT_MAX_MESSAGES,
                Protocol.DEFAULT_CHAT_MAX_MESSAGES
            ).coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT),
            customRtmpServerUrl = preferences.getString(PREF_CUSTOM_RTMP_SERVER_URL, "").orEmpty(),
            customRtmpStreamKey = secretStore.getString(PREF_CUSTOM_RTMP_STREAM_KEY),
            customVideoBitrateOverride = preferences.getInt(PREF_CUSTOM_VIDEO_BITRATE, 0),
            customChatEnabled = preferences.getBoolean(PREF_CUSTOM_CHAT_ENABLED, false),
            customChatChannel = preferences.getString(PREF_CUSTOM_CHAT_CHANNEL, "").orEmpty(),
            customChatFontSizeSp = preferences.getInt(
                PREF_CUSTOM_CHAT_FONT_SIZE_SP,
                Protocol.DEFAULT_CHAT_FONT_SIZE_SP
            ).coerceAtLeast(1),
            customChatMaxMessages = preferences.getInt(
                PREF_CUSTOM_CHAT_MAX_MESSAGES,
                Protocol.DEFAULT_CHAT_MAX_MESSAGES
            ).coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        )
        decoder = VideoPreviewDecoder(
            initialWidth = selectedPreset.width,
            initialHeight = selectedPreset.height,
            onDroppedFrame = {
                ingressServer?.recordDroppedFrame()
                reverseClient?.recordDroppedFrame()
            },
            onError = { message, throwable -> onMain { setError(message, throwable) } }
        )
        youtubeNetworkSelector = YoutubeNetworkSelector(this)
        youtubePublisher = YoutubeRtmpPublisher(
            platformName = "YouTube",
            onStatus = { status -> onMain { uiState = uiState.copy(youtubeStatus = status, lastStatus = status) } },
            onLiveChanged = { live -> onMain { uiState = uiState.copy(youtubeLive = live) } },
            onStats = { bytes -> onMain { uiState = uiState.copy(youtubeBytesSent = bytes) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            networkBindingProvider = { youtubeNetworkSelector.select() },
            onReady = { onMain { uiState = uiState.copy(error = ""); startYoutubeBroadcastWhenRtmpReady() } },
            onVideoBackpressure = {
                onMain {
                    youtubeVideoTranscoder?.requestKeyFrame() ?: cxr.requestKeyFrame()
                }
            },
            onDiagnostics = { diagnostics ->
                onMain { uiState = uiState.copy(youtubeRtmpDiagnostics = diagnostics) }
            }
        )
        twitchPublisher = YoutubeRtmpPublisher(
            platformName = "Twitch",
            onStatus = { status -> onMain { uiState = uiState.copy(twitchStatus = status, lastStatus = status) } },
            onLiveChanged = { live -> onMain { uiState = uiState.copy(twitchLive = live) } },
            onStats = { bytes -> onMain { uiState = uiState.copy(twitchBytesSent = bytes) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            networkBindingProvider = { youtubeNetworkSelector.select() },
            onReady = { onMain { uiState = uiState.copy(twitchStatus = "Twitch stream is live", error = "") } },
            onVideoBackpressure = {
                onMain {
                    twitchVideoTranscoder?.requestKeyFrame() ?: cxr.requestKeyFrame()
                }
            },
            onDiagnostics = { diagnostics ->
                onMain { uiState = uiState.copy(twitchRtmpDiagnostics = diagnostics) }
            }
        )
        customPublisher = YoutubeRtmpPublisher(
            platformName = "Custom RTMP",
            onStatus = { status -> onMain { uiState = uiState.copy(customStatus = status, lastStatus = status) } },
            onLiveChanged = { live -> onMain { uiState = uiState.copy(customLive = live) } },
            onStats = { bytes -> onMain { uiState = uiState.copy(customBytesSent = bytes) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            networkBindingProvider = { youtubeNetworkSelector.select() },
            onReady = {
                onMain { uiState = uiState.copy(customStatus = "Custom RTMP stream is live", error = "") }
            },
            onVideoBackpressure = {
                onMain {
                    customVideoTranscoder?.requestKeyFrame() ?: cxr.requestKeyFrame()
                }
            },
            onDiagnostics = { diagnostics ->
                onMain { uiState = uiState.copy(customRtmpDiagnostics = diagnostics) }
            }
        )
        youtubeLiveApi = YoutubeLiveApi()
        youtubeDeviceAuthApi = YoutubeDeviceAuthorizationApi()
        twitchApi = TwitchApi()
        twitchDeviceAuthApi = TwitchDeviceAuthorizationApi()
        youtubeAuth = YoutubeAuthorizationManager(
            activity = this,
            onStatus = { status -> onMain { uiState = uiState.copy(youtubeStatus = status, lastStatus = status) } },
            onAuthorized = { email ->
                onMain {
                    uiState = uiState.copy(
                        youtubeConnected = true,
                        youtubeAccount = email,
                        youtubeStatus = if (email.isBlank()) "YouTube connected" else "YouTube connected: $email"
                    )
                }
            },
            onError = { message, throwable -> onMain { setError(message, throwable) } }
        )
        cxr = CxrPhoneController(
            context = this,
            onAuthorized = { authorized, _ ->
                onMain {
                    uiState = uiState.copy(authorized = authorized, error = if (authorized) "" else "Authorization failed")
                    if (authorized && connectAfterAuthorization) {
                        connectAfterAuthorization = false
                        cxr.connect()
                    } else if (!authorized) {
                        connectAfterAuthorization = false
                    }
                }
            },
            onConnectionChanged = { cxrConnected, btConnected ->
                onMain { uiState = uiState.copy(cxrConnected = cxrConnected, glassBtConnected = btConnected) }
            },
            onHelperStatus = { status ->
                onMain {
                    val helperNetworkInfo = status.networkInfo.ifBlank { uiState.helperNetworkInfo }
                    uiState = uiState.copy(
                        lastStatus = "${status.type}: ${status.message}",
                        helperNetworkInfo = helperNetworkInfo,
                        p2pStatus = if (status.type == StatusType.P2P) status.message else uiState.p2pStatus,
                        p2pPeer = status.p2pPeerSummary().ifBlank { uiState.p2pPeer },
                        p2pEndpoint = status.p2pGroupOwnerAddress.ifBlank { uiState.p2pEndpoint },
                        glassesBatteryPercent = status.batteryPercent.takeIf { it in 0..100 } ?: uiState.glassesBatteryPercent,
                        streaming = status.type == StatusType.STARTED || (uiState.streaming && status.type == StatusType.STATS),
                        framesReceived = uiState.framesReceived,
                        error = if (status.type == StatusType.ERROR) status.message else uiState.error
                    )
                    if (status.type == StatusType.P2P) {
                        maybeConnectPhoneP2p(status)
                    }
                    if (shouldTryReverse(status)) {
                        startReverseStream(helperNetworkInfo)
                    }
                }
            },
            onHelperInstallStatus = { message, busy ->
                onMain {
                    uiState = uiState.copy(
                        helperInstallStatus = message,
                        helperInstallBusy = busy,
                        lastStatus = message
                    )
                }
            },
            onLog = { log -> onMain { uiState = uiState.copy(lastStatus = log) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } }
        )
        uiState = uiState.copy(requiredRokidAppInstalled = cxr.isRequiredRokidAppInstalled(this))
        startCustomChatIfPossible()

        setContent {
            PhoneScreen(
                state = uiState,
                onSurfaceReady = { decoder.setSurface(it) },
                onSurfaceGone = { decoder.setSurface(null) },
                onConnectRokid = { connectRokid() },
                onInstallHelper = { cxr.installHelper() },
                onLaunchHelper = { cxr.launchHelper() },
                onStartP2pStream = { startP2pStream() },
                onYoutubeKeyChanged = { setYoutubeStreamKey(it) },
                onYoutubeTitleChanged = { setYoutubeTitle(it) },
                onYoutubeDescriptionChanged = { setYoutubeDescription(it) },
                onYoutubeDeviceClientIdChanged = { setYoutubeDeviceClientId(it) },
                onYoutubeDeviceClientSecretChanged = { setYoutubeDeviceClientSecret(it) },
                onYoutubePrivacySelected = { selectYoutubePrivacy(it) },
                onYoutubeCategorySelected = { selectYoutubeCategory(it) },
                onYoutubeBitrateSelected = { selectYoutubeVideoBitrate(it) },
                onYoutubeChatEnabledChange = { setYoutubeChatEnabled(it) },
                onYoutubeChatFontSizeSelected = { setYoutubeChatFontSize(it) },
                onYoutubeChatMaxMessagesSelected = { setYoutubeChatMaxMessages(it) },
                onChatBottomOffsetSelected = { setChatBottomOffset(it) },
                onConnectYoutube = { connectYoutube() },
                onStartYoutubeDeviceAuth = { startYoutubeDeviceAuth() },
                onOpenGoogleCloudDocs = { openGoogleCloudDocs() },
                onDisconnectYoutube = { disconnectYoutube() },
                onRefreshYoutubeChannel = { refreshYoutubeChannel() },
                onCreateYoutubeLive = { createYoutubeLive() },
                onTwitchKeyChanged = { setTwitchStreamKey(it) },
                onTwitchTitleChanged = { setTwitchTitle(it) },
                onTwitchDeviceClientIdChanged = { setTwitchDeviceClientId(it) },
                onTwitchCategorySelected = { selectTwitchCategory(it) },
                onTwitchBitrateSelected = { selectTwitchVideoBitrate(it) },
                onTwitchChatEnabledChange = { setTwitchChatEnabled(it) },
                onTwitchChatFontSizeSelected = { setTwitchChatFontSize(it) },
                onTwitchChatMaxMessagesSelected = { setTwitchChatMaxMessages(it) },
                onCustomRtmpServerUrlChanged = { setCustomRtmpServerUrl(it) },
                onCustomRtmpKeyChanged = { setCustomRtmpStreamKey(it) },
                onCustomBitrateSelected = { selectCustomVideoBitrate(it) },
                onCustomChatEnabledChange = { setCustomChatEnabled(it) },
                onCustomChatChannelChanged = { setCustomChatChannel(it) },
                onCustomChatFontSizeSelected = { setCustomChatFontSize(it) },
                onCustomChatMaxMessagesSelected = { setCustomChatMaxMessages(it) },
                onStartTwitchDeviceAuth = { startTwitchDeviceAuth() },
                onOpenTwitchDocs = { openTwitchDocs() },
                onDisconnectTwitch = { disconnectTwitch() },
                onRefreshTwitchChannel = { refreshTwitchChannel() },
                onStartTwitch = { startTwitchLive() },
                onStopTwitch = { stopTwitchLive() },
                onStartCustom = { startCustomLive() },
                onStopCustom = { stopCustomLive() },
                onPresetSelected = { selectPreset(it) },
                onPreviewRotationSelected = { selectPreviewRotation(it) },
                onStartYoutube = { startYoutubeLive() },
                onStopYoutube = { stopYoutubeLive() },
                onStopStream = { stopStream() },
                onOpenReleases = { openUrl("https://github.com/Anezium/Rokid-Live-Studio/releases") },
                onUpdateAction = { handleUpdateAction() },
                onRequestKeyFrame = { cxr.requestKeyFrame() }
            )
        }
    }

    @Deprecated("Rokid CXR-L SDK uses startActivityForResult for authorization.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CxrPhoneController.AUTH_REQUEST_CODE) {
            cxr.handleAuthorizationResult(resultCode, data)
        } else if (requestCode == YoutubeAuthorizationManager.REQUEST_CODE) {
            youtubeAuth.handleActivityResult(data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != P2P_PERMISSION_REQUEST) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted && p2pAutoStream) {
            startP2pStream()
        } else if (!granted) {
            setError("Wi-Fi Direct permissions denied", null)
        }
    }

    override fun onDestroy() {
        stopLiveKeepAlive()
        runCatching { cxr.sendStopStream() }
        runCatching { cxr.sendStopP2p() }
        ingressServer?.stop()
        reverseClient?.stop()
        p2pConnector?.stop()
        youtubeGoLiveJob?.cancel()
        youtubeCompleteJob?.cancel()
        twitchDeviceAuthJob?.cancel()
        stopYoutubeChat(clearHelper = true)
        stopTwitchChat(clearHelper = true)
        stopCustomChat(clearHelper = true)
        youtubeVideoTranscoder?.stop()
        twitchVideoTranscoder?.stop()
        customVideoTranscoder?.stop()
        youtubePublisher.stop()
        twitchPublisher.stop()
        customPublisher.stop()
        decoder.release()
        super.onDestroy()
    }

    private fun startReceiver() {
        startLiveKeepAlive("Receiving Rokid camera stream")
        ingressServer?.stop()
        val server = MediaIngressServer(
            port = Protocol.DEFAULT_PORT,
            expectedToken = uiState.sessionToken,
            decoder = decoder,
            onRunningChanged = { running ->
                onMain { uiState = uiState.copy(receiverRunning = running) }
            },
            onStats = { frames, bytes, dropped ->
                onMain { uiState = uiState.copy(framesReceived = frames, bytesReceived = bytes, droppedFrames = dropped) }
            },
            onVideoConfig = { payload ->
                handleIncomingVideoConfig(payload)
            },
            onVideoFrame = { payload, timestampUs, keyFrame ->
                handleIncomingVideoFrame(payload, timestampUs, keyFrame)
            },
            onAudioConfig = { payload ->
                handleIncomingAudioConfig(payload)
            },
            onAudioFrame = { payload, timestampUs ->
                handleIncomingAudioFrame(payload, timestampUs)
            },
            onError = { message, throwable -> onMain { setError(message, throwable) } }
        )
        ingressServer = server
        server.start()
    }

    private fun connectRokid() {
        if (uiState.authorized) {
            cxr.connect()
            return
        }
        connectAfterAuthorization = true
        cxr.requestAuthorization(this, CxrPhoneController.AUTH_REQUEST_CODE)
    }

    private fun handleIncomingVideoConfig(payload: ByteArray) {
        lastVideoConfig = payload
        youtubeVideoTranscoder?.configure(payload) ?: youtubePublisher.configureVideo(payload)
        twitchVideoTranscoder?.configure(payload) ?: twitchPublisher.configureVideo(payload)
        customVideoTranscoder?.configure(payload) ?: customPublisher.configureVideo(payload)
    }

    private fun handleIncomingVideoFrame(payload: ByteArray, timestampUs: Long, keyFrame: Boolean) {
        youtubeVideoTranscoder?.queueFrame(payload, timestampUs, keyFrame)
            ?: youtubePublisher.publishVideoFrame(payload, timestampUs, keyFrame)
        twitchVideoTranscoder?.queueFrame(payload, timestampUs, keyFrame)
            ?: twitchPublisher.publishVideoFrame(payload, timestampUs, keyFrame)
        customVideoTranscoder?.queueFrame(payload, timestampUs, keyFrame)
            ?: customPublisher.publishVideoFrame(payload, timestampUs, keyFrame)
    }

    private fun handleIncomingAudioConfig(payload: ByteArray) {
        youtubePublisher.configureAudio(payload)
        twitchPublisher.configureAudio(payload)
        customPublisher.configureAudio(payload)
        if (uiState.youtubeLive) {
            onMain {
                if (uiState.youtubeLive) {
                    uiState = uiState.copy(youtubeStatus = "Rokid glasses mic ready")
                }
            }
        }
        if (uiState.twitchLive) {
            onMain {
                if (uiState.twitchLive) {
                    uiState = uiState.copy(twitchStatus = "Rokid glasses mic ready")
                }
            }
        }
        if (uiState.customLive) {
            onMain {
                if (uiState.customLive) {
                    uiState = uiState.copy(customStatus = "Rokid glasses mic ready")
                }
            }
        }
    }

    private fun handleIncomingAudioFrame(payload: ByteArray, timestampUs: Long) {
        youtubePublisher.publishAudioFrame(payload, timestampUs)
        twitchPublisher.publishAudioFrame(payload, timestampUs)
        customPublisher.publishAudioFrame(payload, timestampUs)
    }

    private fun startStream() {
        val host = uiState.phoneIp.ifBlank { NetworkAddresses.firstIpv4Address().orEmpty() }
        if (host.isBlank()) {
            setError("No phone IP found. Connect phone and glasses to the same Wi-Fi, hotspot, or P2P link.", null)
            return
        }
        startStreamToHost(host)
    }

    private fun startStreamToHost(host: String) {
        reverseAttempted = false
        reverseClient?.stop()
        startLiveKeepAlive("Streaming from Rokid Glasses")
        applySelectedPreset()
        if (!uiState.receiverRunning) startReceiver()
        uiState = uiState.copy(phoneIp = host, streaming = true)
        cxr.sendStartStream(
            uiState.selectedPreset.startStreamConfig(
                host = host,
                port = Protocol.DEFAULT_PORT,
                token = uiState.sessionToken
            )
        )
    }

    private fun startReverseStream(networkInfo: String) {
        if (uiState.reverseRunning) return
        val helperHosts = parseHelperIpCandidates(networkInfo)
        if (helperHosts.isEmpty()) {
            setError("No helper IP found yet. Relaunch helper, then retry reverse stream.", null)
            return
        }
        startReverseStreamFromHosts(helperHosts)
    }

    private fun startReverseStreamFromHosts(helperHosts: List<String>) {
        if (uiState.reverseRunning) return
        reverseAttempted = true
        ingressServer?.stop()
        reverseClient?.stop()
        val reversePort = nextReversePort()
        val client = ReverseMediaClient(
            hosts = helperHosts,
            port = reversePort,
            expectedToken = uiState.sessionToken,
            decoder = decoder,
            onRunningChanged = { running ->
                onMain { uiState = uiState.copy(reverseRunning = running, streaming = running || uiState.streaming) }
            },
            onStats = { frames, bytes, dropped ->
                onMain { uiState = uiState.copy(framesReceived = frames, bytesReceived = bytes, droppedFrames = dropped) }
            },
            onVideoConfig = { payload ->
                handleIncomingVideoConfig(payload)
            },
            onVideoFrame = { payload, timestampUs, keyFrame ->
                handleIncomingVideoFrame(payload, timestampUs, keyFrame)
            },
            onAudioConfig = { payload ->
                handleIncomingAudioConfig(payload)
            },
            onAudioFrame = { payload, timestampUs ->
                handleIncomingAudioFrame(payload, timestampUs)
            },
            onLog = { log -> onMain { uiState = uiState.copy(lastStatus = log) } },
            onError = { message, throwable -> onMain { handleReverseMediaError(message, throwable) } }
        )
        reverseClient = client
        startLiveKeepAlive("Streaming from Rokid Glasses")
        uiState = uiState.copy(
            streaming = true,
            error = "",
            lastStatus = "Trying reverse media: ${helperHosts.joinToString()}:$reversePort"
        )
        cxr.sendStartReverseStream(
            uiState.selectedPreset.startReverseStreamConfig(
                port = reversePort,
                token = uiState.sessionToken
            )
        )
        client.start()
    }

    private fun nextReversePort(): Int {
        reversePortCursor = (reversePortCursor + 1) % REVERSE_PORT_SPREAD
        return Protocol.DEFAULT_REVERSE_PORT + 1 + reversePortCursor
    }

    private fun stopStream() {
        stopLiveKeepAlive()
        cxr.sendStopStream()
        cxr.sendStopP2p()
        stopYoutubeLive()
        stopTwitchLive()
        stopCustomLive()
        reverseClient?.stop()
        p2pConnector?.stop()
        p2pConnector = null
        p2pAutoStream = false
        uiState = uiState.copy(
            streaming = false,
            reverseRunning = false,
            p2pRunning = false,
            p2pConnected = false,
            p2pEndpoint = "",
            p2pStatus = "Stopped",
            youtubeLive = false,
            twitchLive = false,
            customLive = false
        )
    }

    private fun stopCameraTransport() {
        stopLiveKeepAlive()
        cxr.sendStopStream()
        cxr.sendStopP2p()
        ingressServer?.stop()
        reverseClient?.stop()
        p2pConnector?.stop()
        p2pConnector = null
        p2pAutoStream = false
        uiState = uiState.copy(
            streaming = false,
            receiverRunning = false,
            reverseRunning = false,
            p2pRunning = false,
            p2pConnected = false,
            p2pEndpoint = "",
            p2pStatus = "Stopped"
        )
    }

    private fun startP2pStream() {
        if (!ensureP2pPermissions()) return
        startLiveKeepAlive("Connecting Rokid Wi-Fi Direct stream")
        applySelectedPreset()
        p2pAutoStream = true
        reverseAttempted = true
        ingressServer?.stop()
        reverseClient?.stop()
        p2pConnector?.stop()
        p2pConnector = null
        uiState = uiState.copy(
            p2pRunning = true,
            p2pConnected = false,
            p2pStatus = "Asking helper to create Wi-Fi Direct group",
            p2pEndpoint = "",
            streaming = false,
            reverseRunning = false,
            error = ""
        )
        cxr.sendStopStream()
        cxr.sendStartP2p(StartP2pConfig(sessionId = uiState.sessionToken, preferGroupOwner = true))
    }

    private fun maybeConnectPhoneP2p(status: StatusMessage) {
        if (!p2pAutoStream || uiState.p2pConnected || p2pConnector != null) return
        val targetName = status.p2pDeviceName.takeIf { it.isNotBlank() }
        val targetAddress = status.p2pDeviceAddress.takeIf { it.isNotBlank() }
        if (targetName == null && targetAddress == null) return
        if (!ensureP2pPermissions()) return

        val connector = PhoneP2pConnector(
            context = this,
            targetDeviceName = targetName,
            targetDeviceAddress = targetAddress,
            onStatus = { message ->
                uiState = uiState.copy(p2pStatus = message, lastStatus = message)
            },
            onConnected = { connection ->
                p2pAutoStream = false
                uiState = uiState.copy(
                    p2pRunning = false,
                    p2pConnected = true,
                    p2pStatus = "Connected ${connection.role}",
                    p2pEndpoint = connection.mediaHost,
                    lastStatus = "P2P media host: ${connection.mediaHost}"
                )
                if (connection.phoneIsGroupOwner) {
                    startStreamToHost(connection.mediaHost)
                } else {
                    startReverseStreamFromHosts(listOf(connection.groupOwnerAddress))
                }
            },
            onFailure = { reason ->
                p2pConnector = null
                p2pAutoStream = false
                uiState = uiState.copy(p2pRunning = false, p2pConnected = false, p2pStatus = reason)
                if (!uiState.youtubeLive && !uiState.twitchLive && !uiState.customLive) stopLiveKeepAlive()
                setError("Wi-Fi Direct failed: $reason", null)
            }
        )
        p2pConnector = connector
        uiState = uiState.copy(p2pRunning = true, p2pStatus = "Phone joining helper Wi-Fi Direct group")
        connector.start()
    }

    private fun ensureP2pPermissions(): Boolean {
        val missing = requiredP2pPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), P2P_PERMISSION_REQUEST)
        uiState = uiState.copy(p2pStatus = "Waiting for Wi-Fi Direct permissions")
        return false
    }

    private fun selectPreset(preset: VideoPreset) {
        if (uiState.youtubeLive) {
            uiState = uiState.copy(youtubeStatus = "Stop YouTube before changing resolution")
            return
        }
        if (uiState.twitchLive) {
            uiState = uiState.copy(twitchStatus = "Stop Twitch before changing resolution")
            return
        }
        if (uiState.customLive) {
            uiState = uiState.copy(customStatus = "Stop Custom RTMP before changing resolution")
            return
        }
        if (uiState.selectedPreset == preset) return
        preferences.edit().putString(PREF_VIDEO_PRESET, preset.name).apply()
        uiState = uiState.copy(
            selectedPreset = preset,
            previewRotationDegrees = loadPreviewRotation(preset),
            youtubeStatus = "Preset: ${preset.label}",
            twitchStatus = "Preset: ${preset.label}",
            customStatus = "Preset: ${preset.label}"
        )
        applySelectedPreset()
        if (uiState.youtubeLive) {
            startOrUpdateYoutubeVideoPipeline()
            cxr.requestKeyFrame()
        }
        if (uiState.twitchLive) {
            startOrUpdateTwitchVideoPipeline()
            cxr.requestKeyFrame()
        }
        if (uiState.customLive) {
            startOrUpdateCustomVideoPipeline()
            cxr.requestKeyFrame()
        }
    }

    private fun selectPreviewRotation(degrees: Int) {
        val normalized = degrees.normalizedRotation()
        preferences.edit()
            .putInt(previewRotationPreferenceKey(uiState.selectedPreset), normalized)
            .apply()
        uiState = uiState.copy(previewRotationDegrees = normalized)
        if (uiState.youtubeLive) {
            startOrUpdateYoutubeVideoPipeline()
            cxr.requestKeyFrame()
        }
        if (uiState.twitchLive) {
            startOrUpdateTwitchVideoPipeline()
            cxr.requestKeyFrame()
        }
        if (uiState.customLive) {
            startOrUpdateCustomVideoPipeline()
            cxr.requestKeyFrame()
        }
    }

    private fun loadPreviewRotation(preset: VideoPreset): Int =
        preferences.getInt(previewRotationPreferenceKey(preset), preset.displayRotationDegrees).normalizedRotation()

    private fun previewRotationPreferenceKey(preset: VideoPreset): String =
        "${PREF_PREVIEW_ROTATION_PREFIX}${preset.name}"

    private fun applySelectedPreset() {
        val preset = uiState.selectedPreset
        decoder.setFormatHint(preset.width, preset.height)
    }

    private fun setYoutubeStreamKey(streamKey: String) {
        secretStore.putString(PREF_YOUTUBE_STREAM_KEY, streamKey)
        clearPersistedYoutubeLive()
        uiState = uiState.copy(
            youtubeStreamKey = streamKey,
            youtubeIngestionAddress = "",
            youtubeBroadcastId = "",
            youtubeStreamId = "",
            youtubeWatchUrl = ""
        )
    }

    private fun setYoutubeTitle(title: String) {
        preferences.edit().putString(PREF_YOUTUBE_TITLE, title).apply()
        uiState = uiState.copy(youtubeTitle = title)
    }

    private fun setYoutubeDescription(description: String) {
        preferences.edit().putString(PREF_YOUTUBE_DESCRIPTION, description).apply()
        uiState = uiState.copy(youtubeDescription = description)
    }

    private fun setYoutubeDeviceClientId(clientId: String) {
        preferences.edit().putString(PREF_YOUTUBE_DEVICE_CLIENT_ID, clientId).apply()
        uiState = uiState.copy(youtubeDeviceClientId = clientId)
    }

    private fun setYoutubeDeviceClientSecret(clientSecret: String) {
        secretStore.putString(PREF_YOUTUBE_DEVICE_CLIENT_SECRET, clientSecret)
        uiState = uiState.copy(youtubeDeviceClientSecret = clientSecret)
    }

    private fun selectYoutubePrivacy(privacy: YoutubePrivacy) {
        preferences.edit().putString(PREF_YOUTUBE_PRIVACY, privacy.name).apply()
        uiState = uiState.copy(youtubePrivacy = privacy, youtubeStatus = "YouTube privacy: ${privacy.label}")
    }

    private fun selectYoutubeCategory(category: YoutubeCategory) {
        preferences.edit().putString(PREF_YOUTUBE_CATEGORY_ID, category.id).apply()
        uiState = uiState.copy(
            youtubeCategoryId = category.id,
            youtubeStatus = "YouTube category: ${category.label}"
        )
    }

    private fun selectYoutubeVideoBitrate(bitrate: Int) {
        val normalized = bitrate.takeIf { it > 0 } ?: 0
        preferences.edit().putInt(PREF_YOUTUBE_VIDEO_BITRATE, normalized).apply()
        uiState = uiState.copy(
            youtubeVideoBitrateOverride = normalized,
            youtubeStatus = if (normalized > 0) {
                "YouTube bitrate: ${normalized.bitrateLabel()}"
            } else {
                "YouTube bitrate: auto"
            }
        )
    }

    private fun setYoutubeChatEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_YOUTUBE_CHAT_ENABLED, enabled).apply()
        uiState = uiState.copy(
            youtubeChatEnabled = enabled,
            youtubeChatStatus = if (enabled) {
                "Chat will appear in the glasses helper"
            } else {
                "Helper chat hidden"
            },
            youtubeChatMessages = if (enabled) uiState.youtubeChatMessages else emptyList()
        )
        if (enabled) {
            sendYoutubeChatStyle()
            startYoutubeChatIfPossible()
        } else {
            stopYoutubeChat(clearHelper = true)
        }
    }

    private fun setYoutubeChatFontSize(fontSizeSp: Int) {
        val normalized = fontSizeSp.coerceAtLeast(1)
        preferences.edit().putInt(PREF_YOUTUBE_CHAT_FONT_SIZE_SP, normalized).apply()
        uiState = uiState.copy(
            youtubeChatFontSizeSp = normalized,
            youtubeChatStatus = "Helper chat font: ${normalized}sp"
        )
        sendYoutubeChatStyle()
    }

    private fun setYoutubeChatMaxMessages(maxMessages: Int) {
        val normalized = maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        preferences.edit().putInt(PREF_YOUTUBE_CHAT_MAX_MESSAGES, normalized).apply()
        val nextMessages = uiState.youtubeChatMessages.takeLast(normalized)
        uiState = uiState.copy(
            youtubeChatMaxMessages = normalized,
            youtubeChatMessages = nextMessages,
            youtubeChatStatus = "Helper chat messages: $normalized"
        )
        sendYoutubeChatStyle()
        runCatching { cxr.sendChatMessages(nextMessages) }
    }

    private fun setChatBottomOffset(offsetDp: Int) {
        val normalized = offsetDp.coerceIn(
            Protocol.MIN_CHAT_BOTTOM_OFFSET_DP,
            Protocol.MAX_CHAT_BOTTOM_OFFSET_DP
        )
        preferences.edit().putInt(PREF_CHAT_BOTTOM_OFFSET_DP, normalized).apply()
        uiState = uiState.copy(
            chatBottomOffsetDp = normalized,
            youtubeChatStatus = "Helper chat position: ${normalized}dp",
            twitchChatStatus = "Helper chat position: ${normalized}dp",
            customChatStatus = "Helper chat position: ${normalized}dp"
        )
        when {
            uiState.youtubeLive -> sendYoutubeChatStyle()
            uiState.twitchLive -> sendTwitchChatStyle()
            uiState.customLive -> sendCustomChatStyle()
            uiState.youtubeChatEnabled -> sendYoutubeChatStyle()
            uiState.twitchChatEnabled -> sendTwitchChatStyle()
            uiState.customChatEnabled -> sendCustomChatStyle()
        }
    }

    private fun sendYoutubeChatStyle() {
        runCatching {
            cxr.sendChatStyle(
                fontSizeSp = uiState.youtubeChatFontSizeSp,
                maxMessages = uiState.youtubeChatMaxMessages,
                bottomOffsetDp = uiState.chatBottomOffsetDp
            )
        }
    }

    private fun setTwitchStreamKey(streamKey: String) {
        secretStore.putString(PREF_TWITCH_STREAM_KEY, streamKey)
        uiState = uiState.copy(twitchStreamKey = streamKey)
    }

    private fun setTwitchTitle(title: String) {
        preferences.edit().putString(PREF_TWITCH_TITLE, title).apply()
        uiState = uiState.copy(twitchTitle = title)
    }

    private fun setTwitchDeviceClientId(clientId: String) {
        preferences.edit().putString(PREF_TWITCH_DEVICE_CLIENT_ID, clientId).apply()
        uiState = uiState.copy(twitchDeviceClientId = clientId)
    }

    private fun selectTwitchCategory(category: TwitchCategory) {
        preferences.edit().putString(PREF_TWITCH_CATEGORY_ID, category.id).apply()
        uiState = uiState.copy(
            twitchCategoryId = category.id,
            twitchStatus = "Twitch category: ${category.label}"
        )
    }

    private fun selectTwitchVideoBitrate(bitrate: Int) {
        val normalized = bitrate.takeIf { it > 0 } ?: 0
        preferences.edit().putInt(PREF_TWITCH_VIDEO_BITRATE, normalized).apply()
        uiState = uiState.copy(
            twitchVideoBitrateOverride = normalized,
            twitchStatus = if (normalized > 0) {
                "Twitch bitrate: ${normalized.bitrateLabel()}"
            } else {
                "Twitch bitrate: auto"
            }
        )
    }

    private fun setTwitchChatEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_TWITCH_CHAT_ENABLED, enabled).apply()
        uiState = uiState.copy(
            twitchChatEnabled = enabled,
            twitchChatStatus = if (enabled) {
                "Chat will appear in the glasses helper"
            } else {
                "Helper chat hidden"
            },
            twitchChatMessages = if (enabled) uiState.twitchChatMessages else emptyList()
        )
        if (enabled) {
            sendTwitchChatStyle()
            startTwitchChatIfPossible()
        } else {
            stopTwitchChat(clearHelper = true)
        }
    }

    private fun setTwitchChatFontSize(fontSizeSp: Int) {
        val normalized = fontSizeSp.coerceAtLeast(1)
        preferences.edit().putInt(PREF_TWITCH_CHAT_FONT_SIZE_SP, normalized).apply()
        uiState = uiState.copy(
            twitchChatFontSizeSp = normalized,
            twitchChatStatus = "Helper chat font: ${normalized}sp"
        )
        sendTwitchChatStyle()
    }

    private fun setTwitchChatMaxMessages(maxMessages: Int) {
        val normalized = maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        preferences.edit().putInt(PREF_TWITCH_CHAT_MAX_MESSAGES, normalized).apply()
        val nextMessages = uiState.twitchChatMessages.takeLast(normalized)
        uiState = uiState.copy(
            twitchChatMaxMessages = normalized,
            twitchChatMessages = nextMessages,
            twitchChatStatus = "Helper chat messages: $normalized"
        )
        sendTwitchChatStyle()
        runCatching { cxr.sendChatMessages(nextMessages) }
    }

    private fun sendTwitchChatStyle() {
        runCatching {
            cxr.sendChatStyle(
                fontSizeSp = uiState.twitchChatFontSizeSp,
                maxMessages = uiState.twitchChatMaxMessages,
                bottomOffsetDp = uiState.chatBottomOffsetDp
            )
        }
    }

    private fun setCustomRtmpServerUrl(serverUrl: String) {
        preferences.edit().putString(PREF_CUSTOM_RTMP_SERVER_URL, serverUrl).apply()
        uiState = uiState.copy(customRtmpServerUrl = serverUrl)
    }

    private fun setCustomRtmpStreamKey(streamKey: String) {
        secretStore.putString(PREF_CUSTOM_RTMP_STREAM_KEY, streamKey)
        uiState = uiState.copy(customRtmpStreamKey = streamKey)
    }

    private fun selectCustomVideoBitrate(bitrate: Int) {
        val normalized = bitrate.takeIf { it > 0 } ?: 0
        preferences.edit().putInt(PREF_CUSTOM_VIDEO_BITRATE, normalized).apply()
        uiState = uiState.copy(
            customVideoBitrateOverride = normalized,
            customStatus = if (normalized > 0) {
                "Custom RTMP bitrate: ${normalized.bitrateLabel()}"
            } else {
                "Custom RTMP bitrate: auto"
            }
        )
    }

    private fun setCustomChatEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_CUSTOM_CHAT_ENABLED, enabled).apply()
        uiState = uiState.copy(
            customChatEnabled = enabled,
            customChatStatus = if (enabled) {
                "Connecting anonymous Twitch chat..."
            } else {
                "Helper chat hidden"
            },
            customChatMessages = if (enabled) uiState.customChatMessages else emptyList()
        )
        if (enabled) {
            sendCustomChatStyle()
            startCustomChatIfPossible()
        } else {
            stopCustomChat(clearHelper = true)
        }
    }

    private fun setCustomChatChannel(channel: String) {
        preferences.edit().putString(PREF_CUSTOM_CHAT_CHANNEL, channel).apply()
        val shouldRestart = customChatClient != null
        if (shouldRestart) stopCustomChat(clearHelper = true)
        uiState = uiState.copy(
            customChatChannel = channel,
            customChatStatus = if (channel.trim().removePrefix("#").trim().isBlank()) {
                "Enter a Twitch channel name"
            } else {
                "Twitch chat channel: ${channel.trim().removePrefix("#").trim().lowercase()}"
            }
        )
        if (uiState.customChatEnabled && channel.trim().removePrefix("#").trim().isNotBlank()) {
            startCustomChatIfPossible()
        }
    }

    private fun setCustomChatFontSize(fontSizeSp: Int) {
        val normalized = fontSizeSp.coerceAtLeast(1)
        preferences.edit().putInt(PREF_CUSTOM_CHAT_FONT_SIZE_SP, normalized).apply()
        uiState = uiState.copy(
            customChatFontSizeSp = normalized,
            customChatStatus = "Helper chat font: ${normalized}sp"
        )
        sendCustomChatStyle()
    }

    private fun setCustomChatMaxMessages(maxMessages: Int) {
        val normalized = maxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
        preferences.edit().putInt(PREF_CUSTOM_CHAT_MAX_MESSAGES, normalized).apply()
        val nextMessages = uiState.customChatMessages.takeLast(normalized)
        uiState = uiState.copy(
            customChatMaxMessages = normalized,
            customChatMessages = nextMessages,
            customChatStatus = "Helper chat messages: $normalized"
        )
        sendCustomChatStyle()
        runCatching { cxr.sendChatMessages(nextMessages) }
    }

    private fun sendCustomChatStyle() {
        runCatching {
            cxr.sendChatStyle(
                fontSizeSp = uiState.customChatFontSizeSp,
                maxMessages = uiState.customChatMaxMessages,
                bottomOffsetDp = uiState.chatBottomOffsetDp
            )
        }
    }

    private fun connectYoutube() {
        youtubeAuth.requireToken { token -> refreshYoutubeChannel(token) }
    }

    private fun startYoutubeDeviceAuth() {
        val clientId = uiState.youtubeDeviceClientId.trim()
        val clientSecret = uiState.youtubeDeviceClientSecret.trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            setError("Paste your YouTube TV/device OAuth client ID and secret first", null)
            return
        }
        youtubeDeviceAuthJob?.cancel()
        youtubeDeviceAuthJob = lifecycleScope.launch {
            runCatching {
                uiState = uiState.copy(
                    youtubeDeviceUserCode = "",
                    youtubeDeviceVerificationUrl = "",
                    youtubeDeviceAuthPending = true,
                    youtubeStatus = "Requesting YouTube device code...",
                    lastStatus = "Requesting YouTube device code...",
                    error = ""
                )
                val code = youtubeDeviceAuthApi.requestDeviceCode(clientId)
                copyToClipboard("YouTube TV code", code.userCode)
                uiState = uiState.copy(
                    youtubeDeviceUserCode = code.userCode,
                    youtubeDeviceVerificationUrl = code.verificationUrl,
                    youtubeStatus = "TV code copied. Opening Google device page...",
                    lastStatus = "YouTube device code copied: ${code.userCode}"
                )
                openUrl(code.verificationUrl)
                pollYoutubeDeviceTokens(clientId, clientSecret, code)
            }.onFailure { throwable ->
                uiState = uiState.copy(youtubeDeviceAuthPending = false)
                setError("YouTube device link failed", throwable)
            }
        }
    }

    private suspend fun pollYoutubeDeviceTokens(clientId: String, clientSecret: String, code: YoutubeDeviceCode) {
        var intervalSeconds = code.intervalSeconds.coerceAtLeast(5)
        val expiresAtMs = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < expiresAtMs) {
            delay(intervalSeconds * 1000L)
            try {
                val tokens = youtubeDeviceAuthApi.pollForTokens(clientId, clientSecret, code.deviceCode)
                storeYoutubeDeviceTokens(tokens)
                uiState = uiState.copy(
                    youtubeDeviceAuthPending = false,
                    youtubeStatus = "YouTube device linked",
                    lastStatus = "YouTube device linked"
                )
                refreshYoutubeChannel(tokens.accessToken)
                return
            } catch (exception: YoutubeDeviceAuthException) {
                when (exception.errorCode) {
                    "authorization_pending" -> {
                        uiState = uiState.copy(youtubeStatus = "Waiting for YouTube code approval: ${code.userCode}")
                    }
                    "slow_down" -> {
                        intervalSeconds += 5
                        uiState = uiState.copy(youtubeStatus = "YouTube asked to poll slower")
                    }
                    else -> throw exception
                }
            } catch (exception: IOException) {
                uiState = uiState.copy(
                    youtubeStatus = "Network unavailable while linking YouTube. Retrying: ${code.userCode}",
                    lastStatus = "YouTube device link network retry"
                )
            }
        }
        throw YoutubeDeviceAuthException("expired_token", "YouTube device code expired")
    }

    private fun openGoogleCloudDocs() {
        openUrl("https://developers.google.com/youtube/v3/guides/auth/devices")
    }

    private fun startTwitchDeviceAuth() {
        val clientId = uiState.twitchDeviceClientId.trim()
        if (clientId.isBlank()) {
            setError("Paste your Twitch OAuth client ID first", null)
            return
        }
        twitchDeviceAuthJob?.cancel()
        twitchDeviceAuthJob = lifecycleScope.launch {
            runCatching {
                uiState = uiState.copy(
                    twitchDeviceUserCode = "",
                    twitchDeviceVerificationUrl = "",
                    twitchDeviceAuthPending = true,
                    twitchStatus = "Requesting Twitch device code...",
                    lastStatus = "Requesting Twitch device code...",
                    error = ""
                )
                val code = twitchDeviceAuthApi.requestDeviceCode(clientId)
                copyToClipboard("Twitch TV code", code.userCode)
                uiState = uiState.copy(
                    twitchDeviceUserCode = code.userCode,
                    twitchDeviceVerificationUrl = code.verificationUrl,
                    twitchStatus = "TV code copied. Opening Twitch device page...",
                    lastStatus = "Twitch device code copied: ${code.userCode}"
                )
                openUrl(code.verificationUrl)
                pollTwitchDeviceTokens(clientId, code)
            }.onFailure { throwable ->
                uiState = uiState.copy(twitchDeviceAuthPending = false)
                setError("Twitch device link failed", throwable)
            }
        }
    }

    private suspend fun pollTwitchDeviceTokens(clientId: String, code: TwitchDeviceCode) {
        var intervalSeconds = code.intervalSeconds.coerceAtLeast(5)
        val expiresAtMs = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < expiresAtMs) {
            delay(intervalSeconds * 1000L)
            try {
                val tokens = twitchDeviceAuthApi.pollForTokens(clientId, code.deviceCode)
                storeTwitchDeviceTokens(tokens)
                uiState = uiState.copy(
                    twitchDeviceAuthPending = false,
                    twitchStatus = "Twitch device linked",
                    lastStatus = "Twitch device linked"
                )
                refreshTwitchChannel(tokens.accessToken)
                return
            } catch (exception: TwitchDeviceAuthException) {
                when (exception.errorCode) {
                    "authorization_pending" -> {
                        uiState = uiState.copy(twitchStatus = "Waiting for Twitch code approval: ${code.userCode}")
                    }
                    "slow_down" -> {
                        intervalSeconds += 5
                        uiState = uiState.copy(twitchStatus = "Twitch asked to poll slower")
                    }
                    else -> throw exception
                }
            } catch (exception: IOException) {
                uiState = uiState.copy(
                    twitchStatus = "Network unavailable while linking Twitch. Retrying: ${code.userCode}",
                    lastStatus = "Twitch device link network retry"
                )
            }
        }
        throw TwitchDeviceAuthException("expired_token", "Twitch device code expired")
    }

    private fun openTwitchDocs() {
        openUrl("https://dev.twitch.tv/console/apps")
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun handleUpdateAction() {
        val update = uiState.update
        if (update.checking || update.downloading) return
        if (!update.available) {
            checkForUpdates(downloadIfAvailable = true)
            return
        }
        val apkFile = update.apkPath.takeIf { it.isNotBlank() }?.let(::File)
        if (apkFile != null && apkFile.exists()) {
            installUpdateFile(apkFile)
            return
        }
        downloadAndInstallUpdate()
    }

    private fun checkForUpdates(downloadIfAvailable: Boolean = false) {
        lifecycleScope.launch {
            val installed = updateManager.installedVersion()
            updateUi {
                copy(
                    currentVersionName = installed.versionName,
                    currentVersionCode = installed.versionCode,
                    checking = true,
                    downloading = false,
                    status = "Checking GitHub Releases...",
                    apkPath = ""
                )
            }
            runCatching {
                updateManager.fetchLatestRelease()
            }.onSuccess { latest ->
                val available = latest.isNewerThan(installed)
                updateUi {
                    copy(
                        checking = false,
                        available = available,
                        latestTag = latest.tagName,
                        latestVersionName = latest.versionName,
                        latestVersionCode = latest.versionCode,
                        releaseUrl = latest.releaseUrl,
                        releaseNotes = latest.releaseNotes,
                        apkName = latest.apkName,
                        apkUrl = latest.apkDownloadUrl,
                        status = if (available) {
                            "Update available: ${latest.title}"
                        } else {
                            "You're up to date: ${installed.versionName} (${installed.versionCode})"
                        }
                    )
                }
                if (available && downloadIfAvailable) {
                    downloadAndInstallUpdate()
                }
            }.onFailure { throwable ->
                updateUi {
                    copy(
                        checking = false,
                        available = false,
                        status = "Update check failed: ${throwable.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun downloadAndInstallUpdate() {
        val update = uiState.update
        val release = update.toGitHubReleaseUpdate() ?: run {
            checkForUpdates()
            return
        }
        if (!updateManager.canInstallPackages()) {
            updateUi {
                copy(status = "Allow installs from Rokid Live Studio, then tap update again.")
            }
            updateManager.openInstallPermissionSettings()
            return
        }
        lifecycleScope.launch {
            updateUi {
                copy(
                    downloading = true,
                    status = "Downloading ${release.apkName}..."
                )
            }
            runCatching {
                updateManager.downloadApk(release)
            }.onSuccess { file ->
                updateUi {
                    copy(
                        downloading = false,
                        apkPath = file.absolutePath,
                        status = "Downloaded ${file.name}; opening installer..."
                    )
                }
                installUpdateFile(file)
            }.onFailure { throwable ->
                updateUi {
                    copy(
                        downloading = false,
                        status = "Download failed: ${throwable.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun installUpdateFile(file: File) {
        if (!updateManager.canInstallPackages()) {
            updateUi {
                copy(status = "Allow installs from Rokid Live Studio, then tap update again.")
            }
            updateManager.openInstallPermissionSettings()
            return
        }
        runCatching {
            updateManager.installApk(file)
        }.onSuccess {
            updateUi { copy(status = "Android package installer opened") }
        }.onFailure { throwable ->
            updateUi { copy(status = "Install failed: ${throwable.message ?: "unknown error"}") }
        }
    }

    private fun updateUi(block: AppUpdateUiState.() -> AppUpdateUiState) {
        uiState = uiState.copy(update = uiState.update.block())
    }

    private fun AppUpdateUiState.toGitHubReleaseUpdate(): GitHubReleaseUpdate? {
        if (apkUrl.isBlank() || apkName.isBlank()) return null
        return GitHubReleaseUpdate(
            tagName = latestTag,
            versionName = latestVersionName,
            versionCode = latestVersionCode,
            title = latestTag.ifBlank { latestVersionName.ifBlank { apkName } },
            releaseUrl = releaseUrl,
            releaseNotes = releaseNotes,
            apkName = apkName,
            apkDownloadUrl = apkUrl
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun disconnectYoutube() {
        youtubeDeviceAuthJob?.cancel()
        youtubeGoLiveJob?.cancel()
        youtubeCompleteJob?.cancel()
        stopYoutubeChat(clearHelper = true)
        val tokenToRevoke = youtubeDeviceRefreshToken.ifBlank { youtubeDeviceAccessToken }
        youtubeDeviceAccessToken = ""
        youtubeDeviceRefreshToken = ""
        youtubeDeviceAccessTokenExpiresAtMs = 0L
        secretStore.remove(PREF_YOUTUBE_DEVICE_REFRESH_TOKEN)
        clearPersistedYoutubeLive()
        if (tokenToRevoke.isNotBlank()) {
            lifecycleScope.launch { runCatching { youtubeDeviceAuthApi.revoke(tokenToRevoke) } }
        }
        youtubeAuth.disconnectLocal()
        uiState = uiState.copy(
            youtubeConnected = false,
            youtubeAccount = "",
            youtubeChannelId = "",
            youtubeChannelTitle = "",
            youtubeBroadcastId = "",
            youtubeStreamId = "",
            youtubeWatchUrl = "",
            youtubeIngestionAddress = "",
            youtubeDeviceAuthPending = false,
            youtubeDeviceUserCode = "",
            youtubeDeviceVerificationUrl = "",
            youtubeChatMessages = emptyList(),
            youtubeChatStatus = "",
            youtubeStatus = "YouTube disconnected",
            error = ""
        )
    }

    private fun refreshYoutubeChannel() {
        requireYoutubeToken { token -> refreshYoutubeChannel(token) }
    }

    private fun refreshYoutubeChannel(token: String) {
        lifecycleScope.launch {
            runCatching {
                uiState = uiState.copy(youtubeStatus = "Checking OAuth YouTube channel...")
                youtubeLiveApi.getMineChannel(token)
            }.onSuccess { channel ->
                uiState = uiState.copy(
                    youtubeConnected = true,
                    youtubeChannelId = channel.id,
                    youtubeChannelTitle = channel.title,
                    youtubeStatus = "OAuth channel: ${channel.summary}",
                    lastStatus = "OAuth channel: ${channel.summary}",
                    error = ""
                )
            }.onFailure { throwable ->
                if ((throwable as? YoutubeApiException)?.statusCode == 401) {
                    youtubeAuth.clearCachedToken()
                    youtubeDeviceAccessToken = ""
                }
                setError("Check YouTube channel failed", throwable)
            }
        }
    }

    private fun disconnectTwitch() {
        twitchDeviceAuthJob?.cancel()
        stopTwitchChat(clearHelper = true)
        val tokenToRevoke = twitchDeviceRefreshToken.ifBlank { twitchDeviceAccessToken }
        val clientId = uiState.twitchDeviceClientId.trim()
        twitchDeviceAccessToken = ""
        twitchDeviceRefreshToken = ""
        twitchDeviceAccessTokenExpiresAtMs = 0L
        secretStore.remove(PREF_TWITCH_DEVICE_REFRESH_TOKEN)
        if (tokenToRevoke.isNotBlank() && clientId.isNotBlank()) {
            lifecycleScope.launch { runCatching { twitchDeviceAuthApi.revoke(clientId, tokenToRevoke) } }
        }
        preferences.edit()
            .remove(PREF_TWITCH_USER_ID)
            .remove(PREF_TWITCH_USER_LOGIN)
            .remove(PREF_TWITCH_CHANNEL_TITLE)
            .apply()
        uiState = uiState.copy(
            twitchConnected = false,
            twitchAccount = "",
            twitchUserId = "",
            twitchUserLogin = "",
            twitchChannelTitle = "",
            twitchDeviceAuthPending = false,
            twitchDeviceUserCode = "",
            twitchDeviceVerificationUrl = "",
            twitchChatMessages = emptyList(),
            twitchChatStatus = "",
            twitchStatus = "Twitch disconnected",
            error = ""
        )
    }

    private fun refreshTwitchChannel() {
        requireTwitchToken { token -> refreshTwitchChannel(token) }
    }

    private fun refreshTwitchChannel(token: String) {
        val clientId = uiState.twitchDeviceClientId.trim()
        if (clientId.isBlank()) {
            setError("Paste your Twitch OAuth client ID first", null)
            return
        }
        lifecycleScope.launch {
            runCatching {
                uiState = uiState.copy(twitchStatus = "Checking OAuth Twitch channel...")
                val user = twitchApi.getMe(token, clientId)
                val channel = twitchApi.getChannel(token, clientId, user.id)
                user to channel
            }.onSuccess { (user, channel) ->
                preferences.edit()
                    .putString(PREF_TWITCH_USER_ID, user.id)
                    .putString(PREF_TWITCH_USER_LOGIN, user.login)
                    .putString(PREF_TWITCH_CHANNEL_TITLE, channel.title)
                    .apply()
                uiState = uiState.copy(
                    twitchConnected = true,
                    twitchUserId = user.id,
                    twitchUserLogin = user.login,
                    twitchAccount = user.summary,
                    twitchChannelTitle = channel.title.ifBlank { user.summary },
                    twitchTitle = uiState.twitchTitle.ifBlank { channel.title.ifBlank { defaultTwitchTitle() } },
                    twitchCategoryId = channel.categoryId.ifBlank { uiState.twitchCategoryId },
                    twitchStatus = "OAuth channel: ${user.summary}",
                    lastStatus = "OAuth Twitch channel: ${user.summary}",
                    error = ""
                )
                startTwitchChatIfPossible()
            }.onFailure { throwable ->
                if ((throwable as? TwitchApiException)?.statusCode == 401) {
                    twitchDeviceAccessToken = ""
                }
                setError("Check Twitch channel failed", throwable)
            }
        }
    }

    private fun createYoutubeLive() {
        val title = uiState.youtubeTitle.trim().ifBlank { defaultYoutubeTitle() }
        youtubeCompleteJob?.cancel()
        uiState = uiState.copy(youtubeTitle = title, youtubeStatus = "Preparing YouTube live...")
        requireYoutubeToken { token ->
            lifecycleScope.launch {
                runCatching {
                    uiState = uiState.copy(youtubeStatus = "Checking OAuth YouTube channel...")
                    val channel = youtubeLiveApi.getMineChannel(token)
                    uiState = uiState.copy(
                        youtubeConnected = true,
                        youtubeChannelId = channel.id,
                        youtubeChannelTitle = channel.title,
                        youtubeStatus = "Creating live on ${channel.title.ifBlank { channel.id }}..."
                    )
                    youtubeLiveApi.createLive(
                        accessToken = token,
                        request = YoutubeLiveRequest(
                            title = title,
                            description = uiState.youtubeDescription,
                            privacy = uiState.youtubePrivacy,
                            category = YoutubeCategory.fromId(uiState.youtubeCategoryId),
                            preset = uiState.selectedPreset
                        )
                    )
                }.onSuccess { live ->
                    persistCreatedYoutubeLive(live)
                    uiState = uiState.copy(
                        youtubeStreamKey = live.streamName,
                        youtubeBroadcastId = live.broadcastId,
                        youtubeStreamId = live.streamId,
                        youtubeWatchUrl = live.watchUrl,
                        youtubeIngestionAddress = live.ingestionAddress,
                        youtubeStatus = "Created ${live.privacy.label} live: ${live.title}",
                        lastStatus = live.watchUrl,
                        error = ""
                    )
                    startYoutubeChatIfPossible()
                    startYoutubeLive()
                }.onFailure { throwable ->
                    if (throwable is CancellationException) return@onFailure
                    if ((throwable as? YoutubeApiException)?.statusCode == 401) {
                        youtubeAuth.clearCachedToken()
                        youtubeDeviceAccessToken = ""
                    }
                    setError("Create YouTube live failed", throwable)
                }
            }
        }
    }

    private fun persistCreatedYoutubeLive(live: CreatedYoutubeLive) {
        preferences.edit()
            .putString(PREF_YOUTUBE_BROADCAST_ID, live.broadcastId)
            .putString(PREF_YOUTUBE_STREAM_ID, live.streamId)
            .putString(PREF_YOUTUBE_WATCH_URL, live.watchUrl)
            .putString(PREF_YOUTUBE_INGESTION_ADDRESS, live.ingestionAddress)
            .apply()
        secretStore.putString(PREF_YOUTUBE_STREAM_KEY, live.streamName)
    }

    private fun clearPersistedYoutubeLive() {
        preferences.edit()
            .remove(PREF_YOUTUBE_BROADCAST_ID)
            .remove(PREF_YOUTUBE_STREAM_ID)
            .remove(PREF_YOUTUBE_WATCH_URL)
            .remove(PREF_YOUTUBE_INGESTION_ADDRESS)
            .apply()
    }

    private fun requireYoutubeToken(onToken: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (youtubeDeviceAccessToken.isNotBlank() && now < youtubeDeviceAccessTokenExpiresAtMs - 60_000L) {
            onToken(youtubeDeviceAccessToken)
            return
        }

        val refreshToken = youtubeDeviceRefreshToken
            .ifBlank { secretStore.getString(PREF_YOUTUBE_DEVICE_REFRESH_TOKEN) }
        if (refreshToken.isNotBlank()) {
            val clientId = uiState.youtubeDeviceClientId.trim()
            val clientSecret = uiState.youtubeDeviceClientSecret.trim()
            if (clientId.isBlank() || clientSecret.isBlank()) {
                setError("Paste your YouTube TV/device OAuth client ID and secret to refresh auth", null)
                return
            }
            lifecycleScope.launch {
                runCatching {
                    uiState = uiState.copy(youtubeStatus = "Refreshing YouTube device auth...")
                    youtubeDeviceAuthApi.refreshAccessToken(clientId, clientSecret, refreshToken)
                }.onSuccess { tokens ->
                    storeYoutubeDeviceTokens(tokens)
                    onToken(tokens.accessToken)
                }.onFailure { throwable ->
                    setError("Refresh YouTube device auth failed", throwable)
                }
            }
            return
        }

        youtubeAuth.requireToken(onToken)
    }

    private fun storeYoutubeDeviceTokens(tokens: YoutubeDeviceTokens) {
        youtubeDeviceAccessToken = tokens.accessToken
        youtubeDeviceRefreshToken = tokens.refreshToken
        youtubeDeviceAccessTokenExpiresAtMs = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L
        if (tokens.refreshToken.isNotBlank()) {
            secretStore.putString(PREF_YOUTUBE_DEVICE_REFRESH_TOKEN, tokens.refreshToken)
        }
        uiState = uiState.copy(
            youtubeConnected = true,
            youtubeAccount = "device linked",
            error = ""
        )
    }

    private fun requireTwitchToken(onToken: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (twitchDeviceAccessToken.isNotBlank() && now < twitchDeviceAccessTokenExpiresAtMs - 60_000L) {
            onToken(twitchDeviceAccessToken)
            return
        }

        val refreshToken = twitchDeviceRefreshToken
            .ifBlank { secretStore.getString(PREF_TWITCH_DEVICE_REFRESH_TOKEN) }
        if (refreshToken.isBlank()) {
            setError("Connect Twitch with OAuth first", null)
            return
        }
        val clientId = uiState.twitchDeviceClientId.trim()
        if (clientId.isBlank()) {
            setError("Paste your Twitch OAuth client ID to refresh auth", null)
            return
        }
        lifecycleScope.launch {
            runCatching {
                uiState = uiState.copy(twitchStatus = "Refreshing Twitch device auth...")
                twitchDeviceAuthApi.refreshAccessToken(clientId, refreshToken)
            }.onSuccess { tokens ->
                storeTwitchDeviceTokens(tokens)
                onToken(tokens.accessToken)
            }.onFailure { throwable ->
                if (throwable.isExpiredTwitchLogin()) {
                    clearStoredTwitchLogin()
                    setError("Twitch login expired. Sign in again.", throwable)
                } else {
                    setError("Refresh Twitch device auth failed", throwable)
                }
            }
        }
    }

    private fun storeTwitchDeviceTokens(tokens: TwitchDeviceTokens) {
        twitchDeviceAccessToken = tokens.accessToken
        twitchDeviceRefreshToken = tokens.refreshToken
        twitchDeviceAccessTokenExpiresAtMs = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L
        if (tokens.refreshToken.isNotBlank()) {
            secretStore.putString(PREF_TWITCH_DEVICE_REFRESH_TOKEN, tokens.refreshToken)
        }
        uiState = uiState.copy(
            twitchConnected = true,
            twitchAccount = "device linked",
            error = ""
        )
    }

    private fun clearStoredTwitchLogin() {
        twitchDeviceAccessToken = ""
        twitchDeviceRefreshToken = ""
        twitchDeviceAccessTokenExpiresAtMs = 0L
        secretStore.remove(PREF_TWITCH_DEVICE_REFRESH_TOKEN)
        preferences.edit()
            .remove(PREF_TWITCH_USER_ID)
            .remove(PREF_TWITCH_USER_LOGIN)
            .remove(PREF_TWITCH_CHANNEL_TITLE)
            .apply()
        uiState = uiState.copy(
            twitchConnected = false,
            twitchAccount = "",
            twitchUserId = "",
            twitchUserLogin = "",
            twitchChannelTitle = "",
            twitchDeviceAuthPending = false,
            twitchDeviceUserCode = "",
            twitchDeviceVerificationUrl = ""
        )
    }

    private fun Throwable.isExpiredTwitchLogin(): Boolean =
        this is TwitchDeviceAuthException &&
            (errorCode.contains("invalid", ignoreCase = true) ||
                message.orEmpty().contains("refresh", ignoreCase = true))

    private fun startYoutubeLive() {
        if (uiState.twitchLive) stopTwitchLive()
        if (uiState.customLive) stopCustomLive()
        youtubeCompleteJob?.cancel()
        val streamKey = uiState.youtubeStreamKey.trim()
        if (streamKey.isBlank()) {
            setError("Paste your YouTube stream key first", null)
            return
        }
        applySelectedPreset()
        val willTranscode = uiState.previewRotationDegrees.normalizedRotation() != 0 || YOUTUBE_FIX_HORIZONTAL_MIRROR
        if (willTranscode) {
            youtubePublisher.clearVideoState()
        }
        hidePreviewForStreaming("YouTube")
        youtubePublisher.start(
            streamKey = streamKey,
            preset = uiState.selectedPreset,
            serverUrl = uiState.youtubeIngestionAddress.ifBlank { "rtmps://a.rtmps.youtube.com/live2" },
            videoBitrate = selectedStreamingVideoBitrate(uiState.youtubeVideoBitrateOverride)
        )
        if (willTranscode) {
            startOrUpdateYoutubeVideoPipeline()
        } else {
            youtubeVideoTranscoder?.stop()
            youtubeVideoTranscoder = null
            lastVideoConfig?.let { youtubePublisher.configureVideo(it) }
        }
        if (uiState.streaming || uiState.reverseRunning || uiState.receiverRunning) {
            cxr.requestKeyFrame()
        } else {
            startP2pStream()
        }
    }

    private fun startTwitchLive() {
        if (uiState.youtubeLive) stopYoutubeLive()
        if (uiState.customLive) stopCustomLive()
        val clientId = uiState.twitchDeviceClientId.trim()
        if (uiState.twitchConnected) {
            if (clientId.isBlank()) {
                setError("Paste your Twitch OAuth client ID first", null)
                return
            }
            requireTwitchToken { token ->
                lifecycleScope.launch {
                    runCatching {
                        prepareTwitchOauthStream(token, clientId)
                    }.onSuccess { streamKey ->
                        startTwitchPublisher(streamKey)
                    }.onFailure { throwable ->
                        if ((throwable as? TwitchApiException)?.statusCode == 401) {
                            twitchDeviceAccessToken = ""
                        }
                        setError("Prepare Twitch live failed", throwable)
                    }
                }
            }
        } else {
            val streamKey = uiState.twitchStreamKey.trim()
            if (streamKey.isBlank()) {
                setError("Paste your Twitch stream key or connect OAuth first", null)
                return
            }
            startTwitchPublisher(streamKey)
        }
    }

    private suspend fun prepareTwitchOauthStream(accessToken: String, clientId: String): String {
        uiState = uiState.copy(twitchStatus = "Preparing Twitch channel...")
        val user = twitchApi.getMe(accessToken, clientId)
        val title = uiState.twitchTitle.trim().ifBlank { defaultTwitchTitle() }
        val category = TwitchCategory.fromId(uiState.twitchCategoryId)
        twitchApi.updateChannel(
            accessToken = accessToken,
            clientId = clientId,
            broadcasterId = user.id,
            title = title,
            category = category
        )
        val streamKey = twitchApi.getStreamKey(accessToken, clientId, user.id)
        secretStore.putString(PREF_TWITCH_STREAM_KEY, streamKey)
        preferences.edit()
            .putString(PREF_TWITCH_USER_ID, user.id)
            .putString(PREF_TWITCH_USER_LOGIN, user.login)
            .putString(PREF_TWITCH_TITLE, title)
            .apply()
        uiState = uiState.copy(
            twitchStreamKey = streamKey,
            twitchTitle = title,
            twitchConnected = true,
            twitchUserId = user.id,
            twitchUserLogin = user.login,
            twitchAccount = user.summary,
            twitchStatus = "Twitch channel updated: $title",
            error = ""
        )
        startTwitchChatIfPossible()
        return streamKey
    }

    private fun startTwitchPublisher(streamKey: String) {
        lifecycleScope.launch {
            val primaryServerUrl = normalizedTwitchIngestServer(uiState.twitchIngestServerUrl)
            val fallbackServerUrls = loadTwitchFallbackIngestServers(primaryServerUrl)
            startTwitchPublisherWithFallbacks(streamKey, primaryServerUrl, fallbackServerUrls)
        }
    }

    private suspend fun loadTwitchFallbackIngestServers(primaryServerUrl: String): List<String> {
        uiState = uiState.copy(twitchStatus = "Loading Twitch ingest fallbacks...")
        return runCatching {
            twitchApi.getIngestServers()
                .map { normalizedTwitchIngestServer(it.serverUrl) }
                .filter { it != primaryServerUrl }
                .distinct()
                .take(TWITCH_INGEST_FALLBACK_LIMIT)
        }.getOrElse { throwable ->
            uiState = uiState.copy(twitchStatus = "Twitch ingest fallback list unavailable")
            emptyList()
        }
    }

    private fun startTwitchPublisherWithFallbacks(
        streamKey: String,
        primaryServerUrl: String,
        fallbackServerUrls: List<String>
    ) {
        applySelectedPreset()
        val willTranscode = uiState.previewRotationDegrees.normalizedRotation() != 0 || YOUTUBE_FIX_HORIZONTAL_MIRROR
        if (willTranscode) {
            twitchPublisher.clearVideoState()
        }
        hidePreviewForStreaming("Twitch")
        twitchPublisher.start(
            streamKey = streamKey,
            preset = uiState.selectedPreset,
            serverUrl = primaryServerUrl,
            fallbackServerUrls = fallbackServerUrls,
            videoBitrate = selectedStreamingVideoBitrate(uiState.twitchVideoBitrateOverride)
        )
        if (willTranscode) {
            startOrUpdateTwitchVideoPipeline()
        } else {
            twitchVideoTranscoder?.stop()
            twitchVideoTranscoder = null
            lastVideoConfig?.let { twitchPublisher.configureVideo(it) }
        }
        if (uiState.streaming || uiState.reverseRunning || uiState.receiverRunning) {
            cxr.requestKeyFrame()
        } else {
            startP2pStream()
        }
    }

    private fun startCustomLive() {
        val serverUrl = uiState.customRtmpServerUrl.trim()
        val streamKey = uiState.customRtmpStreamKey.trim()
        if (!serverUrl.startsWith("rtmp://") && !serverUrl.startsWith("rtmps://")) {
            setError("Custom RTMP server URL must start with rtmp:// or rtmps://", null)
            return
        }
        if (streamKey.isBlank()) {
            setError("Paste your Custom RTMP stream key first", null)
            return
        }
        if (uiState.youtubeLive) stopYoutubeLive()
        if (uiState.twitchLive) stopTwitchLive()

        applySelectedPreset()
        val willTranscode = uiState.previewRotationDegrees.normalizedRotation() != 0 || YOUTUBE_FIX_HORIZONTAL_MIRROR
        if (willTranscode) {
            customPublisher.clearVideoState()
        }
        hidePreviewForStreaming("Custom RTMP")
        customPublisher.start(
            streamKey = streamKey,
            preset = uiState.selectedPreset,
            serverUrl = serverUrl,
            fallbackServerUrls = emptyList(),
            videoBitrate = selectedStreamingVideoBitrate(uiState.customVideoBitrateOverride)
        )
        if (willTranscode) {
            startOrUpdateCustomVideoPipeline()
        } else {
            customVideoTranscoder?.stop()
            customVideoTranscoder = null
            lastVideoConfig?.let { customPublisher.configureVideo(it) }
        }
        startCustomChatIfPossible()
        if (uiState.streaming || uiState.reverseRunning || uiState.receiverRunning) {
            cxr.requestKeyFrame()
        } else {
            startP2pStream()
        }
    }

    private fun selectedStreamingVideoBitrate(overrideBitrate: Int): Int {
        val preset = uiState.selectedPreset
        val defaultOutputBitrate = preset.youtubeVideoBitrate.takeIf { it > 0 } ?: preset.videoBitrate
        return overrideBitrate.takeIf { it > 0 } ?: defaultOutputBitrate
    }

    private fun hidePreviewForStreaming(platformName: String) {
        uiState = uiState.copy(
            previewAutoHideRequest = uiState.previewAutoHideRequest + 1,
            lastStatus = "$platformName preview hidden for stream performance"
        )
    }

    private fun stopYoutubeLive() {
        val broadcastId = uiState.youtubeBroadcastId
        youtubeGoLiveJob?.cancel()
        stopYoutubeChat(clearHelper = true)
        youtubeVideoTranscoder?.stop()
        youtubeVideoTranscoder = null
        youtubePublisher.stop()
        stopCameraTransport()
        clearPersistedYoutubeLive()
        uiState = uiState.copy(
            youtubeLive = false,
            youtubeBroadcastId = "",
            youtubeStreamId = "",
            youtubeIngestionAddress = "",
            youtubeRtmpDiagnostics = RtmpDiagnostics()
        )
        completeYoutubeBroadcastAfterStop(broadcastId)
    }

    private fun stopTwitchLive() {
        stopTwitchChat(clearHelper = true)
        twitchVideoTranscoder?.stop()
        twitchVideoTranscoder = null
        twitchPublisher.stop()
        stopCameraTransport()
        uiState = uiState.copy(twitchLive = false, twitchRtmpDiagnostics = RtmpDiagnostics())
    }

    private fun stopCustomLive() {
        stopCustomChat(clearHelper = true)
        customVideoTranscoder?.stop()
        customVideoTranscoder = null
        customPublisher.stop()
        stopCameraTransport()
        uiState = uiState.copy(customLive = false, customRtmpDiagnostics = RtmpDiagnostics())
    }

    private fun normalizedTwitchIngestServer(serverUrl: String): String =
        serverUrl.trim()
            .ifBlank { TwitchApi.DEFAULT_INGEST_SERVER }
            .let { if (it == TwitchApi.LEGACY_INGEST_SERVER) TwitchApi.DEFAULT_INGEST_SERVER else it }

    private fun startYoutubeBroadcastWhenRtmpReady() {
        val broadcastId = uiState.youtubeBroadcastId
        val streamId = uiState.youtubeStreamId
        if (broadcastId.isBlank() || streamId.isBlank()) return
        if (youtubeGoLiveJob?.isActive == true) return

        requireYoutubeToken { token ->
            if (!isCurrentYoutubeLiveTarget(broadcastId, streamId)) return@requireYoutubeToken
            youtubeGoLiveJob?.cancel()
            youtubeGoLiveJob = lifecycleScope.launch {
                runCatching {
                    uiState = uiState.copy(youtubeStatus = "Waiting for YouTube ingest...")
                    var latest = YoutubeStreamStatus(streamStatus = "", healthStatus = "")
                    for (attempt in 0 until YOUTUBE_INGEST_POLL_ATTEMPTS) {
                        if (!isCurrentYoutubeLiveTarget(broadcastId, streamId)) return@launch
                        latest = youtubeLiveApi.getStreamStatus(token, streamId)
                        val health = latest.healthStatus
                            .takeIf { it.isNotBlank() }
                            ?.let { ", health=$it" }
                            .orEmpty()
                        uiState = uiState.copy(
                            youtubeStatus = "YouTube ingest: ${latest.streamStatus.ifBlank { "unknown" }}$health"
                        )
                        if (latest.streamStatus.equals("active", ignoreCase = true)) break
                        delay(YOUTUBE_INGEST_POLL_DELAY_MS)
                    }
                    if (!latest.streamStatus.equals("active", ignoreCase = true)) {
                        error("YouTube ingest is ${latest.streamStatus.ifBlank { "not active" }}")
                    }
                    if (!isCurrentYoutubeLiveTarget(broadcastId, streamId)) return@launch
                    val status = youtubeLiveApi.transitionBroadcastLive(token, broadcastId)
                    uiState = uiState.copy(
                        youtubeStatus = "YouTube broadcast: ${status.lifeCycleStatus.ifBlank { "live requested" }}",
                        lastStatus = "YouTube live transition sent"
                    )
                }.onFailure { throwable ->
                    if ((throwable as? YoutubeApiException)?.statusCode == 401) {
                        youtubeAuth.clearCachedToken()
                        youtubeDeviceAccessToken = ""
                    }
                    val message = throwable.message.orEmpty()
                    if (message.contains("redundantTransition", ignoreCase = true)) {
                        uiState = uiState.copy(youtubeStatus = "YouTube already live")
                    } else {
                        setError("Start YouTube broadcast failed", throwable)
                    }
                }
            }
        }
    }

    private fun completeYoutubeBroadcastAfterStop(broadcastId: String) {
        if (broadcastId.isBlank()) return
        requireYoutubeToken { token ->
            youtubeCompleteJob?.cancel()
            youtubeCompleteJob = lifecycleScope.launch {
                runCatching {
                    val status = youtubeLiveApi.transitionBroadcastComplete(token, broadcastId)
                    uiState = uiState.copy(
                        youtubeStatus = "YouTube broadcast: ${status.lifeCycleStatus.ifBlank { "complete requested" }}",
                        lastStatus = "YouTube complete transition sent"
                    )
                }.onFailure { throwable ->
                    if ((throwable as? YoutubeApiException)?.statusCode == 401) {
                        youtubeAuth.clearCachedToken()
                        youtubeDeviceAccessToken = ""
                    }
                    val message = throwable.message.orEmpty()
                    if (message.contains("redundantTransition", ignoreCase = true) ||
                        message.contains("invalidTransition", ignoreCase = true)
                    ) {
                        uiState = uiState.copy(youtubeStatus = "YouTube broadcast stopped")
                    } else {
                        setError("Stop YouTube broadcast failed", throwable)
                    }
                }
            }
        }
    }

    private fun startYoutubeChatIfPossible() {
        if (!uiState.youtubeChatEnabled) return
        sendYoutubeChatStyle()
        val broadcastId = uiState.youtubeBroadcastId
        if (broadcastId.isBlank()) {
            uiState = uiState.copy(youtubeChatStatus = "Chat starts after an OAuth live is created")
            return
        }
        if (youtubeChatJob?.isActive == true) return

        youtubeChatStopRequested = false
        requireYoutubeToken { token ->
            val targetBroadcastId = uiState.youtubeBroadcastId
            if (targetBroadcastId.isBlank() || targetBroadcastId != broadcastId) return@requireYoutubeToken
            youtubeChatJob?.cancel()
            youtubeChatJob = lifecycleScope.launch {
                runCatching {
                    uiState = uiState.copy(youtubeChatStatus = "Finding YouTube live chat...")
                    val liveChatId = youtubeLiveApi.getLiveChatId(token, targetBroadcastId)
                    uiState = uiState.copy(youtubeChatStatus = "Chat connected")
                    val recentMessages = ArrayDeque<ChatOverlayMessage>()
                    val seenIds = linkedMapOf<String, Long>()
                    var pageToken = ""
                    while (isActive &&
                        uiState.youtubeChatEnabled &&
                        uiState.youtubeBroadcastId == targetBroadcastId
                    ) {
                        val page = youtubeLiveApi.listLiveChatMessages(token, liveChatId, pageToken)
                        pageToken = page.nextPageToken
                        val nowMs = System.currentTimeMillis()
                        val seenIterator = seenIds.entries.iterator()
                        while (seenIterator.hasNext()) {
                            if (nowMs - seenIterator.next().value > YOUTUBE_CHAT_SEEN_ID_TTL_MS) {
                                seenIterator.remove()
                            }
                        }
                        val freshMessages = page.messages
                            .filter { message ->
                                if (message.id.isBlank()) {
                                    true
                                } else {
                                    val isNew = !seenIds.containsKey(message.id)
                                    if (isNew) seenIds[message.id] = nowMs
                                    isNew
                                }
                            }
                            .map { message ->
                                ChatOverlayMessage(
                                    author = message.author,
                                    text = message.text,
                                    timestampMs = nowMs
                                )
                            }
                        if (freshMessages.isNotEmpty()) {
                            freshMessages.forEach { message ->
                                recentMessages.addLast(message)
                                val currentMaxMessages = uiState.youtubeChatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
                                while (recentMessages.size > currentMaxMessages) {
                                    recentMessages.removeFirst()
                                }
                            }
                            val overlayMessages = recentMessages.toList()
                                .takeLast(uiState.youtubeChatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT))
                            uiState = uiState.copy(
                                youtubeChatMessages = overlayMessages,
                                youtubeChatStatus = "Showing ${overlayMessages.size} chat messages on helper"
                            )
                            cxr.sendChatMessages(overlayMessages)
                        } else if (uiState.youtubeChatStatus.isBlank()) {
                            uiState = uiState.copy(youtubeChatStatus = "Waiting for chat messages")
                        }
                        delay(page.pollingIntervalMillis.coerceIn(1_500, 12_000).toLong())
                    }
                }.onFailure { throwable ->
                    if (youtubeChatStopRequested || throwable.isCancellationLike()) {
                        uiState = uiState.copy(youtubeChatStatus = "Chat stopped")
                        return@onFailure
                    }
                    if ((throwable as? YoutubeApiException)?.statusCode == 401) {
                        youtubeAuth.clearCachedToken()
                        youtubeDeviceAccessToken = ""
                    }
                    setError("YouTube chat failed", throwable)
                    uiState = uiState.copy(youtubeChatStatus = "Chat unavailable")
                }
            }
        }
    }

    private fun stopYoutubeChat(clearHelper: Boolean) {
        youtubeChatStopRequested = true
        youtubeChatJob?.cancel()
        youtubeChatJob = null
        if (clearHelper) {
            runCatching { cxr.sendChatMessages(emptyList()) }
        }
        val nextError = if (uiState.error.startsWith("YouTube chat failed")) "" else uiState.error
        uiState = uiState.copy(
            youtubeChatMessages = emptyList(),
            youtubeChatStatus = if (uiState.youtubeChatEnabled) "Chat stopped" else uiState.youtubeChatStatus,
            error = nextError
        )
    }

    private fun startTwitchChatIfPossible() {
        if (!uiState.twitchChatEnabled) return
        sendTwitchChatStyle()
        if (!uiState.twitchConnected) {
            uiState = uiState.copy(twitchChatStatus = "Connect Twitch to show chat in the helper")
            return
        }
        if (twitchChatClient != null) return
        val clientId = uiState.twitchDeviceClientId.trim()
        val userId = uiState.twitchUserId.trim()
        if (clientId.isBlank() || userId.isBlank()) {
            uiState = uiState.copy(twitchChatStatus = "Refresh Twitch channel before chat")
            return
        }
        twitchChatStopRequested = false
        requireTwitchToken { token ->
            if (!uiState.twitchChatEnabled || twitchChatClient != null) return@requireTwitchToken
            val recentMessages = ArrayDeque<ChatOverlayMessage>()
            val seenIds = linkedMapOf<String, Long>()
            twitchChatClient = TwitchChatClient(
                api = twitchApi,
                accessToken = token,
                clientId = clientId,
                broadcasterId = userId,
                userId = userId,
                onStatus = { status ->
                    onMain { uiState = uiState.copy(twitchChatStatus = status, lastStatus = status) }
                },
                onMessages = { messages ->
                    onMain {
                        val nowMs = System.currentTimeMillis()
                        val seenIterator = seenIds.entries.iterator()
                        while (seenIterator.hasNext()) {
                            if (nowMs - seenIterator.next().value > YOUTUBE_CHAT_SEEN_ID_TTL_MS) {
                                seenIterator.remove()
                            }
                        }
                        messages.forEach { message ->
                            if (message.id.isNotBlank() && seenIds.containsKey(message.id)) return@forEach
                            if (message.id.isNotBlank()) seenIds[message.id] = nowMs
                            recentMessages.addLast(
                                ChatOverlayMessage(
                                    author = message.author,
                                    text = message.text,
                                    timestampMs = nowMs
                                )
                            )
                            val currentMaxMessages = uiState.twitchChatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
                            while (recentMessages.size > currentMaxMessages) {
                                recentMessages.removeFirst()
                            }
                        }
                        val overlayMessages = recentMessages.toList()
                            .takeLast(uiState.twitchChatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT))
                        uiState = uiState.copy(
                            twitchChatMessages = overlayMessages,
                            twitchChatStatus = "Showing ${overlayMessages.size} chat messages on helper"
                        )
                        cxr.sendChatMessages(overlayMessages)
                    }
                },
                onError = { message, throwable ->
                    onMain {
                        if (twitchChatStopRequested || throwable?.isCancellationLike() == true) {
                            uiState = uiState.copy(twitchChatStatus = "Chat stopped")
                        } else {
                            setError(message, throwable)
                            uiState = uiState.copy(twitchChatStatus = "Chat unavailable")
                        }
                    }
                }
            ).also { it.start() }
        }
    }

    private fun stopTwitchChat(clearHelper: Boolean) {
        twitchChatStopRequested = true
        twitchChatClient?.stop()
        twitchChatClient = null
        if (clearHelper) {
            runCatching { cxr.sendChatMessages(emptyList()) }
        }
        val nextError = if (uiState.error.startsWith("Twitch chat failed")) "" else uiState.error
        uiState = uiState.copy(
            twitchChatMessages = emptyList(),
            twitchChatStatus = if (uiState.twitchChatEnabled) "Chat stopped" else uiState.twitchChatStatus,
            error = nextError
        )
    }

    private fun startCustomChatIfPossible() {
        if (!uiState.customChatEnabled) return
        val channel = uiState.customChatChannel.trim().removePrefix("#").trim()
        if (channel.isBlank()) {
            uiState = uiState.copy(customChatStatus = "Enter a Twitch channel name")
            return
        }
        sendCustomChatStyle()
        if (customChatClient != null) return
        customChatStopRequested = false
        val recentMessages = ArrayDeque<ChatOverlayMessage>()
        val seenIds = linkedMapOf<String, Long>()
        customChatClient = TwitchAnonymousChatClient(
            channel = channel,
            onStatus = { status ->
                onMain {
                    val nextError = if (
                        status == "Twitch chat connected (anonymous)" &&
                        uiState.error.startsWith("Twitch anonymous chat")
                    ) {
                        ""
                    } else {
                        uiState.error
                    }
                    uiState = uiState.copy(customChatStatus = status, lastStatus = status, error = nextError)
                }
            },
            onMessages = { messages ->
                onMain {
                    val nowMs = System.currentTimeMillis()
                    val seenIterator = seenIds.entries.iterator()
                    while (seenIterator.hasNext()) {
                        if (nowMs - seenIterator.next().value > YOUTUBE_CHAT_SEEN_ID_TTL_MS) {
                            seenIterator.remove()
                        }
                    }
                    messages.forEach { message ->
                        if (message.id.isNotBlank() && seenIds.containsKey(message.id)) return@forEach
                        if (message.id.isNotBlank()) seenIds[message.id] = nowMs
                        recentMessages.addLast(
                            ChatOverlayMessage(
                                author = message.author,
                                text = message.text,
                                timestampMs = nowMs
                            )
                        )
                        val currentMaxMessages = uiState.customChatMaxMessages
                            .coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT)
                        while (recentMessages.size > currentMaxMessages) {
                            recentMessages.removeFirst()
                        }
                    }
                    val overlayMessages = recentMessages.toList()
                        .takeLast(uiState.customChatMaxMessages.coerceIn(0, Protocol.MAX_CHAT_MESSAGE_COUNT))
                    uiState = uiState.copy(
                        customChatMessages = overlayMessages,
                        customChatStatus = "Showing ${overlayMessages.size} chat messages on helper"
                    )
                    cxr.sendChatMessages(overlayMessages)
                }
            },
            onError = { message, throwable ->
                onMain {
                    if (customChatStopRequested || throwable?.isCancellationLike() == true) {
                        uiState = uiState.copy(customChatStatus = "Chat stopped")
                    } else {
                        setError(message, throwable)
                        uiState = uiState.copy(customChatStatus = "Reconnecting anonymous Twitch chat...")
                    }
                }
            }
        ).also { it.start() }
    }

    private fun stopCustomChat(clearHelper: Boolean) {
        customChatStopRequested = true
        customChatClient?.stop()
        customChatClient = null
        if (clearHelper) {
            runCatching { cxr.sendChatMessages(emptyList()) }
        }
        val nextError = if (uiState.error.startsWith("Twitch anonymous chat")) "" else uiState.error
        uiState = uiState.copy(
            customChatMessages = emptyList(),
            customChatStatus = if (uiState.customChatEnabled) "Chat stopped" else uiState.customChatStatus,
            error = nextError
        )
    }

    private fun isCurrentYoutubeLiveTarget(broadcastId: String, streamId: String): Boolean =
        uiState.youtubeLive &&
            uiState.youtubeBroadcastId == broadcastId &&
            uiState.youtubeStreamId == streamId

    private fun startOrUpdateYoutubeVideoPipeline() {
        youtubeVideoTranscoder?.stop()
        youtubeVideoTranscoder = null
        val previewRotation = uiState.previewRotationDegrees.normalizedRotation()
        val streamRotation = previewRotation.inverseRotation()
        if (previewRotation == 0 && !YOUTUBE_FIX_HORIZONTAL_MIRROR) {
            lastVideoConfig?.let { youtubePublisher.configureVideo(it) }
            uiState = uiState.copy(youtubeStatus = "YouTube pass-through video")
            return
        }
        val preset = uiState.selectedPreset
        val naturalOutputWidth = if (streamRotation.isQuarterTurn()) preset.height else preset.width
        val naturalOutputHeight = if (streamRotation.isQuarterTurn()) preset.width else preset.height
        val outputWidth = preset.youtubeOutputWidth.takeIf { it > 0 } ?: naturalOutputWidth
        val outputHeight = preset.youtubeOutputHeight.takeIf { it > 0 } ?: naturalOutputHeight
        val defaultOutputBitrate = preset.youtubeVideoBitrate.takeIf { it > 0 } ?: preset.videoBitrate
        val outputBitrate = uiState.youtubeVideoBitrateOverride.takeIf { it > 0 } ?: defaultOutputBitrate
        val transcoder = YoutubeVideoRotationTranscoder(
            platformName = "YouTube",
            inputWidth = preset.width,
            inputHeight = preset.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fps = preset.fps,
            bitrate = outputBitrate,
            iframeIntervalSeconds = preset.iframeIntervalSeconds,
            rotationDegrees = streamRotation,
            mirrorHorizontally = YOUTUBE_FIX_HORIZONTAL_MIRROR,
            onConfig = { payload -> youtubePublisher.configureVideo(payload) },
            onFrame = { payload, timestampUs, keyFrame -> youtubePublisher.publishVideoFrame(payload, timestampUs, keyFrame) },
            onStatus = { status -> onMain { uiState = uiState.copy(youtubeStatus = status, lastStatus = status) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            onInputBackpressure = { onMain { cxr.requestKeyFrame() } }
        )
        youtubeVideoTranscoder = transcoder
        youtubePublisher.clearVideoState()
        transcoder.start()
        lastVideoConfig?.let { transcoder.configure(it) }
    }

    private fun startOrUpdateTwitchVideoPipeline() {
        twitchVideoTranscoder?.stop()
        twitchVideoTranscoder = null
        val previewRotation = uiState.previewRotationDegrees.normalizedRotation()
        val streamRotation = previewRotation.inverseRotation()
        if (previewRotation == 0 && !YOUTUBE_FIX_HORIZONTAL_MIRROR) {
            lastVideoConfig?.let { twitchPublisher.configureVideo(it) }
            uiState = uiState.copy(twitchStatus = "Twitch pass-through video")
            return
        }
        val preset = uiState.selectedPreset
        val naturalOutputWidth = if (streamRotation.isQuarterTurn()) preset.height else preset.width
        val naturalOutputHeight = if (streamRotation.isQuarterTurn()) preset.width else preset.height
        val outputWidth = preset.youtubeOutputWidth.takeIf { it > 0 } ?: naturalOutputWidth
        val outputHeight = preset.youtubeOutputHeight.takeIf { it > 0 } ?: naturalOutputHeight
        val defaultOutputBitrate = preset.youtubeVideoBitrate.takeIf { it > 0 } ?: preset.videoBitrate
        val outputBitrate = uiState.twitchVideoBitrateOverride.takeIf { it > 0 } ?: defaultOutputBitrate
        val transcoder = YoutubeVideoRotationTranscoder(
            platformName = "Twitch",
            inputWidth = preset.width,
            inputHeight = preset.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fps = preset.fps,
            bitrate = outputBitrate,
            iframeIntervalSeconds = preset.iframeIntervalSeconds,
            rotationDegrees = streamRotation,
            mirrorHorizontally = YOUTUBE_FIX_HORIZONTAL_MIRROR,
            onConfig = { payload -> twitchPublisher.configureVideo(payload) },
            onFrame = { payload, timestampUs, keyFrame -> twitchPublisher.publishVideoFrame(payload, timestampUs, keyFrame) },
            onStatus = { status -> onMain { uiState = uiState.copy(twitchStatus = status, lastStatus = status) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            onInputBackpressure = { onMain { cxr.requestKeyFrame() } }
        )
        twitchVideoTranscoder = transcoder
        twitchPublisher.clearVideoState()
        transcoder.start()
        lastVideoConfig?.let { transcoder.configure(it) }
    }

    private fun startOrUpdateCustomVideoPipeline() {
        customVideoTranscoder?.stop()
        customVideoTranscoder = null
        val previewRotation = uiState.previewRotationDegrees.normalizedRotation()
        val streamRotation = previewRotation.inverseRotation()
        if (previewRotation == 0 && !YOUTUBE_FIX_HORIZONTAL_MIRROR) {
            lastVideoConfig?.let { customPublisher.configureVideo(it) }
            uiState = uiState.copy(customStatus = "Custom RTMP pass-through video")
            return
        }
        val preset = uiState.selectedPreset
        val naturalOutputWidth = if (streamRotation.isQuarterTurn()) preset.height else preset.width
        val naturalOutputHeight = if (streamRotation.isQuarterTurn()) preset.width else preset.height
        val outputWidth = preset.youtubeOutputWidth.takeIf { it > 0 } ?: naturalOutputWidth
        val outputHeight = preset.youtubeOutputHeight.takeIf { it > 0 } ?: naturalOutputHeight
        val defaultOutputBitrate = preset.youtubeVideoBitrate.takeIf { it > 0 } ?: preset.videoBitrate
        val outputBitrate = uiState.customVideoBitrateOverride.takeIf { it > 0 } ?: defaultOutputBitrate
        val transcoder = YoutubeVideoRotationTranscoder(
            platformName = "Custom RTMP",
            inputWidth = preset.width,
            inputHeight = preset.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            fps = preset.fps,
            bitrate = outputBitrate,
            iframeIntervalSeconds = preset.iframeIntervalSeconds,
            rotationDegrees = streamRotation,
            mirrorHorizontally = YOUTUBE_FIX_HORIZONTAL_MIRROR,
            onConfig = { payload -> customPublisher.configureVideo(payload) },
            onFrame = { payload, timestampUs, keyFrame -> customPublisher.publishVideoFrame(payload, timestampUs, keyFrame) },
            onStatus = { status -> onMain { uiState = uiState.copy(customStatus = status, lastStatus = status) } },
            onError = { message, throwable -> onMain { setError(message, throwable) } },
            onInputBackpressure = { onMain { cxr.requestKeyFrame() } }
        )
        customVideoTranscoder = transcoder
        customPublisher.clearVideoState()
        transcoder.start()
        lastVideoConfig?.let { transcoder.configure(it) }
    }

    private fun defaultYoutubeTitle(): String =
        "Rokid POV ${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}"

    private fun defaultTwitchTitle(): String =
        "Rokid POV ${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}"

    private fun onMain(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun setError(message: String, throwable: Throwable?) {
        val detail = throwable?.message
        val error = if (!detail.isNullOrBlank() && detail != message) "$message: $detail" else message
        uiState = uiState.copy(error = error, lastStatus = error)
    }

    private fun startLiveKeepAlive(title: String) {
        runCatching {
            ContextCompat.startForegroundService(this, LiveKeepAliveService.startIntent(this, title))
        }.onFailure {
            setError("Live keep-alive failed", it)
        }
    }

    private fun stopLiveKeepAlive() {
        runCatching { startService(LiveKeepAliveService.stopIntent(this)) }
    }

    private fun handleReverseMediaError(message: String, throwable: Throwable?) {
        setError(message, throwable)
        uiState = uiState.copy(reverseRunning = false, streaming = false)
        if (!uiState.youtubeLive && !uiState.twitchLive && !uiState.customLive) stopLiveKeepAlive()
    }

    private fun Throwable.isCancellationLike(): Boolean =
        this is CancellationException ||
            javaClass.name.contains("CancellationException") ||
            message?.contains("coroutine was cancelled", ignoreCase = true) == true

    private fun shouldTryReverse(status: StatusMessage): Boolean {
        if (reverseAttempted || status.type != StatusType.ERROR) return false
        val message = status.message.lowercase()
        return message.startsWith("start failed") &&
            (message.contains("enetunreach") ||
                message.contains("network is unreachable") ||
                message.contains("failed to connect"))
    }

    private fun parseHelperIpCandidates(networkInfo: String): List<String> {
        val phoneIps = NetworkAddresses.allIpv4Addresses().map { it.address }.toSet()
        val fromPairs = networkInfo
            .split(",")
            .mapNotNull { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0].trim() to parts[1].trim()
            }
            .filter { (_, address) -> address.isUsableIpv4() && address !in phoneIps }
            .sortedBy { (name, _) ->
                when {
                    name.contains("p2p", ignoreCase = true) -> 0
                    name.contains("wlan", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            .map { it.second }
        if (fromPairs.isNotEmpty()) return fromPairs.distinct()

        return IPV4_REGEX.findAll(networkInfo)
            .map { it.value }
            .filter { it.isUsableIpv4() && it !in phoneIps }
            .distinct()
            .toList()
    }

    private fun String.isUsableIpv4(): Boolean {
        if (this == "0.0.0.0" || startsWith("127.")) return false
        val parts = split(".")
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    companion object {
        private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private const val P2P_PERMISSION_REQUEST = 3001
        private const val PREF_VIDEO_PRESET = "video_preset"
        private const val PREF_PREVIEW_ROTATION_PREFIX = "preview_rotation_"
        private const val PREF_YOUTUBE_STREAM_KEY = "youtube_stream_key"
        private const val PREF_YOUTUBE_TITLE = "youtube_title"
        private const val PREF_YOUTUBE_DESCRIPTION = "youtube_description"
        private const val PREF_YOUTUBE_PRIVACY = "youtube_privacy"
        private const val PREF_YOUTUBE_CATEGORY_ID = "youtube_category_id"
        private const val PREF_YOUTUBE_DEVICE_CLIENT_ID = "youtube_device_client_id"
        private const val PREF_YOUTUBE_DEVICE_CLIENT_SECRET = "youtube_device_client_secret"
        private const val PREF_YOUTUBE_DEVICE_REFRESH_TOKEN = "youtube_device_refresh_token"
        private const val PREF_YOUTUBE_BROADCAST_ID = "youtube_broadcast_id"
        private const val PREF_YOUTUBE_STREAM_ID = "youtube_stream_id"
        private const val PREF_YOUTUBE_WATCH_URL = "youtube_watch_url"
        private const val PREF_YOUTUBE_INGESTION_ADDRESS = "youtube_ingestion_address"
        private const val PREF_YOUTUBE_VIDEO_BITRATE = "youtube_video_bitrate"
        private const val PREF_YOUTUBE_CHAT_ENABLED = "youtube_chat_enabled"
        private const val PREF_YOUTUBE_CHAT_FONT_SIZE_SP = "youtube_chat_font_size_sp"
        private const val PREF_YOUTUBE_CHAT_MAX_MESSAGES = "youtube_chat_max_messages"
        private const val PREF_CHAT_BOTTOM_OFFSET_DP = "chat_bottom_offset_dp"
        private const val PREF_TWITCH_STREAM_KEY = "twitch_stream_key"
        private const val PREF_TWITCH_TITLE = "twitch_title"
        private const val PREF_TWITCH_CATEGORY_ID = "twitch_category_id"
        private const val PREF_TWITCH_DEVICE_CLIENT_ID = "twitch_device_client_id"
        private const val PREF_TWITCH_DEVICE_REFRESH_TOKEN = "twitch_device_refresh_token"
        private const val PREF_TWITCH_USER_ID = "twitch_user_id"
        private const val PREF_TWITCH_USER_LOGIN = "twitch_user_login"
        private const val PREF_TWITCH_CHANNEL_TITLE = "twitch_channel_title"
        private const val PREF_TWITCH_INGEST_SERVER_URL = "twitch_ingest_server_url"
        private const val PREF_TWITCH_VIDEO_BITRATE = "twitch_video_bitrate"
        private const val PREF_TWITCH_CHAT_ENABLED = "twitch_chat_enabled"
        private const val PREF_TWITCH_CHAT_FONT_SIZE_SP = "twitch_chat_font_size_sp"
        private const val PREF_TWITCH_CHAT_MAX_MESSAGES = "twitch_chat_max_messages"
        private const val PREF_CUSTOM_RTMP_SERVER_URL = "custom_rtmp_server_url"
        private const val PREF_CUSTOM_RTMP_STREAM_KEY = "custom_rtmp_stream_key"
        private const val PREF_CUSTOM_VIDEO_BITRATE = "custom_video_bitrate"
        private const val PREF_CUSTOM_CHAT_ENABLED = "custom_chat_enabled"
        private const val PREF_CUSTOM_CHAT_CHANNEL = "custom_chat_channel"
        private const val PREF_CUSTOM_CHAT_FONT_SIZE_SP = "custom_chat_font_size_sp"
        private const val PREF_CUSTOM_CHAT_MAX_MESSAGES = "custom_chat_max_messages"
        private const val YOUTUBE_FIX_HORIZONTAL_MIRROR = true
        private const val YOUTUBE_INGEST_POLL_ATTEMPTS = 24
        private const val YOUTUBE_INGEST_POLL_DELAY_MS = 2_500L
        private const val YOUTUBE_CHAT_SEEN_ID_TTL_MS = 15 * 60 * 1000L
        private const val TWITCH_INGEST_FALLBACK_LIMIT = 6
        private const val REVERSE_PORT_SPREAD = 1_000
    }
}

private fun requiredP2pPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

private fun StatusMessage.p2pPeerSummary(): String {
    return listOfNotNull(
        p2pDeviceName.takeIf { it.isNotBlank() },
        p2pDeviceAddress.takeIf { it.isNotBlank() }?.let { "($it)" },
        p2pRole.takeIf { it.isNotBlank() },
        p2pGroupOwnerAddress.takeIf { it.isNotBlank() }?.let { "owner=$it" }
    ).joinToString(" ")
}

private enum class StudioTab(val label: String) {
    HOME("Home"),
    YOUTUBE("YouTube"),
    TWITCH("Twitch"),
    CUSTOM("Custom RTMP"),
    SETTINGS("Settings")
}

private enum class YoutubeConnectionMode(val label: String) {
    STREAM_KEY("Stream key"),
    OAUTH("OAuth account")
}

private enum class StudioIcon {
    GLASSES,
    PHONE,
    WIFI,
    NETWORK,
    STOP,
    EXTERNAL,
    SETTINGS,
    CHEVRON,
    BACK,
    YOUTUBE,
    KEY,
    VIDEO,
    BITRATE,
    COPY,
    HOME,
    TWITCH,
    BROADCAST
}

private val StudioGreen = Color(0xFF5CF018)
private val StudioGreenSoft = Color(0xFF3DD90B)
private val StudioRed = Color(0xFFFF1F1F)
private val StudioPurple = Color(0xFF7C35FF)
private val StudioText = Color(0xFFF3F4F6)
private val StudioMuted = Color(0xFF9CA3AF)
private val StudioCardBase = Color(0xFF111413)
private val StudioCardAlt = Color(0xFF151918)
private val StudioBorder = Color(0xFF242A28)
private val StudioBgTop = Color(0xFF0B0E0D)
private val StudioBgBottom = Color(0xFF040605)
private val StudioShape = RoundedCornerShape(18.dp)
private val StudioSmallShape = RoundedCornerShape(12.dp)
private val YoutubeBitrateOptions = listOf(
    0,
    800_000,
    1_200_000,
    1_800_000,
    2_500_000,
    3_500_000,
    4_500_000,
    5_500_000,
    6_000_000,
    8_000_000
)
private val ChatBottomOffsetOptions = listOf(0, 20, 40, 60, 80, 120, 160, 200)
@Composable
private fun PhoneScreen(
    state: PhoneUiState,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    onConnectRokid: () -> Unit,
    onInstallHelper: () -> Unit,
    onLaunchHelper: () -> Unit,
    onStartP2pStream: () -> Unit,
    onYoutubeKeyChanged: (String) -> Unit,
    onYoutubeTitleChanged: (String) -> Unit,
    onYoutubeDescriptionChanged: (String) -> Unit,
    onYoutubeDeviceClientIdChanged: (String) -> Unit,
    onYoutubeDeviceClientSecretChanged: (String) -> Unit,
    onYoutubePrivacySelected: (YoutubePrivacy) -> Unit,
    onYoutubeCategorySelected: (YoutubeCategory) -> Unit,
    onYoutubeBitrateSelected: (Int) -> Unit,
    onYoutubeChatEnabledChange: (Boolean) -> Unit,
    onYoutubeChatFontSizeSelected: (Int) -> Unit,
    onYoutubeChatMaxMessagesSelected: (Int) -> Unit,
    onChatBottomOffsetSelected: (Int) -> Unit,
    onConnectYoutube: () -> Unit,
    onStartYoutubeDeviceAuth: () -> Unit,
    onOpenGoogleCloudDocs: () -> Unit,
    onDisconnectYoutube: () -> Unit,
    onRefreshYoutubeChannel: () -> Unit,
    onCreateYoutubeLive: () -> Unit,
    onTwitchKeyChanged: (String) -> Unit,
    onTwitchTitleChanged: (String) -> Unit,
    onTwitchDeviceClientIdChanged: (String) -> Unit,
    onTwitchCategorySelected: (TwitchCategory) -> Unit,
    onTwitchBitrateSelected: (Int) -> Unit,
    onTwitchChatEnabledChange: (Boolean) -> Unit,
    onTwitchChatFontSizeSelected: (Int) -> Unit,
    onTwitchChatMaxMessagesSelected: (Int) -> Unit,
    onCustomRtmpServerUrlChanged: (String) -> Unit,
    onCustomRtmpKeyChanged: (String) -> Unit,
    onCustomBitrateSelected: (Int) -> Unit,
    onCustomChatEnabledChange: (Boolean) -> Unit,
    onCustomChatChannelChanged: (String) -> Unit,
    onCustomChatFontSizeSelected: (Int) -> Unit,
    onCustomChatMaxMessagesSelected: (Int) -> Unit,
    onStartTwitchDeviceAuth: () -> Unit,
    onOpenTwitchDocs: () -> Unit,
    onDisconnectTwitch: () -> Unit,
    onRefreshTwitchChannel: () -> Unit,
    onStartTwitch: () -> Unit,
    onStopTwitch: () -> Unit,
    onStartCustom: () -> Unit,
    onStopCustom: () -> Unit,
    onPresetSelected: (VideoPreset) -> Unit,
    onPreviewRotationSelected: (Int) -> Unit,
    onStartYoutube: () -> Unit,
    onStopYoutube: () -> Unit,
    onStopStream: () -> Unit,
    onOpenReleases: () -> Unit,
    onUpdateAction: () -> Unit,
    onRequestKeyFrame: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(StudioTab.HOME) }
    var showPreview by remember { mutableStateOf(true) }

    LaunchedEffect(state.previewAutoHideRequest) {
        if (state.previewAutoHideRequest > 0) showPreview = false
    }

    BackHandler(enabled = selectedTab != StudioTab.HOME) {
        selectedTab = StudioTab.HOME
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = StudioBgBottom,
            surface = StudioCardBase,
            primary = StudioGreen,
            onPrimary = Color.Black,
            onSurface = StudioText
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(StudioBgTop, StudioBgBottom)))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            when (selectedTab) {
                StudioTab.HOME -> HomeStudioScreen(
                    state = state,
                    showPreview = showPreview,
                    onShowPreviewChange = { showPreview = it },
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone,
                    onConnectRokid = onConnectRokid,
                    onInstallHelper = onInstallHelper,
                    onLaunchHelper = onLaunchHelper,
                    onStartP2pStream = onStartP2pStream,
                    onStopStream = onStopStream,
                    onOpenYoutube = { selectedTab = StudioTab.YOUTUBE }
                )

                StudioTab.YOUTUBE -> YoutubeStudioScreen(
                    state = state,
                    showPreview = showPreview,
                    onShowPreviewChange = { showPreview = it },
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone,
                    onBack = { selectedTab = StudioTab.HOME },
                    onYoutubeKeyChanged = onYoutubeKeyChanged,
                    onYoutubeTitleChanged = onYoutubeTitleChanged,
                    onYoutubeDescriptionChanged = onYoutubeDescriptionChanged,
                    onYoutubeDeviceClientIdChanged = onYoutubeDeviceClientIdChanged,
                    onYoutubeDeviceClientSecretChanged = onYoutubeDeviceClientSecretChanged,
                    onYoutubePrivacySelected = onYoutubePrivacySelected,
                    onYoutubeCategorySelected = onYoutubeCategorySelected,
                    onYoutubeBitrateSelected = onYoutubeBitrateSelected,
                    onYoutubeChatEnabledChange = onYoutubeChatEnabledChange,
                    onYoutubeChatFontSizeSelected = onYoutubeChatFontSizeSelected,
                    onYoutubeChatMaxMessagesSelected = onYoutubeChatMaxMessagesSelected,
                    onChatBottomOffsetSelected = onChatBottomOffsetSelected,
                    onConnectYoutube = onConnectYoutube,
                    onStartYoutubeDeviceAuth = onStartYoutubeDeviceAuth,
                    onOpenGoogleCloudDocs = onOpenGoogleCloudDocs,
                    onDisconnectYoutube = onDisconnectYoutube,
                    onRefreshYoutubeChannel = onRefreshYoutubeChannel,
                    onCreateYoutubeLive = onCreateYoutubeLive,
                    onPresetSelected = onPresetSelected,
                    onPreviewRotationSelected = onPreviewRotationSelected,
                    onStartYoutube = onStartYoutube,
                    onStopYoutube = onStopYoutube
                )

                StudioTab.TWITCH -> TwitchStudioScreen(
                    state = state,
                    showPreview = showPreview,
                    onShowPreviewChange = { showPreview = it },
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone,
                    onBack = { selectedTab = StudioTab.HOME },
                    onTwitchKeyChanged = onTwitchKeyChanged,
                    onTwitchTitleChanged = onTwitchTitleChanged,
                    onTwitchDeviceClientIdChanged = onTwitchDeviceClientIdChanged,
                    onTwitchCategorySelected = onTwitchCategorySelected,
                    onTwitchBitrateSelected = onTwitchBitrateSelected,
                    onTwitchChatEnabledChange = onTwitchChatEnabledChange,
                    onTwitchChatFontSizeSelected = onTwitchChatFontSizeSelected,
                    onTwitchChatMaxMessagesSelected = onTwitchChatMaxMessagesSelected,
                    onChatBottomOffsetSelected = onChatBottomOffsetSelected,
                    onStartTwitchDeviceAuth = onStartTwitchDeviceAuth,
                    onOpenTwitchDocs = onOpenTwitchDocs,
                    onDisconnectTwitch = onDisconnectTwitch,
                    onRefreshTwitchChannel = onRefreshTwitchChannel,
                    onPresetSelected = onPresetSelected,
                    onPreviewRotationSelected = onPreviewRotationSelected,
                    onStartTwitch = onStartTwitch,
                    onStopTwitch = onStopTwitch
                )

                StudioTab.CUSTOM -> CustomRtmpStudioScreen(
                    state = state,
                    showPreview = showPreview,
                    onShowPreviewChange = { showPreview = it },
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone,
                    onBack = { selectedTab = StudioTab.HOME },
                    onServerUrlChanged = onCustomRtmpServerUrlChanged,
                    onStreamKeyChanged = onCustomRtmpKeyChanged,
                    onBitrateSelected = onCustomBitrateSelected,
                    onChatEnabledChange = onCustomChatEnabledChange,
                    onChatChannelChanged = onCustomChatChannelChanged,
                    onChatFontSizeSelected = onCustomChatFontSizeSelected,
                    onChatMaxMessagesSelected = onCustomChatMaxMessagesSelected,
                    onChatBottomOffsetSelected = onChatBottomOffsetSelected,
                    onPresetSelected = onPresetSelected,
                    onPreviewRotationSelected = onPreviewRotationSelected,
                    onStartCustom = onStartCustom,
                    onStopCustom = onStopCustom
                )

                StudioTab.SETTINGS -> SettingsStudioScreen(
                    state = state,
                    onOpenReleases = onOpenReleases,
                    onUpdateAction = onUpdateAction
                )
            }

            if (
                selectedTab != StudioTab.YOUTUBE &&
                selectedTab != StudioTab.TWITCH &&
                selectedTab != StudioTab.CUSTOM
            ) {
                BottomStudioNav(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun PreviewSurface(
    preset: VideoPreset,
    rotationDegrees: Int,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    modifier: Modifier = Modifier,
    containerAspectRatio: Float? = null
) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(containerAspectRatio ?: preset.displayAspectRatio(rotationDegrees))
            .background(Color.Black),
        factory = { context ->
            RotatedPreviewView(
                context = context,
                initialPreset = preset,
                initialRotationDegrees = rotationDegrees,
                onSurfaceReady = onSurfaceReady,
                onSurfaceGone = onSurfaceGone
            )
        },
        update = { view ->
            view.updatePreview(
                nextPreset = preset,
                nextRotationDegrees = rotationDegrees
            )
        }
    )
}

private class RotatedPreviewView(
    context: Context,
    initialPreset: VideoPreset,
    initialRotationDegrees: Int,
    private val onSurfaceReady: (AndroidSurface) -> Unit,
    private val onSurfaceGone: () -> Unit
) : FrameLayout(context) {
    private val textureView = TextureView(context)
    private var preset = initialPreset
    private var rotationDegrees = initialRotationDegrees.normalizedRotation()
    private var decodeSurface: AndroidSurface? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        textureView.rotation = rotationDegrees.toFloat()
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(preset.width, preset.height)
                decodeSurface?.release()
                decodeSurface = AndroidSurface(surface).also(onSurfaceReady)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(preset.width, preset.height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                onSurfaceGone()
                decodeSurface?.release()
                decodeSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        addView(textureView)
    }

    fun updatePreview(nextPreset: VideoPreset, nextRotationDegrees: Int) {
        val normalizedRotation = nextRotationDegrees.normalizedRotation()
        val changed = preset != nextPreset || rotationDegrees != normalizedRotation
        preset = nextPreset
        rotationDegrees = normalizedRotation
        textureView.rotation = rotationDegrees.toFloat()
        textureView.surfaceTexture?.setDefaultBufferSize(preset.width, preset.height)
        if (changed) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val (childWidth, childHeight) = fittedTextureSize(measuredWidth, measuredHeight)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val parentWidth = right - left
        val parentHeight = bottom - top
        val childWidth = textureView.measuredWidth
        val childHeight = textureView.measuredHeight
        val childLeft = (parentWidth - childWidth) / 2
        val childTop = (parentHeight - childHeight) / 2
        textureView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        textureView.pivotX = childWidth / 2f
        textureView.pivotY = childHeight / 2f
    }

    private fun fittedTextureSize(parentWidth: Int, parentHeight: Int): Pair<Int, Int> {
        if (parentWidth <= 0 || parentHeight <= 0) return 0 to 0
        val sourceAspect = preset.originalAspectRatio()
        val visualAspect = if (rotationDegrees.isQuarterTurn()) 1f / sourceAspect else sourceAspect
        var visualWidth = parentWidth.toFloat()
        var visualHeight = visualWidth / visualAspect
        if (visualHeight > parentHeight) {
            visualHeight = parentHeight.toFloat()
            visualWidth = visualHeight * visualAspect
        }
        return if (rotationDegrees.isQuarterTurn()) {
            visualHeight.roundToInt().coerceAtLeast(1) to visualWidth.roundToInt().coerceAtLeast(1)
        } else {
            visualWidth.roundToInt().coerceAtLeast(1) to visualHeight.roundToInt().coerceAtLeast(1)
        }
    }
}

private fun VideoPreset.originalAspectRatio(): Float = width.toFloat() / height.toFloat()

private fun VideoPreset.displayAspectRatio(rotationDegrees: Int): Float =
    if (rotationDegrees.normalizedRotation().isQuarterTurn()) {
        height.toFloat() / width.toFloat()
    } else {
        originalAspectRatio()
    }

private fun VideoPreset.dimensionSummary(rotationDegrees: Int = displayRotationDegrees): String =
    buildString {
        val display = if (rotationDegrees.normalizedRotation().isQuarterTurn()) {
            "${height}x${width}"
        } else {
            "${width}x${height}"
        }
        val youtubeWidth = youtubeOutputWidth.takeIf { it > 0 }
        val youtubeHeight = youtubeOutputHeight.takeIf { it > 0 }
        append(display)
        if (rotationDegrees.normalizedRotation().isQuarterTurn()) {
            append(" display, ${width}x${height} encoded")
        }
        if (youtubeWidth != null && youtubeHeight != null) {
            append(" -> ${youtubeWidth}x${youtubeHeight} YT")
        }
    }

private fun VideoPreset.bitrateSummary(): String =
    if (youtubeVideoBitrate > 0 && youtubeVideoBitrate != videoBitrate) {
        "${videoBitrate / 1_000_000.0} Mbps capture, ${youtubeVideoBitrate / 1_000_000.0} Mbps YT"
    } else {
        "${videoBitrate / 1_000_000.0} Mbps"
    }

private fun Int.normalizedRotation(): Int = ((this % 360) + 360) % 360

private fun Int.inverseRotation(): Int = (360 - normalizedRotation()) % 360

private fun Int.isQuarterTurn(): Boolean = this == 90 || this == 270

@Composable
private fun HomeStudioScreen(
    state: PhoneUiState,
    showPreview: Boolean,
    onShowPreviewChange: (Boolean) -> Unit,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    onConnectRokid: () -> Unit,
    onInstallHelper: () -> Unit,
    onLaunchHelper: () -> Unit,
    onStartP2pStream: () -> Unit,
    onStopStream: () -> Unit,
    onOpenYoutube: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StudioBrandHeader()

        DeviceStatusCard(
            state = state,
            onClick = onConnectRokid
        )

        StudioActionCard(
            title = "Authorize Rokid",
            subtitle = "Hi Rokid auth and CXR connection",
            icon = StudioIcon.GLASSES,
            onClick = onConnectRokid,
            trailing = { IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(22.dp)) }
        )

        StudioActionCard(
            title = "Launch Helper App",
            subtitle = state.helperInstallStatus.ifBlank { "Open the glasses companion" },
            icon = StudioIcon.EXTERNAL,
            onClick = onLaunchHelper,
            trailing = {
                val installBusy = state.helperInstallBusy
                Text(
                    text = if (installBusy) "Installing..." else "Install",
                    color = if (installBusy) StudioMuted else StudioGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !installBusy, onClick = onInstallHelper)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        )

        StudioActionCard(
            title = "P2P Wi-Fi",
            subtitle = state.p2pStatus.ifBlank {
                if (state.p2pConnected) "Direct glasses link ready" else "Direct outdoor streaming link"
            },
            icon = StudioIcon.WIFI,
            onClick = onStartP2pStream,
            trailing = { IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(22.dp)) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioSquareButton(
                modifier = Modifier.weight(1f),
                title = "Start on Phone",
                icon = StudioIcon.PHONE,
                active = state.streaming || state.p2pRunning,
                onClick = onStartP2pStream
            )
            StudioSquareButton(
                modifier = Modifier.weight(1f),
                title = "Stop",
                icon = StudioIcon.STOP,
                active = false,
                onClick = onStopStream
            )
        }

        PreviewHudCard(
            state = state,
            showPreview = showPreview,
            previewActionLabel = "Show Preview",
            onShowPreviewChange = onShowPreviewChange,
            onSurfaceReady = onSurfaceReady,
            onSurfaceGone = onSurfaceGone
        )

        ErrorBanner(state.error)
    }
}

@Composable
private fun YoutubeStudioScreen(
    state: PhoneUiState,
    showPreview: Boolean,
    onShowPreviewChange: (Boolean) -> Unit,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    onBack: () -> Unit,
    onYoutubeKeyChanged: (String) -> Unit,
    onYoutubeTitleChanged: (String) -> Unit,
    onYoutubeDescriptionChanged: (String) -> Unit,
    onYoutubeDeviceClientIdChanged: (String) -> Unit,
    onYoutubeDeviceClientSecretChanged: (String) -> Unit,
    onYoutubePrivacySelected: (YoutubePrivacy) -> Unit,
    onYoutubeCategorySelected: (YoutubeCategory) -> Unit,
    onYoutubeBitrateSelected: (Int) -> Unit,
    onYoutubeChatEnabledChange: (Boolean) -> Unit,
    onYoutubeChatFontSizeSelected: (Int) -> Unit,
    onYoutubeChatMaxMessagesSelected: (Int) -> Unit,
    onChatBottomOffsetSelected: (Int) -> Unit,
    onConnectYoutube: () -> Unit,
    onStartYoutubeDeviceAuth: () -> Unit,
    onOpenGoogleCloudDocs: () -> Unit,
    onDisconnectYoutube: () -> Unit,
    onRefreshYoutubeChannel: () -> Unit,
    onCreateYoutubeLive: () -> Unit,
    onPresetSelected: (VideoPreset) -> Unit,
    onPreviewRotationSelected: (Int) -> Unit,
    onStartYoutube: () -> Unit,
    onStopYoutube: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var showYoutubeHelp by remember { mutableStateOf(false) }
    var selectedYoutubeMode by remember {
        mutableStateOf(
            if (state.youtubeConnected) YoutubeConnectionMode.OAUTH else YoutubeConnectionMode.STREAM_KEY
        )
    }
    val clipboard = LocalClipboardManager.current
    val oauthMode = selectedYoutubeMode == YoutubeConnectionMode.OAUTH
    val streamKeyMode = selectedYoutubeMode == YoutubeConnectionMode.STREAM_KEY
    val canUsePrimaryButton = state.youtubeLive ||
        if (oauthMode) state.youtubeConnected else state.youtubeStreamKey.isNotBlank()
    val primaryText = when {
        state.youtubeLive -> "End YouTube Live"
        oauthMode && state.youtubeConnected -> "Create live and start stream"
        oauthMode -> "Connect YouTube account first"
        else -> "Start stream with key"
    }

    LaunchedEffect(state.youtubeConnected) {
        if (state.youtubeConnected) {
            selectedYoutubeMode = YoutubeConnectionMode.OAUTH
        }
    }

    BackHandler(enabled = showYoutubeHelp) {
        showYoutubeHelp = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 112.dp)
        ) {
            YoutubeTopBar(onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YoutubeModeSelector(
                    selected = selectedYoutubeMode,
                    enabled = !state.youtubeLive,
                    onSelected = { selectedYoutubeMode = it },
                    onHelp = { showYoutubeHelp = true }
                )

                StudioTextInputCard(
                    icon = StudioIcon.GLASSES,
                    label = "Stream Title",
                    value = if (oauthMode) state.youtubeTitle else "",
                    onValueChange = onYoutubeTitleChanged,
                    placeholder = if (oauthMode) "Rokid Live - AR Adventures" else "Set in YouTube Studio with stream key mode",
                    enabled = oauthMode && !state.youtubeLive,
                    helperText = if (oauthMode) {
                        "OAuth-created lives can be named from the app."
                    } else {
                        "Stream key mode only sends video and audio. Title, category, thumbnail and visibility stay in YouTube Studio."
                    },
                    trailing = {
                        Text(
                            if (oauthMode) "${state.youtubeTitle.length}/100" else "Studio",
                            color = if (oauthMode) StudioMuted else StudioMuted.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                )

                StudioTextInputCard(
                    icon = StudioIcon.KEY,
                    label = "Stream Key",
                    value = state.youtubeStreamKey,
                    onValueChange = onYoutubeKeyChanged,
                    placeholder = if (streamKeyMode) "Paste YouTube stream key" else "Created automatically by OAuth live setup",
                    password = !showKey,
                    enabled = streamKeyMode && !state.youtubeLive,
                    helperText = if (streamKeyMode) {
                        "No OAuth or TV client ID needed. Paste the key from YouTube Studio."
                    } else {
                        "OAuth mode creates a YouTube live, fetches its stream key, then uses it automatically."
                    },
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (showKey) "Hide" else "Show",
                                color = StudioGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showKey = !showKey }
                            )
                            IconGlyph(
                                StudioIcon.COPY,
                                StudioMuted,
                                Modifier
                                    .size(24.dp)
                                    .clickable {
                                        if (state.youtubeStreamKey.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(state.youtubeStreamKey))
                                        }
                                    }
                            )
                        }
                    }
                )

                StudioSelectCard(
                    label = "Resolution",
                    value = state.selectedPreset.youtubeResolutionLabel(state.previewRotationDegrees),
                    icon = StudioIcon.VIDEO,
                    enabled = !state.youtubeLive,
                    options = VideoPreset.Visible,
                    selected = state.selectedPreset,
                    onSelected = onPresetSelected
                )
                ResolutionHeatWarning(state.selectedPreset)

                YoutubeBitrateCard(
                    selectedBitrate = state.youtubeVideoBitrateOverride,
                    autoBitrate = state.selectedPreset.defaultYoutubeBitrate(),
                    enabled = !state.youtubeLive,
                    onSelected = onYoutubeBitrateSelected
                )

                RtmpDiagnosticCard(
                    platformName = "YouTube",
                    diagnostics = state.youtubeRtmpDiagnostics
                )

                PreviewHudCard(
                    state = state,
                    showPreview = showPreview,
                    previewActionLabel = "Hide Preview",
                    onShowPreviewChange = onShowPreviewChange,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone
                )

                YoutubeLoginCard(
                    state = state,
                    onShowHelp = { showYoutubeHelp = true },
                    onStartYoutubeDeviceAuth = onStartYoutubeDeviceAuth,
                    onRefreshYoutubeChannel = onRefreshYoutubeChannel,
                    onDisconnectYoutube = onDisconnectYoutube,
                    visible = oauthMode
                )

                if (streamKeyMode) {
                    YoutubeModeHintCard(onShowHelp = { showYoutubeHelp = true })
                }

                ErrorBanner(state.error)

                RotationSelector(
                    selectedRotation = state.previewRotationDegrees,
                    enabled = !state.youtubeLive,
                    onSelected = onPreviewRotationSelected
                )

                PrivacySelector(
                    selected = state.youtubePrivacy,
                    enabled = oauthMode && !state.youtubeLive,
                    onSelected = onYoutubePrivacySelected
                )

                YoutubeCategoryCard(
                    selected = YoutubeCategory.fromId(state.youtubeCategoryId),
                    enabled = oauthMode && !state.youtubeLive,
                    oauthMode = oauthMode,
                    onSelected = onYoutubeCategorySelected
                )

                if (!oauthMode) {
                    SmallInfoText("Visibility and category are controlled in YouTube Studio when using a stream key.")
                }

                if (oauthMode) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { advancedOpen = !advancedOpen }
                    ) {
                        Text(
                            if (advancedOpen) "Hide OAuth setup" else "Advanced OAuth setup",
                            color = StudioGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (advancedOpen) {
                        AdvancedYoutubeSetup(
                            state = state,
                            enabled = !state.youtubeLive,
                            onYoutubeDescriptionChanged = onYoutubeDescriptionChanged,
                            onYoutubeDeviceClientIdChanged = onYoutubeDeviceClientIdChanged,
                            onYoutubeDeviceClientSecretChanged = onYoutubeDeviceClientSecretChanged,
                            onOpenGoogleCloudDocs = onOpenGoogleCloudDocs
                        )
                    }
                }

                YoutubeChatCard(
                    state = state,
                    oauthMode = oauthMode,
                    onEnabledChange = onYoutubeChatEnabledChange,
                    onFontSizeSelected = onYoutubeChatFontSizeSelected,
                    onMaxMessagesSelected = onYoutubeChatMaxMessagesSelected,
                    onBottomOffsetSelected = onChatBottomOffsetSelected
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(118.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, StudioBgBottom, StudioBgBottom)))
        )

        StudioPrimaryButton(
            text = primaryText,
            icon = if (state.youtubeLive) StudioIcon.STOP else StudioIcon.BROADCAST,
            enabled = canUsePrimaryButton,
            onClick = {
                if (state.youtubeLive) {
                    onStopYoutube()
                } else if (oauthMode) {
                    onCreateYoutubeLive()
                } else {
                    onStartYoutube()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
        )

        if (showYoutubeHelp) {
            YoutubeModeHelpDialog(
                onDismiss = { showYoutubeHelp = false },
                onUseStreamKey = {
                    selectedYoutubeMode = YoutubeConnectionMode.STREAM_KEY
                    showYoutubeHelp = false
                },
                onUseOAuth = {
                    selectedYoutubeMode = YoutubeConnectionMode.OAUTH
                    showYoutubeHelp = false
                },
                onStartDeviceAuth = {
                    selectedYoutubeMode = YoutubeConnectionMode.OAUTH
                    showYoutubeHelp = false
                    onStartYoutubeDeviceAuth()
                },
                onGoogleSignIn = {
                    selectedYoutubeMode = YoutubeConnectionMode.OAUTH
                    showYoutubeHelp = false
                    onConnectYoutube()
                },
                onOpenGoogleCloudDocs = onOpenGoogleCloudDocs
            )
        }
    }
}

@Composable
private fun TwitchStudioScreen(
    state: PhoneUiState,
    showPreview: Boolean,
    onShowPreviewChange: (Boolean) -> Unit,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    onBack: () -> Unit,
    onTwitchKeyChanged: (String) -> Unit,
    onTwitchTitleChanged: (String) -> Unit,
    onTwitchDeviceClientIdChanged: (String) -> Unit,
    onTwitchCategorySelected: (TwitchCategory) -> Unit,
    onTwitchBitrateSelected: (Int) -> Unit,
    onTwitchChatEnabledChange: (Boolean) -> Unit,
    onTwitchChatFontSizeSelected: (Int) -> Unit,
    onTwitchChatMaxMessagesSelected: (Int) -> Unit,
    onChatBottomOffsetSelected: (Int) -> Unit,
    onStartTwitchDeviceAuth: () -> Unit,
    onOpenTwitchDocs: () -> Unit,
    onDisconnectTwitch: () -> Unit,
    onRefreshTwitchChannel: () -> Unit,
    onPresetSelected: (VideoPreset) -> Unit,
    onPreviewRotationSelected: (Int) -> Unit,
    onStartTwitch: () -> Unit,
    onStopTwitch: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var showTwitchHelp by remember { mutableStateOf(false) }
    var selectedTwitchMode by remember {
        mutableStateOf(
            if (state.twitchConnected) YoutubeConnectionMode.OAUTH else YoutubeConnectionMode.STREAM_KEY
        )
    }
    val clipboard = LocalClipboardManager.current
    val oauthMode = selectedTwitchMode == YoutubeConnectionMode.OAUTH
    val streamKeyMode = selectedTwitchMode == YoutubeConnectionMode.STREAM_KEY
    val canUsePrimaryButton = state.twitchLive ||
        if (oauthMode) state.twitchConnected else state.twitchStreamKey.isNotBlank()
    val primaryText = when {
        state.twitchLive -> "End Twitch Stream"
        oauthMode && state.twitchConnected -> "Update channel and start stream"
        oauthMode -> "Connect Twitch account first"
        else -> "Start stream with key"
    }

    LaunchedEffect(state.twitchConnected) {
        if (state.twitchConnected) {
            selectedTwitchMode = YoutubeConnectionMode.OAUTH
        }
    }

    BackHandler(enabled = showTwitchHelp) {
        showTwitchHelp = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 112.dp)
        ) {
            TwitchTopBar(onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwitchModeSelector(
                    selected = selectedTwitchMode,
                    enabled = !state.twitchLive,
                    onSelected = { selectedTwitchMode = it },
                    onHelp = { showTwitchHelp = true }
                )

                StudioTextInputCard(
                    icon = StudioIcon.GLASSES,
                    label = "Stream Title",
                    value = if (oauthMode) state.twitchTitle else "",
                    onValueChange = onTwitchTitleChanged,
                    placeholder = if (oauthMode) "Rokid Live - AR Adventures" else "Set in Twitch Creator Dashboard with stream key mode",
                    enabled = oauthMode && !state.twitchLive,
                    helperText = if (oauthMode) {
                        "OAuth mode updates the Twitch channel title before streaming."
                    } else {
                        "Stream key mode only sends video and audio. Title and category stay in Twitch."
                    },
                    trailing = {
                        Text(
                            if (oauthMode) "${state.twitchTitle.length}/140" else "Dashboard",
                            color = StudioMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                )

                StudioTextInputCard(
                    icon = StudioIcon.KEY,
                    label = "Stream Key",
                    value = state.twitchStreamKey,
                    onValueChange = onTwitchKeyChanged,
                    placeholder = if (streamKeyMode) "Paste Twitch stream key" else "Fetched automatically by OAuth",
                    password = !showKey,
                    enabled = streamKeyMode && !state.twitchLive,
                    helperText = if (streamKeyMode) {
                        "No OAuth needed. Paste the key from Twitch Creator Dashboard."
                    } else {
                        "OAuth mode retrieves the Twitch stream key automatically."
                    },
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (showKey) "Hide" else "Show",
                                color = StudioGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showKey = !showKey }
                            )
                            IconGlyph(
                                StudioIcon.COPY,
                                StudioMuted,
                                Modifier
                                    .size(24.dp)
                                    .clickable {
                                        if (state.twitchStreamKey.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(state.twitchStreamKey))
                                        }
                                    }
                            )
                        }
                    }
                )

                StudioSelectCard(
                    label = "Resolution",
                    value = state.selectedPreset.youtubeResolutionLabel(state.previewRotationDegrees),
                    icon = StudioIcon.VIDEO,
                    enabled = !state.twitchLive,
                    options = VideoPreset.Visible,
                    selected = state.selectedPreset,
                    onSelected = onPresetSelected
                )
                ResolutionHeatWarning(state.selectedPreset)

                PlatformBitrateCard(
                    platformName = "Twitch",
                    selectedBitrate = state.twitchVideoBitrateOverride,
                    autoBitrate = state.selectedPreset.defaultYoutubeBitrate(),
                    enabled = !state.twitchLive,
                    onSelected = onTwitchBitrateSelected
                )

                RtmpDiagnosticCard(
                    platformName = "Twitch",
                    diagnostics = state.twitchRtmpDiagnostics
                )

                PreviewHudCard(
                    state = state,
                    showPreview = showPreview,
                    previewActionLabel = "Hide Preview",
                    onShowPreviewChange = onShowPreviewChange,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone
                )

                TwitchLoginCard(
                    state = state,
                    onShowHelp = { showTwitchHelp = true },
                    onStartTwitchDeviceAuth = onStartTwitchDeviceAuth,
                    onRefreshTwitchChannel = onRefreshTwitchChannel,
                    onDisconnectTwitch = onDisconnectTwitch,
                    visible = oauthMode
                )

                if (streamKeyMode) {
                    TwitchModeHintCard(onShowHelp = { showTwitchHelp = true })
                }

                ErrorBanner(state.error)

                RotationSelector(
                    selectedRotation = state.previewRotationDegrees,
                    enabled = !state.twitchLive,
                    onSelected = onPreviewRotationSelected
                )

                DisabledSettingCard(
                    label = "Visibility",
                    value = "Public",
                    reason = "Twitch goes public when the RTMP stream starts. Use YouTube for private or unlisted lives."
                )

                TwitchCategoryCard(
                    selected = TwitchCategory.fromId(state.twitchCategoryId),
                    enabled = oauthMode && !state.twitchLive,
                    oauthMode = oauthMode,
                    onSelected = onTwitchCategorySelected
                )

                if (!oauthMode) {
                    SmallInfoText("Title and category are controlled in Twitch Creator Dashboard when using a stream key.")
                }

                if (oauthMode) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { advancedOpen = !advancedOpen }
                    ) {
                        Text(
                            if (advancedOpen) "Hide OAuth setup" else "Advanced OAuth setup",
                            color = StudioGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (advancedOpen) {
                        AdvancedTwitchSetup(
                            state = state,
                            enabled = !state.twitchLive,
                            onTwitchDeviceClientIdChanged = onTwitchDeviceClientIdChanged,
                            onOpenTwitchDocs = onOpenTwitchDocs
                        )
                    }
                }

                TwitchChatCard(
                    state = state,
                    oauthMode = oauthMode,
                    onEnabledChange = onTwitchChatEnabledChange,
                    onFontSizeSelected = onTwitchChatFontSizeSelected,
                    onMaxMessagesSelected = onTwitchChatMaxMessagesSelected,
                    onBottomOffsetSelected = onChatBottomOffsetSelected
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(118.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, StudioBgBottom, StudioBgBottom)))
        )

        StudioPrimaryButton(
            text = primaryText,
            icon = if (state.twitchLive) StudioIcon.STOP else StudioIcon.BROADCAST,
            enabled = canUsePrimaryButton,
            onClick = {
                if (state.twitchLive) {
                    onStopTwitch()
                } else {
                    onStartTwitch()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
        )

        if (showTwitchHelp) {
            TwitchModeHelpDialog(
                onDismiss = { showTwitchHelp = false },
                onUseStreamKey = {
                    selectedTwitchMode = YoutubeConnectionMode.STREAM_KEY
                    showTwitchHelp = false
                },
                onUseOAuth = {
                    selectedTwitchMode = YoutubeConnectionMode.OAUTH
                    showTwitchHelp = false
                },
                onStartDeviceAuth = {
                    selectedTwitchMode = YoutubeConnectionMode.OAUTH
                    showTwitchHelp = false
                    onStartTwitchDeviceAuth()
                },
                onOpenTwitchDocs = onOpenTwitchDocs
            )
        }
    }
}

@Composable
private fun CustomRtmpStudioScreen(
    state: PhoneUiState,
    showPreview: Boolean,
    onShowPreviewChange: (Boolean) -> Unit,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit,
    onBack: () -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onStreamKeyChanged: (String) -> Unit,
    onBitrateSelected: (Int) -> Unit,
    onChatEnabledChange: (Boolean) -> Unit,
    onChatChannelChanged: (String) -> Unit,
    onChatFontSizeSelected: (Int) -> Unit,
    onChatMaxMessagesSelected: (Int) -> Unit,
    onChatBottomOffsetSelected: (Int) -> Unit,
    onPresetSelected: (VideoPreset) -> Unit,
    onPreviewRotationSelected: (Int) -> Unit,
    onStartCustom: () -> Unit,
    onStopCustom: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val canUsePrimaryButton = state.customLive ||
        (state.customRtmpServerUrl.isNotBlank() && state.customRtmpStreamKey.isNotBlank())

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 112.dp)
        ) {
            CustomRtmpTopBar(onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioTextInputCard(
                    icon = StudioIcon.NETWORK,
                    label = "RTMP Server URL",
                    value = state.customRtmpServerUrl,
                    onValueChange = onServerUrlChanged,
                    placeholder = "rtmp://host/app",
                    enabled = !state.customLive,
                    helperText = "Direct RTMP/RTMPS destination. No YouTube or Twitch API is used."
                )

                StudioTextInputCard(
                    icon = StudioIcon.KEY,
                    label = "Stream Key",
                    value = state.customRtmpStreamKey,
                    onValueChange = onStreamKeyChanged,
                    placeholder = "Paste the gateway stream key",
                    password = !showKey,
                    enabled = !state.customLive,
                    helperText = "Stored securely on this phone.",
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (showKey) "Hide" else "Show",
                                color = StudioGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showKey = !showKey }
                            )
                            IconGlyph(
                                StudioIcon.COPY,
                                StudioMuted,
                                Modifier
                                    .size(24.dp)
                                    .clickable {
                                        if (state.customRtmpStreamKey.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(state.customRtmpStreamKey))
                                        }
                                    }
                            )
                        }
                    }
                )

                StudioSelectCard(
                    label = "Resolution",
                    value = state.selectedPreset.youtubeResolutionLabel(state.previewRotationDegrees),
                    icon = StudioIcon.VIDEO,
                    enabled = !state.customLive,
                    options = VideoPreset.Visible,
                    selected = state.selectedPreset,
                    onSelected = onPresetSelected
                )
                ResolutionHeatWarning(state.selectedPreset)

                PlatformBitrateCard(
                    platformName = "Custom RTMP",
                    selectedBitrate = state.customVideoBitrateOverride,
                    autoBitrate = state.selectedPreset.defaultYoutubeBitrate(),
                    enabled = !state.customLive,
                    onSelected = onBitrateSelected
                )

                CustomRtmpStatsCard(
                    live = state.customLive,
                    status = state.customStatus,
                    bytesSent = state.customBytesSent
                )

                RtmpDiagnosticCard(
                    platformName = "Custom RTMP",
                    diagnostics = state.customRtmpDiagnostics
                )

                PreviewHudCard(
                    state = state,
                    showPreview = showPreview,
                    previewActionLabel = "Hide Preview",
                    onShowPreviewChange = onShowPreviewChange,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceGone = onSurfaceGone
                )

                ErrorBanner(state.error)

                RotationSelector(
                    selectedRotation = state.previewRotationDegrees,
                    enabled = !state.customLive,
                    onSelected = onPreviewRotationSelected
                )

                CustomChatCard(
                    state = state,
                    onEnabledChange = onChatEnabledChange,
                    onChannelChanged = onChatChannelChanged,
                    onFontSizeSelected = onChatFontSizeSelected,
                    onMaxMessagesSelected = onChatMaxMessagesSelected,
                    onBottomOffsetSelected = onChatBottomOffsetSelected
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(118.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, StudioBgBottom, StudioBgBottom)))
        )

        StudioPrimaryButton(
            text = if (state.customLive) "End Custom RTMP Stream" else "Start Custom RTMP Stream",
            icon = if (state.customLive) StudioIcon.STOP else StudioIcon.BROADCAST,
            enabled = canUsePrimaryButton,
            onClick = {
                if (state.customLive) onStopCustom() else onStartCustom()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
        )
    }
}

@Composable
private fun SettingsStudioScreen(
    state: PhoneUiState,
    onOpenReleases: () -> Unit,
    onUpdateAction: () -> Unit
) {
    val update = state.update
    val updateButtonText = when {
        update.checking -> "Checking for Update"
        update.downloading -> "Downloading APK"
        update.available && update.apkPath.isNotBlank() -> "Open Installer"
        update.available -> "Install Update"
        else -> "Check for Update"
    }
    val updateEnabled = !update.checking && !update.downloading
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StudioBrandHeader()
        StudioCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconGlyph(StudioIcon.SETTINGS, StudioGreen, Modifier.size(28.dp))
                    Text("Settings", color = StudioText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                MiniMetric("Phone package", "com.anezium.rokidlive.phone")
                MiniMetric("Glasses helper", "com.anezium.rokidlive.glasses")
                MiniMetric("Selected resolution", state.selectedPreset.youtubeResolutionLabel(state.previewRotationDegrees))
            }
        }
        StudioCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconGlyph(StudioIcon.EXTERNAL, StudioGreen, Modifier.size(28.dp))
                    Text("Updates", color = StudioText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    update.status.ifBlank {
                        "Checks the official GitHub release, downloads the newest phone APK, then opens Android Package Installer."
                    },
                    color = StudioMuted,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
                StudioInfoCard(
                    label = "Installed",
                    value = "${update.currentVersionName} (${update.currentVersionCode})",
                    icon = StudioIcon.PHONE
                )
                if (update.available) {
                    StudioInfoCard(
                        label = "Available",
                        value = listOfNotNull(
                            update.latestVersionName.ifBlank { update.latestTag }.takeIf { it.isNotBlank() },
                            update.apkName.takeIf { it.isNotBlank() }
                        ).joinToString("  /  ").ifBlank { "APK available" },
                        icon = StudioIcon.BROADCAST
                    )
                }
                StudioPrimaryButton(
                    text = updateButtonText,
                    icon = StudioIcon.EXTERNAL,
                    enabled = updateEnabled,
                    onClick = onUpdateAction
                )
                CompactTextAction(
                    text = "Open GitHub Releases",
                    onClick = onOpenReleases
                )
            }
        }
    }
}

@Composable
private fun StudioBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Rokid",
                color = StudioGreen,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                " Live Studio",
                color = StudioText,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

@Composable
private fun YoutubeTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioBgTop),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_arrow_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(31.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                YoutubeBadge(Modifier.size(width = 44.dp, height = 31.dp))
                Text("YouTube", color = StudioText, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(StudioRed)
        )
    }
}

@Composable
private fun TwitchTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioBgTop),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_arrow_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(31.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwitchBadge(Modifier.size(width = 34.dp, height = 34.dp))
                Text("Twitch", color = StudioText, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(StudioPurple)
        )
    }
}

@Composable
private fun CustomRtmpTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioBgTop),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_arrow_back),
                    contentDescription = "Back",
                    modifier = Modifier.size(31.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconGlyph(StudioIcon.BROADCAST, StudioGreen, Modifier.size(34.dp))
                Text("Custom RTMP", color = StudioText, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(StudioGreen)
        )
    }
}

@Composable
private fun DeviceStatusCard(state: PhoneUiState, onClick: () -> Unit) {
    val connected = state.glassesConnected()
    val batteryPercent = state.glassesBatteryPercent
    val hasBattery = batteryPercent in 0..100
    val batteryTint = when {
        !hasBattery -> StudioMuted
        batteryPercent <= 15 -> Color(0xFFF87171)
        else -> StudioGreen
    }
    StudioCard(
        modifier = Modifier.height(68.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Rokid Glasses", color = StudioText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (connected) StudioGreen else Color(0xFF4B5563))
                )
                Text(
                    if (connected) "Connected" else "Connect",
                    color = if (connected) StudioGreen else StudioMuted,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
                BatteryGlyph(
                    level = if (hasBattery) batteryPercent / 100f else 0f,
                    tint = batteryTint
                )
                Text(
                    if (hasBattery) "$batteryPercent%" else "--%",
                    color = if (hasBattery) StudioText else StudioMuted,
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
private fun StudioActionCard(
    title: String,
    subtitle: String,
    icon: StudioIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = StudioGreen,
    trailing: @Composable (() -> Unit)? = null
) {
    StudioCard(
        modifier = modifier.heightIn(min = 64.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconGlyph(icon, iconTint, Modifier.size(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = StudioText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            color = StudioMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun StudioSquareButton(
    title: String,
    icon: StudioIcon,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(StudioShape)
            .background(if (active) Color(0xFF1D2C17) else StudioCardAlt)
            .border(1.dp, if (active) StudioGreen.copy(alpha = 0.45f) else StudioBorder, StudioShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconGlyph(icon, StudioGreen, Modifier.size(34.dp))
            Text(title, color = StudioText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PreviewHudCard(
    state: PhoneUiState,
    showPreview: Boolean,
    previewActionLabel: String,
    onShowPreviewChange: (Boolean) -> Unit,
    onSurfaceReady: (AndroidSurface) -> Unit,
    onSurfaceGone: () -> Unit
) {
    StudioCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Preview", color = StudioText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        previewActionLabel,
                        color = StudioGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Switch(
                        checked = showPreview,
                        onCheckedChange = onShowPreviewChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StudioGreen,
                            uncheckedThumbColor = Color(0xFFCBD5E1),
                            uncheckedTrackColor = Color(0xFF374151)
                        )
                    )
                }
            }

            if (showPreview) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFBBF24))
                    )
                    Text(
                        "May degrade streaming performance",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    PreviewSurface(
                        preset = state.selectedPreset,
                        rotationDegrees = state.previewRotationDegrees,
                        onSurfaceReady = onSurfaceReady,
                        onSurfaceGone = onSurfaceGone,
                        containerAspectRatio = 16f / 9f
                    )
                    if (state.framesReceived == 0L) {
                        PreviewEmptyState(Modifier.matchParentSize())
                    } else {
                        PreviewTelemetry(
                            state = state,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF030605)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "No preview signal yet",
                color = StudioText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Start P2P stream from the glasses",
                color = StudioMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PreviewTelemetry(state: PhoneUiState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconGlyph(StudioIcon.VIDEO, StudioGreen, Modifier.size(18.dp))
            Text(if (state.streaming) "REC" else "READY", color = StudioGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (state.framesReceived > 0) {
            Text(
                "${state.framesReceived} frames",
                color = StudioGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun StreamStatsStrip(state: PhoneUiState) {
    StudioCard(modifier = Modifier.heightIn(min = 54.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniMetric("Phone", "${state.phoneIp.ifBlank { "-" }}:${Protocol.DEFAULT_PORT}")
            MiniMetric("Frames", state.framesReceived.toString())
            MiniMetric("Dropped", state.droppedFrames.toString())
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = StudioMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, color = StudioText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun YoutubeModeSelector(
    selected: YoutubeConnectionMode,
    enabled: Boolean,
    onSelected: (YoutubeConnectionMode) -> Unit,
    onHelp: () -> Unit
) {
    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    Text("YouTube setup", color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose how this app is allowed to control the live.",
                        color = StudioMuted,
                        fontSize = 12.sp
                    )
                }
                CompactTextAction("Explain", onHelp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YoutubeModePill(
                    modifier = Modifier.weight(1f),
                    title = "Stream key",
                    subtitle = "RTMP only",
                    active = selected == YoutubeConnectionMode.STREAM_KEY,
                    enabled = enabled,
                    onClick = { onSelected(YoutubeConnectionMode.STREAM_KEY) }
                )
                YoutubeModePill(
                    modifier = Modifier.weight(1f),
                    title = "OAuth",
                    subtitle = "Create live",
                    active = selected == YoutubeConnectionMode.OAUTH,
                    enabled = enabled,
                    onClick = { onSelected(YoutubeConnectionMode.OAUTH) }
                )
            }
        }
    }
}

@Composable
private fun TwitchModeSelector(
    selected: YoutubeConnectionMode,
    enabled: Boolean,
    onSelected: (YoutubeConnectionMode) -> Unit,
    onHelp: () -> Unit
) {
    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    Text("Twitch setup", color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose whether the app only publishes RTMP or also manages your channel.",
                        color = StudioMuted,
                        fontSize = 12.sp
                    )
                }
                CompactTextAction("Explain", onHelp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YoutubeModePill(
                    modifier = Modifier.weight(1f),
                    title = "Stream key",
                    subtitle = "RTMP only",
                    active = selected == YoutubeConnectionMode.STREAM_KEY,
                    enabled = enabled,
                    onClick = { onSelected(YoutubeConnectionMode.STREAM_KEY) }
                )
                YoutubeModePill(
                    modifier = Modifier.weight(1f),
                    title = "OAuth",
                    subtitle = "Key + chat",
                    active = selected == YoutubeConnectionMode.OAUTH,
                    enabled = enabled,
                    onClick = { onSelected(YoutubeConnectionMode.OAUTH) }
                )
            }
        }
    }
}

@Composable
private fun YoutubeModePill(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        active && enabled -> StudioGreen
        active -> Color(0xFF23321F)
        else -> Color(0xFF0E1211)
    }
    val textColor = if (active && enabled) Color.Black else if (enabled) StudioText else StudioMuted
    Box(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, if (active && enabled) StudioGreen else StudioBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = if (active && enabled) Color.Black.copy(alpha = 0.72f) else StudioMuted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun YoutubeModeHintCard(onShowHelp: () -> Unit) {
    StudioCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                YoutubeBadge(Modifier.size(width = 38.dp, height = 27.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Stream key mode", color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "The phone sends the Rokid video/audio to an existing YouTube live. YouTube Studio keeps control of title, privacy, category and thumbnail.",
                        color = StudioMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            CompactTextAction("Compare with OAuth", onShowHelp)
        }
    }
}

@Composable
private fun TwitchModeHintCard(onShowHelp: () -> Unit) {
    StudioCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TwitchBadge(Modifier.size(width = 32.dp, height = 32.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Stream key mode", color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "The phone sends the Rokid video/audio to Twitch. Creator Dashboard keeps control of title and category.",
                        color = StudioMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            CompactTextAction("Compare with OAuth", onShowHelp)
        }
    }
}

@Composable
private fun SmallInfoText(text: String) {
    Text(
        text,
        color = StudioMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun StudioTextInputCard(
    icon: StudioIcon,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    enabled: Boolean = true,
    helperText: String = "",
    trailing: @Composable (() -> Unit)? = null
) {
    StudioCard(modifier = Modifier.heightIn(min = 74.dp), enabled = enabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconGlyph(icon, if (enabled) StudioGreen else StudioMuted, Modifier.size(30.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(label, color = if (enabled) StudioText else StudioMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = value,
                    onValueChange = { if (enabled) onValueChange(it) },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = if (enabled) StudioText else StudioMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(if (enabled) StudioGreen else StudioMuted),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isBlank()) {
                                Text(
                                    placeholder,
                                    color = StudioMuted.copy(alpha = 0.75f),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (helperText.isNotBlank()) {
                    Text(
                        helperText,
                        color = StudioMuted.copy(alpha = if (enabled) 0.9f else 0.72f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun StudioSelectCard(
    label: String,
    value: String,
    icon: StudioIcon,
    enabled: Boolean,
    options: List<VideoPreset>,
    selected: VideoPreset,
    onSelected: (VideoPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StudioCard(
            modifier = Modifier.heightIn(min = 58.dp),
            onClick = { if (enabled) expanded = true },
            enabled = enabled
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconGlyph(icon, if (enabled) StudioGreen else StudioMuted, Modifier.size(28.dp))
                Text(label, color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    value,
                    color = if (enabled) StudioText else StudioMuted,
                    fontSize = 15.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                preset.label,
                                color = if (preset == selected) StudioGreen else StudioText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                preset.youtubeResolutionLabel(preset.displayRotationDegrees),
                                color = StudioMuted,
                                fontSize = 12.sp
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(preset)
                    }
                )
            }
        }
    }
}

@Composable
private fun ResolutionHeatWarning(preset: VideoPreset) {
    val warning = preset.heatWarningText() ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF241C08))
            .border(1.dp, Color(0xFF8A5A12), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            warning,
            color = Color(0xFFFACC15),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun YoutubeBitrateCard(
    selectedBitrate: Int,
    autoBitrate: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) = PlatformBitrateCard(
    platformName = "YouTube",
    selectedBitrate = selectedBitrate,
    autoBitrate = autoBitrate,
    enabled = enabled,
    onSelected = onSelected
)

@Composable
private fun PlatformBitrateCard(
    platformName: String,
    selectedBitrate: Int,
    autoBitrate: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val value = if (selectedBitrate > 0) {
        selectedBitrate.bitrateLabel()
    } else {
        "Auto (${autoBitrate.bitrateLabel()})"
    }
    Box {
        StudioCard(
            modifier = Modifier.heightIn(min = 78.dp),
            enabled = enabled,
            onClick = { if (enabled) expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconGlyph(StudioIcon.BITRATE, if (enabled) StudioGreen else StudioMuted, Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$platformName bitrate", color = if (enabled) StudioText else StudioMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(value, color = if (enabled) StudioText else StudioMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        "Lower this if $platformName blocks while the phone preview stays clean.",
                        color = StudioMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            YoutubeBitrateOptions.forEach { bitrate ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (bitrate == 0) "Auto (${autoBitrate.bitrateLabel()})" else bitrate.bitrateLabel(),
                            color = if (bitrate == selectedBitrate) StudioGreen else StudioText
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(bitrate)
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomRtmpStatsCard(
    live: Boolean,
    status: String,
    bytesSent: Long
) {
    StudioCard(modifier = Modifier.heightIn(min = 74.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconGlyph(StudioIcon.BROADCAST, if (live) StudioGreen else StudioMuted, Modifier.size(22.dp))
                Text("Custom RTMP stream", color = StudioText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                MiniMetric("State", if (live) "Live" else "Stopped")
                MiniMetric("Bytes sent", "$bytesSent B")
            }
            if (status.isNotBlank()) {
                Text(
                    status,
                    color = StudioMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RtmpDiagnosticCard(
    platformName: String,
    diagnostics: RtmpDiagnostics
) {
    val hasDiagnostics = diagnostics.endpoint.isNotBlank() ||
        diagnostics.network.isNotBlank() ||
        diagnostics.reconnects > 0L ||
        diagnostics.droppedVideoFrames > 0L
    if (!hasDiagnostics) return
    StudioCard(modifier = Modifier.heightIn(min = 74.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconGlyph(StudioIcon.NETWORK, StudioGreen, Modifier.size(22.dp))
                Text("$platformName RTMP diagnostics", color = StudioText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                MiniMetric("Network", diagnostics.network.shortNetworkLabel().ifBlank { "-" })
                MiniMetric("Bitrate", diagnostics.videoBitrate.takeIf { it > 0 }?.bitrateLabel() ?: "-")
                MiniMetric("Reconnects", diagnostics.reconnects.toString())
                MiniMetric("Drops", diagnostics.droppedVideoFrames.toString())
            }
            if (diagnostics.endpoint.isNotBlank()) {
                Text(
                    diagnostics.endpoint,
                    color = StudioMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun String.shortNetworkLabel(): String =
    when {
        contains("cellular", ignoreCase = true) -> "Cellular"
        contains("wi-fi", ignoreCase = true) || contains("wifi", ignoreCase = true) -> "Wi-Fi"
        contains("ethernet", ignoreCase = true) -> "Ethernet"
        contains("vpn", ignoreCase = true) -> "VPN"
        else -> this
    }

@Composable
private fun StudioInfoCard(label: String, value: String, icon: StudioIcon) {
    StudioCard(modifier = Modifier.heightIn(min = 58.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconGlyph(icon, StudioGreen, Modifier.size(28.dp))
            Text(label, color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = StudioText,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DisabledSettingCard(
    label: String,
    value: String,
    reason: String
) {
    StudioCard(modifier = Modifier.heightIn(min = 62.dp), enabled = false) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = StudioMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(value, color = StudioMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(reason, color = StudioMuted.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun YoutubeCategoryCard(
    selected: YoutubeCategory,
    enabled: Boolean,
    oauthMode: Boolean,
    onSelected: (YoutubeCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StudioCard(
            modifier = Modifier.heightIn(min = 76.dp),
            enabled = enabled,
            onClick = { if (enabled) expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconGlyph(StudioIcon.VIDEO, if (enabled) StudioGreen else StudioMuted, Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Category", color = if (enabled) StudioText else StudioMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (oauthMode) selected.label else "YouTube Studio",
                            color = if (enabled) StudioText else StudioMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        if (oauthMode) {
                            "Applied to OAuth-created lives."
                        } else {
                            "Stream key mode keeps category in YouTube Studio."
                        },
                        color = StudioMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            YoutubeCategory.Common.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            category.label,
                            color = if (category.id == selected.id) StudioGreen else StudioText
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(category)
                    }
                )
            }
        }
    }
}

@Composable
private fun TwitchCategoryCard(
    selected: TwitchCategory,
    enabled: Boolean,
    oauthMode: Boolean,
    onSelected: (TwitchCategory) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StudioCard(
            modifier = Modifier.heightIn(min = 76.dp),
            enabled = enabled,
            onClick = { if (enabled) expanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IconGlyph(StudioIcon.TWITCH, if (enabled) StudioPurple else StudioMuted, Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Category", color = if (enabled) StudioText else StudioMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (oauthMode) selected.label else "Creator Dashboard",
                            color = if (enabled) StudioText else StudioMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        if (oauthMode) {
                            "Applied to the channel before starting RTMP."
                        } else {
                            "Stream key mode keeps category in Twitch."
                        },
                        color = StudioMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TwitchCategory.Common.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            category.label,
                            color = if (category.id == selected.id) StudioPurple else StudioText
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(category)
                    }
                )
            }
        }
    }
}

@Composable
private fun YoutubeChatCard(
    state: PhoneUiState,
    oauthMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onFontSizeSelected: (Int) -> Unit,
    onMaxMessagesSelected: (Int) -> Unit,
    onBottomOffsetSelected: (Int) -> Unit
) {
    val enabled = oauthMode && state.youtubeConnected
    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconGlyph(StudioIcon.YOUTUBE, if (enabled) StudioRed else StudioMuted, Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Chat on Helper",
                        color = if (enabled) StudioText else StudioMuted,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            !oauthMode -> "OAuth mode is required to read YouTube chat."
                            !state.youtubeConnected -> "Connect YouTube to show chat in the glasses helper."
                            state.youtubeChatStatus.isNotBlank() -> state.youtubeChatStatus
                            state.youtubeChatEnabled -> "Waiting for an OAuth live."
                            else -> "Phone polls YouTube; helper only renders text."
                        },
                        color = StudioMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = state.youtubeChatEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StudioGreen,
                        uncheckedThumbColor = StudioMuted,
                        uncheckedTrackColor = Color(0xFF242A28),
                        disabledCheckedThumbColor = StudioMuted,
                        disabledCheckedTrackColor = Color(0xFF242A28),
                        disabledUncheckedThumbColor = StudioMuted.copy(alpha = 0.7f),
                        disabledUncheckedTrackColor = Color(0xFF1A1F1D)
                    )
                )
            }

            if (enabled) {
                ChatFontStepperRow(
                    selected = state.youtubeChatFontSizeSp,
                    enabled = enabled,
                    onDecrease = {
                        onFontSizeSelected(state.youtubeChatFontSizeSp - 1)
                    },
                    onIncrease = {
                        onFontSizeSelected(state.youtubeChatFontSizeSp + 1)
                    },
                    onReset = {
                        onFontSizeSelected(Protocol.DEFAULT_CHAT_FONT_SIZE_SP)
                    }
                )
                ChatMessageStepperRow(
                    selected = state.youtubeChatMaxMessages,
                    enabled = enabled,
                    onDecrease = {
                        onMaxMessagesSelected(state.youtubeChatMaxMessages - 1)
                    },
                    onIncrease = {
                        onMaxMessagesSelected(state.youtubeChatMaxMessages + 1)
                    },
                    onReset = {
                        onMaxMessagesSelected(Protocol.DEFAULT_CHAT_MAX_MESSAGES)
                    }
                )
                ChatBottomOffsetStepperRow(
                    selected = state.chatBottomOffsetDp,
                    enabled = enabled,
                    onDecrease = {
                        onBottomOffsetSelected(previousChatBottomOffset(state.chatBottomOffsetDp))
                    },
                    onIncrease = {
                        onBottomOffsetSelected(nextChatBottomOffset(state.chatBottomOffsetDp))
                    },
                    onReset = {
                        onBottomOffsetSelected(Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP)
                    }
                )
            }

            if (state.youtubeChatMessages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.youtubeChatMessages.takeLast(3).forEach { message ->
                        Text(
                            "${message.author}: ${message.text}",
                            color = StudioText.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TwitchChatCard(
    state: PhoneUiState,
    oauthMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onFontSizeSelected: (Int) -> Unit,
    onMaxMessagesSelected: (Int) -> Unit,
    onBottomOffsetSelected: (Int) -> Unit
) {
    val enabled = oauthMode && state.twitchConnected
    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconGlyph(StudioIcon.TWITCH, if (enabled) StudioPurple else StudioMuted, Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Chat on Helper",
                        color = if (enabled) StudioText else StudioMuted,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            !oauthMode -> "OAuth mode is required to read Twitch chat."
                            !state.twitchConnected -> "Connect Twitch to show chat in the glasses helper."
                            state.twitchChatStatus.isNotBlank() -> state.twitchChatStatus
                            state.twitchChatEnabled -> "Waiting for Twitch chat."
                            else -> "Phone uses Twitch EventSub; helper only renders text."
                        },
                        color = StudioMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = state.twitchChatEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StudioPurple,
                        uncheckedThumbColor = StudioMuted,
                        uncheckedTrackColor = Color(0xFF242A28),
                        disabledCheckedThumbColor = StudioMuted,
                        disabledCheckedTrackColor = Color(0xFF242A28),
                        disabledUncheckedThumbColor = StudioMuted.copy(alpha = 0.7f),
                        disabledUncheckedTrackColor = Color(0xFF1A1F1D)
                    )
                )
            }

            if (enabled) {
                ChatFontStepperRow(
                    selected = state.twitchChatFontSizeSp,
                    enabled = enabled,
                    onDecrease = {
                        onFontSizeSelected(state.twitchChatFontSizeSp - 1)
                    },
                    onIncrease = {
                        onFontSizeSelected(state.twitchChatFontSizeSp + 1)
                    },
                    onReset = {
                        onFontSizeSelected(Protocol.DEFAULT_CHAT_FONT_SIZE_SP)
                    }
                )
                ChatMessageStepperRow(
                    selected = state.twitchChatMaxMessages,
                    enabled = enabled,
                    onDecrease = {
                        onMaxMessagesSelected(state.twitchChatMaxMessages - 1)
                    },
                    onIncrease = {
                        onMaxMessagesSelected(state.twitchChatMaxMessages + 1)
                    },
                    onReset = {
                        onMaxMessagesSelected(Protocol.DEFAULT_CHAT_MAX_MESSAGES)
                    }
                )
                ChatBottomOffsetStepperRow(
                    selected = state.chatBottomOffsetDp,
                    enabled = enabled,
                    onDecrease = {
                        onBottomOffsetSelected(previousChatBottomOffset(state.chatBottomOffsetDp))
                    },
                    onIncrease = {
                        onBottomOffsetSelected(nextChatBottomOffset(state.chatBottomOffsetDp))
                    },
                    onReset = {
                        onBottomOffsetSelected(Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP)
                    }
                )
            }

            if (state.twitchChatMessages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.twitchChatMessages.takeLast(3).forEach { message ->
                        Text(
                            "${message.author}: ${message.text}",
                            color = StudioText.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomChatCard(
    state: PhoneUiState,
    onEnabledChange: (Boolean) -> Unit,
    onChannelChanged: (String) -> Unit,
    onFontSizeSelected: (Int) -> Unit,
    onMaxMessagesSelected: (Int) -> Unit,
    onBottomOffsetSelected: (Int) -> Unit
) {
    StudioCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconGlyph(StudioIcon.TWITCH, StudioPurple, Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Anonymous Twitch Chat",
                        color = StudioText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            state.customChatStatus.isNotBlank() -> state.customChatStatus
                            state.customChatEnabled -> "Waiting for Twitch chat."
                            else -> "Read-only chat needs only a channel name; helper renders the messages."
                        },
                        color = StudioMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = state.customChatEnabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StudioPurple,
                        uncheckedThumbColor = StudioMuted,
                        uncheckedTrackColor = Color(0xFF242A28)
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "Twitch channel",
                    color = StudioText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D1110))
                        .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = state.customChatChannel,
                        onValueChange = onChannelChanged,
                        textStyle = TextStyle(
                            color = StudioText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(StudioGreen),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.customChatChannel.isBlank()) {
                                    Text(
                                        "channelname",
                                        color = StudioMuted.copy(alpha = 0.75f),
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                Text(
                    "Connects anonymously to Twitch IRC. No OAuth token, client ID, or developer app is required.",
                    color = StudioMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            ChatFontStepperRow(
                selected = state.customChatFontSizeSp,
                enabled = true,
                onDecrease = { onFontSizeSelected(state.customChatFontSizeSp - 1) },
                onIncrease = { onFontSizeSelected(state.customChatFontSizeSp + 1) },
                onReset = { onFontSizeSelected(Protocol.DEFAULT_CHAT_FONT_SIZE_SP) }
            )
            ChatMessageStepperRow(
                selected = state.customChatMaxMessages,
                enabled = true,
                onDecrease = { onMaxMessagesSelected(state.customChatMaxMessages - 1) },
                onIncrease = { onMaxMessagesSelected(state.customChatMaxMessages + 1) },
                onReset = { onMaxMessagesSelected(Protocol.DEFAULT_CHAT_MAX_MESSAGES) }
            )
            ChatBottomOffsetStepperRow(
                selected = state.chatBottomOffsetDp,
                enabled = true,
                onDecrease = { onBottomOffsetSelected(previousChatBottomOffset(state.chatBottomOffsetDp)) },
                onIncrease = { onBottomOffsetSelected(nextChatBottomOffset(state.chatBottomOffsetDp)) },
                onReset = { onBottomOffsetSelected(Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP) }
            )

            if (state.customChatMessages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.customChatMessages.takeLast(3).forEach { message ->
                        Text(
                            "${message.author}: ${message.text}",
                            color = StudioText.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatFontStepperRow(
    selected: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "Message font",
            color = if (enabled) StudioText else StudioMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatMiniButton(
                text = "-",
                enabled = enabled && selected > 1,
                onClick = onDecrease,
                modifier = Modifier.weight(0.75f)
            )
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D1110))
                    .border(1.dp, StudioBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${selected}sp",
                    color = if (enabled) StudioText else StudioMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ChatMiniButton(
                text = "+",
                enabled = enabled,
                onClick = onIncrease,
                modifier = Modifier.weight(0.75f)
            )
            ChatMiniButton(
                text = "Reset",
                enabled = enabled && selected != Protocol.DEFAULT_CHAT_FONT_SIZE_SP,
                onClick = onReset,
                modifier = Modifier.weight(1.25f)
            )
        }
    }
}

@Composable
private fun ChatMessageStepperRow(
    selected: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "Message count",
            color = if (enabled) StudioText else StudioMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatMiniButton(
                text = "-",
                enabled = enabled && selected > 0,
                onClick = onDecrease,
                modifier = Modifier.weight(0.75f)
            )
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D1110))
                    .border(1.dp, StudioBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$selected/${Protocol.MAX_CHAT_MESSAGE_COUNT}",
                    color = if (enabled) StudioText else StudioMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ChatMiniButton(
                text = "+",
                enabled = enabled && selected < Protocol.MAX_CHAT_MESSAGE_COUNT,
                onClick = onIncrease,
                modifier = Modifier.weight(0.75f)
            )
            ChatMiniButton(
                text = "Reset",
                enabled = enabled && selected != Protocol.DEFAULT_CHAT_MAX_MESSAGES,
                onClick = onReset,
                modifier = Modifier.weight(1.25f)
            )
        }
    }
}

@Composable
private fun ChatBottomOffsetStepperRow(
    selected: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "Chat position (bottom offset)",
            color = if (enabled) StudioText else StudioMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatMiniButton(
                text = "-",
                enabled = enabled && selected > Protocol.MIN_CHAT_BOTTOM_OFFSET_DP,
                onClick = onDecrease,
                modifier = Modifier.weight(0.75f)
            )
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D1110))
                    .border(1.dp, StudioBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${selected}dp",
                    color = if (enabled) StudioText else StudioMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ChatMiniButton(
                text = "+",
                enabled = enabled && selected < Protocol.MAX_CHAT_BOTTOM_OFFSET_DP,
                onClick = onIncrease,
                modifier = Modifier.weight(0.75f)
            )
            ChatMiniButton(
                text = "Reset",
                enabled = enabled && selected != Protocol.DEFAULT_CHAT_BOTTOM_OFFSET_DP,
                onClick = onReset,
                modifier = Modifier.weight(1.25f)
            )
        }
    }
}

@Composable
private fun ChatMiniButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Color(0xFF1B2718) else Color(0xFF121715))
            .border(1.dp, if (enabled) StudioBorder else StudioBorder.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) StudioGreen else StudioMuted.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun previousChatBottomOffset(selected: Int): Int =
    ChatBottomOffsetOptions.lastOrNull { it < selected } ?: Protocol.MIN_CHAT_BOTTOM_OFFSET_DP

private fun nextChatBottomOffset(selected: Int): Int =
    ChatBottomOffsetOptions.firstOrNull { it > selected } ?: Protocol.MAX_CHAT_BOTTOM_OFFSET_DP

@Composable
private fun PrivacySelector(
    selected: YoutubePrivacy,
    enabled: Boolean,
    onSelected: (YoutubePrivacy) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Visibility", color = if (enabled) StudioText else StudioMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YoutubePrivacy.entries.forEach { privacy ->
                val active = selected == privacy
                val activeEnabled = active && enabled
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activeEnabled) StudioGreen else StudioCardAlt)
                        .border(
                            1.dp,
                            when {
                                activeEnabled -> StudioGreen
                                active -> StudioMuted.copy(alpha = 0.35f)
                                else -> StudioBorder
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = enabled) { onSelected(privacy) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        privacy.label,
                        color = if (activeEnabled) Color.Black else StudioMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeLoginCard(
    state: PhoneUiState,
    onShowHelp: () -> Unit,
    onStartYoutubeDeviceAuth: () -> Unit,
    onRefreshYoutubeChannel: () -> Unit,
    onDisconnectYoutube: () -> Unit,
    visible: Boolean
) {
    if (!visible) return
    StudioCard(onClick = onShowHelp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                YoutubeBadge(Modifier.size(width = 42.dp, height = 30.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.youtubeConnected) "YouTube Connected" else "Login to YouTube",
                        color = StudioText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            state.youtubeConnected -> state.youtubeChannelTitle.ifBlank { state.youtubeAccount.ifBlank { "Connected account" } }
                            state.youtubeDeviceUserCode.isNotBlank() -> "TV code copied: ${state.youtubeDeviceUserCode}"
                            else -> "Use TV code OAuth to create and control YouTube lives"
                        },
                        color = StudioMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(22.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactTextAction("Generate code & open page", onStartYoutubeDeviceAuth)
                CompactTextAction("Refresh", onRefreshYoutubeChannel)
                if (state.youtubeConnected) {
                    CompactTextAction("Sign out", onDisconnectYoutube, enabled = !state.youtubeLive)
                }
            }
        }
    }
}

@Composable
private fun TwitchLoginCard(
    state: PhoneUiState,
    onShowHelp: () -> Unit,
    onStartTwitchDeviceAuth: () -> Unit,
    onRefreshTwitchChannel: () -> Unit,
    onDisconnectTwitch: () -> Unit,
    visible: Boolean
) {
    if (!visible) return
    StudioCard(onClick = onShowHelp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TwitchBadge(Modifier.size(width = 38.dp, height = 38.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.twitchConnected) "Twitch Connected" else "Login to Twitch",
                        color = StudioText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            state.twitchConnected -> state.twitchChannelTitle.ifBlank { state.twitchAccount.ifBlank { "Connected account" } }
                            state.twitchDeviceUserCode.isNotBlank() -> "TV code copied: ${state.twitchDeviceUserCode}"
                            else -> "Use device-code OAuth to fetch stream key and chat"
                        },
                        color = StudioMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconGlyph(StudioIcon.CHEVRON, StudioMuted, Modifier.size(22.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactTextAction("Generate code & open page", onStartTwitchDeviceAuth)
                CompactTextAction("Refresh", onRefreshTwitchChannel)
                if (state.twitchConnected) {
                    CompactTextAction("Sign out", onDisconnectTwitch, enabled = !state.twitchLive)
                }
            }
        }
    }
}

@Composable
private fun YoutubeModeHelpDialog(
    onDismiss: () -> Unit,
    onUseStreamKey: () -> Unit,
    onUseOAuth: () -> Unit,
    onStartDeviceAuth: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onOpenGoogleCloudDocs: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        StudioCard(modifier = Modifier.heightIn(max = 690.dp)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                        Text("YouTube login options", color = StudioText, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Pick the amount of control Rokid Live Studio gets over your YouTube live.",
                            color = StudioMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    CompactTextAction("Close", onDismiss)
                }

                YoutubeModeExplanationBlock(
                    title = "Stream key",
                    subtitle = "Fastest setup. No OAuth. No TV client ID.",
                    lines = listOf(
                        "Paste the stream key from YouTube Studio.",
                        "The phone only sends RTMPS video/audio.",
                        "Title, privacy, category, thumbnail and scheduling stay in YouTube Studio."
                    ),
                    accent = StudioGreen
                )
                DialogActionButton("Use stream key mode", primary = true, onClick = onUseStreamKey)

                YoutubeModeExplanationBlock(
                    title = "OAuth account",
                    subtitle = "Needed when the app should create and manage the live.",
                    lines = listOf(
                        "The app can create private, unlisted or public lives.",
                        "It fetches the stream key automatically and transitions the broadcast live.",
                        "For Brand Accounts, the TV code flow is the one that shows the account picker."
                    ),
                    accent = StudioRed
                )
                DialogActionButton("Use OAuth mode", primary = false, onClick = onUseOAuth)
                DialogActionButton("Generate code & open page", primary = true, onClick = onStartDeviceAuth)
                DialogActionButton("Google sign-in fallback", primary = false, onClick = onGoogleSignIn)

                YoutubeModeExplanationBlock(
                    title = "Do we need a TV client ID?",
                    subtitle = "Only for TV code OAuth, and only until the app ships with a verified Google OAuth client.",
                    lines = listOf(
                        "Stream key mode does not need any Google Cloud setup.",
                        "OAuth device login needs a TV OAuth client ID and secret from Google Cloud.",
                        "If Google verification is not available, follow README.md -> Google Cloud setup and paste your own TV credentials."
                    ),
                    accent = StudioMuted
                )
                DialogActionButton("Open Google OAuth docs", primary = false, onClick = onOpenGoogleCloudDocs)
            }
        }
    }
}

@Composable
private fun TwitchModeHelpDialog(
    onDismiss: () -> Unit,
    onUseStreamKey: () -> Unit,
    onUseOAuth: () -> Unit,
    onStartDeviceAuth: () -> Unit,
    onOpenTwitchDocs: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        StudioCard(modifier = Modifier.heightIn(max = 650.dp)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                        Text("Twitch login options", color = StudioText, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Pick how much control Rokid Live Studio gets over your Twitch channel.",
                            color = StudioMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    CompactTextAction("Close", onDismiss)
                }

                YoutubeModeExplanationBlock(
                    title = "Stream key",
                    subtitle = "Fastest setup. No OAuth. No Twitch app required.",
                    lines = listOf(
                        "Paste the stream key from Twitch Creator Dashboard.",
                        "The phone only sends RTMP video/audio.",
                        "Title, category and chat stay outside the app."
                    ),
                    accent = StudioGreen
                )
                DialogActionButton("Use stream key mode", primary = true, onClick = onUseStreamKey)

                YoutubeModeExplanationBlock(
                    title = "OAuth account",
                    subtitle = "Needed when the app should fetch the key, update metadata, and read chat.",
                    lines = listOf(
                        "The app gets the Twitch stream key automatically.",
                        "It updates channel title/category before starting RTMP.",
                        "It reads chat through EventSub and sends messages to the glasses helper."
                    ),
                    accent = StudioPurple
                )
                DialogActionButton("Use OAuth mode", primary = false, onClick = onUseOAuth)
                DialogActionButton("Generate code & open page", primary = true, onClick = onStartDeviceAuth)

                YoutubeModeExplanationBlock(
                    title = "Do we need a client ID?",
                    subtitle = "Only for Twitch OAuth mode.",
                    lines = listOf(
                        "Stream key mode does not need Twitch developer setup.",
                        "OAuth mode needs a Twitch Developer app client ID.",
                        "The MVP uses device-code login, so no client secret is stored in the app."
                    ),
                    accent = StudioMuted
                )
                DialogActionButton("Open Twitch developer console", primary = false, onClick = onOpenTwitchDocs)
            }
        }
    }
}

@Composable
private fun YoutubeModeExplanationBlock(
    title: String,
    subtitle: String,
    lines: List<String>,
    accent: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0D1110))
            .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(title, color = StudioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, color = StudioMuted, fontSize = 12.sp, lineHeight = 16.sp)
            lines.forEach { line ->
                Text("- $line", color = StudioText.copy(alpha = 0.86f), fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun DialogActionButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) StudioGreen else Color(0xFF1B2718))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color.Black else StudioGreen,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CompactTextAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        color = if (enabled) StudioGreen else StudioMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (enabled) Color(0xFF1B2718) else Color(0xFF141817))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

@Composable
private fun RotationSelector(
    selectedRotation: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val active = selectedRotation == rotation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            !enabled -> Color(0xFF111827)
                            active -> StudioGreen
                            else -> StudioCardAlt
                        }
                    )
                    .border(1.dp, if (active && enabled) StudioGreen else StudioBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onSelected(rotation) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${rotation} deg",
                    color = if (active && enabled) Color.Black else StudioMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AdvancedYoutubeSetup(
    state: PhoneUiState,
    enabled: Boolean,
    onYoutubeDescriptionChanged: (String) -> Unit,
    onYoutubeDeviceClientIdChanged: (String) -> Unit,
    onYoutubeDeviceClientSecretChanged: (String) -> Unit,
    onOpenGoogleCloudDocs: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = StudioText,
        unfocusedTextColor = StudioText,
        disabledTextColor = StudioMuted,
        focusedBorderColor = StudioGreen,
        unfocusedBorderColor = StudioBorder,
        disabledBorderColor = StudioBorder.copy(alpha = 0.45f),
        focusedLabelColor = StudioGreen,
        unfocusedLabelColor = StudioMuted,
        disabledLabelColor = StudioMuted.copy(alpha = 0.7f),
        cursorColor = StudioGreen
    )

    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallInfoText("OAuth mode can create the broadcast, set title/description/visibility, fetch the stream key and transition the broadcast live. If Google verification is not available, follow README.md -> Google Cloud setup and paste a TV OAuth client ID and secret here.")
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.youtubeDescription,
                onValueChange = onYoutubeDescriptionChanged,
                label = { Text("Description") },
                singleLine = true,
                enabled = enabled,
                colors = fieldColors
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.youtubeDeviceClientId,
                onValueChange = onYoutubeDeviceClientIdChanged,
                label = { Text("YouTube TV client ID") },
                singleLine = true,
                enabled = enabled,
                colors = fieldColors
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.youtubeDeviceClientSecret,
                onValueChange = onYoutubeDeviceClientSecretChanged,
                label = { Text("YouTube TV client secret") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors
            )
            CompactTextAction("Open Google OAuth docs", onOpenGoogleCloudDocs)
        }
    }
}

@Composable
private fun AdvancedTwitchSetup(
    state: PhoneUiState,
    enabled: Boolean,
    onTwitchDeviceClientIdChanged: (String) -> Unit,
    onOpenTwitchDocs: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = StudioText,
        unfocusedTextColor = StudioText,
        disabledTextColor = StudioMuted,
        focusedBorderColor = StudioPurple,
        unfocusedBorderColor = StudioBorder,
        disabledBorderColor = StudioBorder.copy(alpha = 0.45f),
        focusedLabelColor = StudioPurple,
        unfocusedLabelColor = StudioMuted,
        disabledLabelColor = StudioMuted.copy(alpha = 0.7f),
        cursorColor = StudioPurple
    )

    StudioCard(enabled = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallInfoText("OAuth mode can fetch the Twitch stream key, update title/category, and read chat. Create an app in the Twitch Developer Console and paste its Client ID here.")
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.twitchDeviceClientId,
                onValueChange = onTwitchDeviceClientIdChanged,
                label = { Text("Twitch client ID") },
                singleLine = true,
                enabled = enabled,
                colors = fieldColors
            )
            CompactTextAction("Open Twitch developer console", onOpenTwitchDocs)
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    if (error.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2A1111))
                .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(error, color = Color(0xFFFCA5A5), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StudioPrimaryButton(
    text: String,
    icon: StudioIcon,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(listOf(StudioGreen, Color(0xFF43D918)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFF263226), Color(0xFF1E271E)))
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconGlyph(icon, Color.Black, Modifier.size(28.dp))
            Text(
                text,
                color = Color.Black.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun BottomStudioNav(
    selectedTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(98.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2050706))))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioTab.entries.forEach { tab ->
                BottomNavItem(
                    tab = tab,
                    selected = selectedTab == tab,
                    onClick = { onSelectTab(tab) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(tab: StudioTab, selected: Boolean, onClick: () -> Unit) {
    val color = when (tab) {
        StudioTab.HOME -> StudioGreen
        StudioTab.YOUTUBE -> StudioRed
        StudioTab.TWITCH -> StudioPurple
        StudioTab.CUSTOM -> StudioGreen
        StudioTab.SETTINGS -> if (selected) StudioGreen else StudioMuted
    }
    val icon = when (tab) {
        StudioTab.HOME -> StudioIcon.HOME
        StudioTab.YOUTUBE -> StudioIcon.YOUTUBE
        StudioTab.TWITCH -> StudioIcon.TWITCH
        StudioTab.CUSTOM -> StudioIcon.BROADCAST
        StudioTab.SETTINGS -> StudioIcon.SETTINGS
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconGlyph(icon, if (selected) color else StudioMuted, Modifier.size(30.dp))
        Text(
            tab.label,
            color = if (selected) color else StudioMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun StudioCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickModifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(StudioShape)
            .background(Brush.verticalGradient(listOf(StudioCardAlt, StudioCardBase)))
            .border(1.dp, StudioBorder.copy(alpha = if (enabled) 0.95f else 0.45f), StudioShape)
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        content()
    }
}

@Composable
private fun StudioRoundButton(
    icon: StudioIcon,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconGlyph(icon, tint, Modifier.size(30.dp))
    }
}

@Composable
private fun YoutubeBadge(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_youtube),
        contentDescription = "YouTube",
        modifier = modifier
    )
}

@Composable
private fun TwitchBadge(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_twitch),
        contentDescription = "Twitch",
        modifier = modifier
    )
}

@Composable
private fun BatteryGlyph(
    level: Float,
    tint: Color = StudioGreen,
    modifier: Modifier = Modifier.size(width = 34.dp, height = 17.dp)
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.5f)
        val bodyWidth = size.width * 0.82f
        val bodyHeight = size.height * 0.72f
        val bodyTop = size.height * 0.14f
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(2.5f, 2.5f),
            style = stroke
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyWidth + size.width * 0.04f, size.height * 0.34f),
            size = Size(size.width * 0.10f, size.height * 0.32f),
            cornerRadius = CornerRadius(1.5f, 1.5f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.08f, bodyTop + size.height * 0.12f),
            size = Size((bodyWidth - size.width * 0.16f) * level.coerceIn(0f, 1f), bodyHeight - size.height * 0.24f),
            cornerRadius = CornerRadius(1.5f, 1.5f)
        )
    }
}

@Composable
private fun IconGlyph(icon: StudioIcon, tint: Color, modifier: Modifier = Modifier) {
    if (icon == StudioIcon.YOUTUBE) {
        Image(
            painter = painterResource(R.drawable.ic_brand_youtube),
            contentDescription = "YouTube",
            modifier = modifier
        )
        return
    }
    if (icon == StudioIcon.TWITCH) {
        Image(
            painter = painterResource(R.drawable.ic_brand_twitch),
            contentDescription = "Twitch",
            modifier = modifier
        )
        return
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val min = minOf(w, h)
        val stroke = Stroke(width = min * 0.085f, cap = StrokeCap.Round)
        val thin = Stroke(width = min * 0.055f, cap = StrokeCap.Round)

        when (icon) {
            StudioIcon.GLASSES -> {
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.24f), Size(w * 0.34f, h * 0.50f), CornerRadius(5f, 5f), style = stroke)
                drawRoundRect(tint, Offset(w * 0.58f, h * 0.24f), Size(w * 0.34f, h * 0.50f), CornerRadius(5f, 5f), style = stroke)
                drawLine(tint, Offset(w * 0.42f, h * 0.48f), Offset(w * 0.58f, h * 0.48f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.36f), Offset(w * 0.18f, h * 0.62f), strokeWidth = min * 0.05f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, h * 0.36f), Offset(w * 0.82f, h * 0.62f), strokeWidth = min * 0.05f, cap = StrokeCap.Round)
            }

            StudioIcon.PHONE -> {
                drawRoundRect(tint, Offset(w * 0.25f, h * 0.08f), Size(w * 0.50f, h * 0.84f), CornerRadius(8f, 8f), style = stroke)
                drawLine(tint, Offset(w * 0.42f, h * 0.22f), Offset(w * 0.58f, h * 0.22f), strokeWidth = min * 0.05f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.66f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.52f), Offset(w * 0.62f, h * 0.52f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
            }

            StudioIcon.WIFI -> {
                drawArc(tint, 210f, 120f, false, Offset(w * 0.03f, h * 0.18f), Size(w * 0.94f, h * 0.78f), style = stroke)
                drawArc(tint, 220f, 100f, false, Offset(w * 0.20f, h * 0.37f), Size(w * 0.60f, h * 0.50f), style = stroke)
                drawArc(tint, 230f, 80f, false, Offset(w * 0.36f, h * 0.56f), Size(w * 0.28f, h * 0.24f), style = stroke)
                drawCircle(tint, min * 0.08f, Offset(w * 0.50f, h * 0.82f))
            }

            StudioIcon.NETWORK -> {
                drawCircle(tint, min * 0.12f, Offset(w * 0.50f, h * 0.18f), style = stroke)
                drawCircle(tint, min * 0.12f, Offset(w * 0.20f, h * 0.72f), style = stroke)
                drawCircle(tint, min * 0.12f, Offset(w * 0.80f, h * 0.72f), style = stroke)
                drawLine(tint, Offset(w * 0.44f, h * 0.28f), Offset(w * 0.26f, h * 0.62f), strokeWidth = min * 0.06f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.56f, h * 0.28f), Offset(w * 0.74f, h * 0.62f), strokeWidth = min * 0.06f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.68f, h * 0.72f), strokeWidth = min * 0.06f, cap = StrokeCap.Round)
            }

            StudioIcon.STOP -> {
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), CornerRadius(4f, 4f))
            }

            StudioIcon.EXTERNAL -> {
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.28f), Size(w * 0.58f, h * 0.58f), CornerRadius(4f, 4f), style = stroke)
                drawLine(tint, Offset(w * 0.46f, h * 0.16f), Offset(w * 0.86f, h * 0.16f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.84f, h * 0.16f), Offset(w * 0.84f, h * 0.56f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.46f, h * 0.54f), Offset(w * 0.84f, h * 0.16f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
            }

            StudioIcon.SETTINGS -> {
                drawCircle(tint, min * 0.24f, Offset(w * 0.50f, h * 0.50f), style = stroke)
                drawCircle(tint, min * 0.08f, Offset(w * 0.50f, h * 0.50f), style = thin)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val sx = w * 0.50f + kotlin.math.cos(a).toFloat() * min * 0.34f
                    val sy = h * 0.50f + kotlin.math.sin(a).toFloat() * min * 0.34f
                    val ex = w * 0.50f + kotlin.math.cos(a).toFloat() * min * 0.44f
                    val ey = h * 0.50f + kotlin.math.sin(a).toFloat() * min * 0.44f
                    drawLine(tint, Offset(sx, sy), Offset(ex, ey), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
                }
            }

            StudioIcon.CHEVRON -> {
                drawLine(tint, Offset(w * 0.38f, h * 0.22f), Offset(w * 0.66f, h * 0.50f), strokeWidth = min * 0.09f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.50f), Offset(w * 0.38f, h * 0.78f), strokeWidth = min * 0.09f, cap = StrokeCap.Round)
            }

            StudioIcon.BACK -> {
                drawLine(tint, Offset(w * 0.70f, h * 0.18f), Offset(w * 0.30f, h * 0.50f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.70f, h * 0.82f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.88f, h * 0.50f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
            }

            StudioIcon.KEY -> {
                drawCircle(tint, min * 0.19f, Offset(w * 0.34f, h * 0.36f), style = stroke)
                drawLine(tint, Offset(w * 0.48f, h * 0.50f), Offset(w * 0.82f, h * 0.84f), strokeWidth = min * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.68f, h * 0.70f), Offset(w * 0.82f, h * 0.58f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.78f), Offset(w * 0.90f, h * 0.66f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
            }

            StudioIcon.VIDEO -> {
                drawRoundRect(tint, Offset(w * 0.10f, h * 0.24f), Size(w * 0.64f, h * 0.52f), CornerRadius(5f, 5f), style = stroke)
                val path = Path().apply {
                    moveTo(w * 0.74f, h * 0.42f)
                    lineTo(w * 0.92f, h * 0.30f)
                    lineTo(w * 0.92f, h * 0.70f)
                    lineTo(w * 0.74f, h * 0.58f)
                    close()
                }
                drawPath(path, tint)
            }

            StudioIcon.BITRATE -> {
                for (i in 0 until 5) {
                    val x = w * (0.18f + i * 0.16f)
                    val barH = h * (0.28f + (i % 3) * 0.16f)
                    drawLine(tint, Offset(x, h * 0.50f - barH / 2f), Offset(x, h * 0.50f + barH / 2f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
                }
            }

            StudioIcon.COPY -> {
                drawRoundRect(tint, Offset(w * 0.30f, h * 0.14f), Size(w * 0.52f, h * 0.62f), CornerRadius(4f, 4f), style = stroke)
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.30f), Size(w * 0.52f, h * 0.62f), CornerRadius(4f, 4f), style = stroke)
            }

            StudioIcon.HOME -> {
                val path = Path().apply {
                    moveTo(w * 0.14f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.48f)
                    lineTo(w * 0.76f, h * 0.48f)
                    lineTo(w * 0.76f, h * 0.84f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.24f, h * 0.48f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }

            StudioIcon.TWITCH -> {
                val path = Path().apply {
                    moveTo(w * 0.14f, h * 0.20f)
                    lineTo(w * 0.86f, h * 0.20f)
                    lineTo(w * 0.86f, h * 0.66f)
                    lineTo(w * 0.62f, h * 0.66f)
                    lineTo(w * 0.46f, h * 0.84f)
                    lineTo(w * 0.46f, h * 0.66f)
                    lineTo(w * 0.14f, h * 0.66f)
                    close()
                }
                drawPath(path, tint, style = stroke)
                drawLine(tint, Offset(w * 0.43f, h * 0.36f), Offset(w * 0.43f, h * 0.50f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, h * 0.36f), Offset(w * 0.62f, h * 0.50f), strokeWidth = min * 0.07f, cap = StrokeCap.Round)
            }

            StudioIcon.BROADCAST -> {
                drawCircle(tint, min * 0.07f, Offset(w * 0.50f, h * 0.52f))
                drawArc(tint, -140f, 100f, false, Offset(w * 0.30f, h * 0.30f), Size(w * 0.40f, h * 0.44f), style = thin)
                drawArc(tint, -155f, 130f, false, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.72f), style = thin)
                drawArc(tint, -40f, 100f, false, Offset(w * 0.30f, h * 0.30f), Size(w * 0.40f, h * 0.44f), style = thin)
                drawArc(tint, -25f, 130f, false, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.72f), style = thin)
            }

            StudioIcon.YOUTUBE -> Unit
        }
    }
}

private fun PhoneUiState.glassesConnected(): Boolean =
    authorized || cxrConnected || glassBtConnected

private fun VideoPreset.youtubeResolutionLabel(rotationDegrees: Int): String {
    val visualWidth = if (rotationDegrees.normalizedRotation().isQuarterTurn()) height else width
    val visualHeight = if (rotationDegrees.normalizedRotation().isQuarterTurn()) width else height
    val outWidth = youtubeOutputWidth.takeIf { it > 0 } ?: visualWidth
    val outHeight = youtubeOutputHeight.takeIf { it > 0 } ?: visualHeight
    val shortSide = minOf(outWidth, outHeight)
    val short = when {
        shortSide >= 2160 -> "4K"
        shortSide >= 1440 -> "1440p"
        shortSide >= 1080 -> "1080p"
        shortSide >= 720 -> "720p"
        else -> "${shortSide}p"
    }
    return "$outWidth x $outHeight ($short)"
}

private fun VideoPreset.youtubeBitrateLabel(): String {
    return defaultYoutubeBitrate().bitrateLabel()
}

private fun VideoPreset.heatWarningText(): String? {
    val baselinePixels = VideoPreset.ROKID_768_1024.width * VideoPreset.ROKID_768_1024.height
    val selectedPixels = width * height
    if (selectedPixels <= baselinePixels) return null
    return "Higher than 768 x 1024 can heat the glasses quickly and drain battery fast. For long streams, use a lower resolution, hide preview, and use external power."
}

private fun VideoPreset.defaultYoutubeBitrate(): Int =
    youtubeVideoBitrate.takeIf { it > 0 } ?: videoBitrate

private fun Int.bitrateLabel(): String =
    if (this >= 1_000_000) {
        val mbps = this / 1_000_000.0
        if (this % 1_000_000 == 0) "${this / 1_000_000} Mbps" else String.format(java.util.Locale.US, "%.1f Mbps", mbps)
    } else {
        "${this / 1_000} kbps"
    }
