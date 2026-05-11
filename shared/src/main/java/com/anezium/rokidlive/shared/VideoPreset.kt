package com.anezium.rokidlive.shared

enum class VideoPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
    val iframeIntervalSeconds: Int = Protocol.DEFAULT_IFRAME_INTERVAL_SECONDS,
    val displayRotationDegrees: Int = 0,
    val visibleInUi: Boolean = true,
    val youtubeOutputWidth: Int = 0,
    val youtubeOutputHeight: Int = 0,
    val youtubeVideoBitrate: Int = 0
) {
    ROKID_768_1024(
        label = "768x1024",
        width = 1024,
        height = 768,
        fps = 30,
        videoBitrate = 1_600_000,
        displayRotationDegrees = 270,
        youtubeOutputWidth = 768,
        youtubeOutputHeight = 1024,
        youtubeVideoBitrate = 2_000_000
    ),
    LIVE_720P_9_16(
        label = "720p 9:16 Live",
        width = 720,
        height = 1280,
        fps = 30,
        videoBitrate = 2_500_000,
        youtubeVideoBitrate = 4_500_000
    ),
    ROKID_2K_9_16(
        label = "2K 9:16",
        width = 1080,
        height = 1920,
        fps = 30,
        videoBitrate = 5_000_000
    ),
    ROKID_2_5K_3_4(
        label = "2.5K 3:4",
        width = 2268,
        height = 3024,
        fps = 30,
        videoBitrate = 9_000_000,
        youtubeOutputWidth = 1080,
        youtubeOutputHeight = 1440,
        youtubeVideoBitrate = 6_000_000
    ),
    ROKID_3K_9_16(
        label = "3K capture -> 1080p",
        width = 3072,
        height = 1728,
        fps = 30,
        videoBitrate = 12_000_000,
        displayRotationDegrees = 270,
        youtubeOutputWidth = 1080,
        youtubeOutputHeight = 1920,
        youtubeVideoBitrate = 6_000_000
    ),
    ROKID_2K_9_19_5(
        label = "2K 9:19.5",
        width = 2340,
        height = 1080,
        fps = 30,
        videoBitrate = 7_000_000,
        displayRotationDegrees = 270,
        visibleInUi = false
    ),
    ROKID_2_5K_4_3(
        label = "2.5K 4:3 High",
        width = 2582,
        height = 1936,
        fps = 30,
        videoBitrate = 8_000_000
    ),
    LOW_720P(
        label = "720p rotated Low",
        width = 1280,
        height = 720,
        fps = 30,
        videoBitrate = 1_500_000,
        displayRotationDegrees = 270,
        visibleInUi = false
    ),
    STANDARD_720P(
        label = "720p rotated",
        width = 1280,
        height = 720,
        fps = 30,
        videoBitrate = 2_500_000,
        displayRotationDegrees = 270,
        visibleInUi = false
    ),
    QUALITY_1080P(
        label = "1080p rotated",
        width = 1920,
        height = 1080,
        fps = 30,
        videoBitrate = 5_000_000,
        displayRotationDegrees = 270,
        visibleInUi = false
    );

    fun startStreamConfig(host: String, port: Int, token: String): StartStreamConfig = StartStreamConfig(
        host = host,
        port = port,
        token = token,
        width = width,
        height = height,
        fps = fps,
        videoBitrate = videoBitrate,
        iframeIntervalSeconds = iframeIntervalSeconds
    )

    fun startReverseStreamConfig(port: Int, token: String): StartReverseStreamConfig = StartReverseStreamConfig(
        port = port,
        token = token,
        width = width,
        height = height,
        fps = fps,
        videoBitrate = videoBitrate,
        iframeIntervalSeconds = iframeIntervalSeconds
    )

    companion object {
        val Default: VideoPreset = ROKID_768_1024

        val Visible: List<VideoPreset> get() = entries.filter { it.visibleInUi }

        fun fromName(name: String): VideoPreset =
            entries.firstOrNull { it.name == name && it.visibleInUi } ?: Default
    }
}
