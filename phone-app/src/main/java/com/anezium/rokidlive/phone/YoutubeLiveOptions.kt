package com.anezium.rokidlive.phone

enum class YoutubePrivacy(val apiValue: String, val label: String) {
    PRIVATE("private", "Private"),
    UNLISTED("unlisted", "Unlisted"),
    PUBLIC("public", "Public");

    companion object {
        val Default = PRIVATE

        fun fromName(name: String): YoutubePrivacy =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

data class YoutubeLiveRequest(
    val title: String,
    val description: String,
    val privacy: YoutubePrivacy,
    val category: YoutubeCategory,
    val preset: com.anezium.rokidlive.shared.VideoPreset
)

data class CreatedYoutubeLive(
    val broadcastId: String,
    val streamId: String,
    val title: String,
    val privacy: YoutubePrivacy,
    val ingestionAddress: String,
    val streamName: String
) {
    val watchUrl: String = "https://www.youtube.com/watch?v=$broadcastId"
}

data class YoutubeChannelInfo(
    val id: String,
    val title: String
) {
    val summary: String = if (title.isBlank()) id else "$title ($id)"
}

data class YoutubeStreamStatus(
    val streamStatus: String,
    val healthStatus: String
)

data class YoutubeBroadcastStatus(
    val id: String,
    val lifeCycleStatus: String
)

data class YoutubeCategory(
    val id: String,
    val label: String
) {
    companion object {
        val Default = YoutubeCategory("22", "People & Blogs")
        val Common = listOf(
            YoutubeCategory("22", "People & Blogs"),
            YoutubeCategory("20", "Gaming"),
            YoutubeCategory("24", "Entertainment"),
            YoutubeCategory("28", "Science & Technology"),
            YoutubeCategory("27", "Education"),
            YoutubeCategory("17", "Sports"),
            YoutubeCategory("19", "Travel & Events"),
            YoutubeCategory("26", "Howto & Style"),
            YoutubeCategory("25", "News & Politics"),
            YoutubeCategory("23", "Comedy"),
            YoutubeCategory("10", "Music"),
            YoutubeCategory("2", "Autos & Vehicles"),
            YoutubeCategory("15", "Pets & Animals"),
            YoutubeCategory("1", "Film & Animation"),
            YoutubeCategory("29", "Nonprofits & Activism")
        )

        fun fromId(id: String): YoutubeCategory =
            Common.firstOrNull { it.id == id } ?: Default
    }
}

data class YoutubeLiveChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val timestampMs: Long
)

data class YoutubeLiveChatPage(
    val messages: List<YoutubeLiveChatMessage>,
    val nextPageToken: String,
    val pollingIntervalMillis: Int
)
