package com.anezium.rokidlive.phone

data class TwitchUserInfo(
    val id: String,
    val login: String,
    val displayName: String
) {
    val summary: String = displayName.ifBlank { login }.ifBlank { id }
}

data class TwitchChannelInfo(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String
)

data class TwitchCategory(
    val id: String,
    val label: String
) {
    companion object {
        val Default = TwitchCategory("509658", "Just Chatting")
        val Common = listOf(
            TwitchCategory("509658", "Just Chatting"),
            TwitchCategory("509670", "Science & Technology"),
            TwitchCategory("509672", "Travel & Outdoors"),
            TwitchCategory("509673", "Makers & Crafting"),
            TwitchCategory("509667", "Food & Drink"),
            TwitchCategory("518203", "Sports"),
            TwitchCategory("27471", "Music"),
            TwitchCategory("21779", "Special Events")
        )

        fun fromId(id: String): TwitchCategory =
            Common.firstOrNull { it.id == id } ?: Default
    }
}

data class TwitchIngestServer(
    val name: String,
    val serverUrl: String,
    val priority: Int,
    val default: Boolean
) {
    val label: String = name.ifBlank { serverUrl }
}

data class TwitchChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val timestampMs: Long
)
