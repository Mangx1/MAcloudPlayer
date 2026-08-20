package com.lagradost.cloudstream3.ui.iptv

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

object IptvRepository {

    private const val KEY = "MAcloudPlayer_iptv_playlists"

    fun getPlaylists(context: Context): List<IptvPlaylist> {
        return context.getKey(
            KEY,
            Array<IptvPlaylist>::class.java
        )?.toList() ?: emptyList()
    }

    fun savePlaylists(
        context: Context,
        playlists: List<IptvPlaylist>
    ) {
        context.setKey(KEY, playlists)
    }

    fun addPlaylist(
        context: Context,
        playlist: IptvPlaylist
    ) {
        val current = getPlaylists(context)

        val updated = (
            current.filterNot {
                it.url.equals(playlist.url, ignoreCase = true)
            } + playlist
        )

        savePlaylists(context, updated)
    }

    fun removePlaylist(
        context: Context,
        playlist: IptvPlaylist
    ) {
        savePlaylists(
            context,
            getPlaylists(context).filterNot {
                it.url == playlist.url
            }
        )
    }
}
