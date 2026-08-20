package com.lagradost.cloudstream3.ui.iptv

data class IptvPlaylist(
    val name: String,
    val url: String
)

data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null
)
