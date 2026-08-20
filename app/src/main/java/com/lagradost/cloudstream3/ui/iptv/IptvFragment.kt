package com.lagradost.cloudstream3.ui.iptv

import android.app.AlertDialog
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class IptvFragment : Fragment(R.layout.fragment_iptv) {

    private lateinit var playlistRecycler: RecyclerView
    private lateinit var channelRecycler: RecyclerView
    private lateinit var categoryRecycler: RecyclerView
    private lateinit var emptyView: TextView

    private var playlists = emptyList<IptvPlaylist>()
    private var channels = emptyList<IptvChannel>()
    private var activePlaylist: IptvPlaylist? = null
    private var activeCategory: String = "Semua"

    private var restoredPlaylistUrl: String? = null
    private var restoredCategory: String? = null
    private var restoredChannelPosition: Int = 0

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        restoredPlaylistUrl =
            savedInstanceState?.getString("iptv_active_playlist_url")

        restoredCategory =
            savedInstanceState?.getString("iptv_active_category")

        restoredChannelPosition =
            savedInstanceState?.getInt("iptv_channel_position", 0) ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            root.setPadding(
                root.paddingLeft,
                12 + bars.top,
                root.paddingRight,
                root.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)

        playlistRecycler = view.findViewById(R.id.iptv_playlist_recycler)
        channelRecycler = view.findViewById(R.id.iptv_channel_recycler)
        categoryRecycler = view.findViewById(R.id.iptv_category_recycler)
        emptyView = view.findViewById(R.id.iptv_empty)

        playlistRecycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )

        channelRecycler.layoutManager = LinearLayoutManager(
            requireContext()
        )

        view.findViewById<View>(R.id.iptv_add_playlist)
            .setOnClickListener {
                showAddPlaylistDialog()
            }

        view.findViewById<View>(R.id.iptv_update_playlist)
            .setOnClickListener {
                val playlist = activePlaylist
                if (playlist != null) {
                    loadPlaylist(playlist, forceRefresh = true)
                } else {
                    loadPlaylists()
                }
            }

        loadPlaylists()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(
            "iptv_active_playlist_url",
            activePlaylist?.url
        )

        outState.putString(
            "iptv_active_category",
            activeCategory
        )

        outState.putInt(
            "iptv_channel_position",
            (channelRecycler.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?: 0
        )
    }

    private fun loadPlaylists() {
        playlists = IptvRepository.getPlaylists(requireContext())

        playlistRecycler.adapter = PlaylistAdapter(
            playlists,
            activePlaylist,
            onClick = { playlist ->
                loadPlaylist(playlist)
            },
            onLongClick = { playlist ->
                confirmDeletePlaylist(playlist)
            }
        )

        emptyView.visibility =
            if (playlists.isEmpty()) View.VISIBLE else View.GONE

        if (playlists.isNotEmpty()) {
            val selectedPlaylist =
                restoredPlaylistUrl?.let { url ->
                    playlists.firstOrNull { it.url == url }
                } ?: playlists.first()

            val categoryToRestore = restoredCategory
            val positionToRestore = restoredChannelPosition

            restoredPlaylistUrl = null
            restoredCategory = null
            restoredChannelPosition = 0

            loadPlaylist(
                selectedPlaylist,
                restoreCategory = categoryToRestore,
                restorePosition = positionToRestore
            )
        } else {
            channels = emptyList()
            channelRecycler.adapter = ChannelAdapter(
                channels,
                {},
                ::loadChannelLogo
            )
        }
    }

    private fun showAddPlaylistDialog() {
        val container = LinearLayoutContainer(requireContext())

        val nameInput = EditText(requireContext()).apply {
            hint = "Nama playlist"
            setSingleLine(true)
        }

        val urlInput = EditText(requireContext()).apply {
            hint = "URL M3U / M3U8"
            setSingleLine(true)
        }

        container.add(nameInput)
        container.add(urlInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Tambah Playlist IPTV")
            .setView(container.view)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()

                if (name.isBlank() || url.isBlank()) {
                    return@setPositiveButton
                }

                IptvRepository.addPlaylist(
                    requireContext(),
                    IptvPlaylist(name, url)
                )

                loadPlaylists()
            }
            .show()
    }

    private fun loadPlaylist(
        playlist: IptvPlaylist,
        forceRefresh: Boolean = false,
        restoreCategory: String? = null,
        restorePosition: Int = 0
    ) {
        val previousCategory =
            restoreCategory
                ?: if (activePlaylist?.url == playlist.url) {
                    activeCategory
                } else {
                    "Semua"
                }

        viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    URL(playlist.url)
                        .openConnection()
                        .apply {
                            connectTimeout = if (forceRefresh) 15000 else 10000
                            readTimeout = if (forceRefresh) 15000 else 10000
                            useCaches = !forceRefresh
                        }
                        .getInputStream()
                        .bufferedReader()
                        .use { reader ->
                            IptvParser.parse(reader.readText())
                        }
                }.getOrDefault(emptyList())
            }

            if (!isAdded) return@launch

            activePlaylist = playlist
            channels = parsed

            val availableCategories = getCategories()
            activeCategory =
                if (availableCategories.contains(previousCategory)) {
                    previousCategory
                } else {
                    "Semua"
                }

            playlistRecycler.adapter = PlaylistAdapter(
                playlists,
                activePlaylist,
                onClick = { selected ->
                    loadPlaylist(selected)
                },
                onLongClick = { selected ->
                    confirmDeletePlaylist(selected)
                }
            )

            setupCategories()
            showFilteredChannels()
            updateChannelTitle()

            if (restorePosition > 0) {
                channelRecycler.post {
                    (channelRecycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(
                            restorePosition,
                            0
                        )
                }
            }
        }
    }

    private fun setupCategories() {
        val categories = mutableListOf("Semua")

        channels
            .mapNotNull { it.groupTitle?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { category ->
                if (!categories.contains(category)) {
                    categories.add(category)
                }
            }

        if (channels.any { it.groupTitle.isNullOrBlank() }) {
            categories.add("Lainnya")
        }

        categoryRecycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )

        categoryRecycler.adapter = CategoryAdapter(
            categories,
            activeCategory
        ) { category ->
            activeCategory = category
            updateCategorySelection()
            showFilteredChannels()
        }
    }

    private fun updateCategorySelection() {
        categoryRecycler.adapter = CategoryAdapter(
            getCategories(),
            activeCategory
        ) { category ->
            activeCategory = category
            updateCategorySelection()
            showFilteredChannels()
        }
    }

    private fun getCategories(): List<String> {
        val categories = mutableListOf("Semua")

        channels
            .mapNotNull { it.groupTitle?.trim()?.takeIf { value -> value.isNotBlank() } }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { category ->
                if (!categories.contains(category)) {
                    categories.add(category)
                }
            }

        if (channels.any { it.groupTitle.isNullOrBlank() }) {
            categories.add("Lainnya")
        }

        return categories
    }

    private fun showFilteredChannels() {
        val filtered = when (activeCategory) {
            "Semua" -> channels
            "Lainnya" -> channels.filter { it.groupTitle.isNullOrBlank() }
            else -> channels.filter {
                it.groupTitle?.trim() == activeCategory
            }
        }

        channelRecycler.adapter = ChannelAdapter(
            filtered,
            { channel ->
                OfflinePlaybackHelper.playLink(
                    requireActivity(),
                    channel.streamUrl
                )
            },
            ::loadChannelLogo
        )

        updateChannelTitle(filtered.size)
    }

    private fun updateChannelTitle(count: Int? = null) {
        val playlistName = activePlaylist?.name ?: "IPTV"
        val suffix = count?.let { " • $it channel" } ?: ""
        view?.findViewById<TextView>(R.id.iptv_channel_title)?.text =
            "$playlistName • $activeCategory$suffix"
    }

    private fun confirmDeletePlaylist(
        playlist: IptvPlaylist
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("Hapus Playlist?")
            .setMessage(playlist.name)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                IptvRepository.removePlaylist(
                    requireContext(),
                    playlist
                )
                loadPlaylists()
            }
            .show()
    }

    private class CategoryAdapter(
        private val items: List<String>,
        private val active: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_iptv_category,
                    parent,
                    false
                )
            return Holder(view)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int
        ) {
            holder.bind(items[position], active, onClick)
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val button =
                view.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.iptv_category_button
                )

            fun bind(
                category: String,
                active: String,
                onClick: (String) -> Unit
            ) {
                button.text = category

                if (category == active) {
                    button.setBackgroundColor(
                        button.context.getColor(R.color.colorPrimary)
                    )
                } else {
                    button.setBackgroundColor(
                        button.context.getColor(R.color.primaryGrayBackground)
                    )
                }

                button.setOnClickListener {
                    onClick(category)
                }
            }
        }
    }

    private class PlaylistAdapter(
        private val items: List<IptvPlaylist>,
        private val active: IptvPlaylist?,
        private val onClick: (IptvPlaylist) -> Unit,
        private val onLongClick: (IptvPlaylist) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_iptv_playlist,
                    parent,
                    false
                )

            return Holder(view)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int
        ) {
            holder.bind(
                items[position],
                active,
                onClick,
                onLongClick
            )
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            view: View
        ) : RecyclerView.ViewHolder(view) {

            private val button =
                view.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.iptv_playlist_button
                )

            fun bind(
                playlist: IptvPlaylist,
                activePlaylist: IptvPlaylist?,
                onClick: (IptvPlaylist) -> Unit,
                onLongClick: (IptvPlaylist) -> Unit
            ) {
                button.text = playlist.name

                if (activePlaylist?.url == playlist.url) {
                    button.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            button.context.getColor(R.color.colorPrimary)
                        )
                } else {
                    button.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            button.context.getColor(R.color.primaryGrayBackground)
                        )
                }

                button.setOnClickListener {
                    onClick(playlist)
                }

                button.setOnLongClickListener {
                    onLongClick(playlist)
                    true
                }
            }
        }
    }

    private fun loadChannelLogo(
        imageView: ImageView,
        url: String?
    ) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }

        imageView.setTag(url)

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection =
                        URL(url).openConnection() as HttpURLConnection

                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.instanceFollowRedirects = true

                    connection.inputStream.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }

            if (!isAdded) return@launch

            if (imageView.getTag() == url && bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private class ChannelAdapter(
        private val items: List<IptvChannel>,
        private val onClick: (IptvChannel) -> Unit,
        private val loadLogo: (ImageView, String?) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_iptv_channel,
                    parent,
                    false
                )

            return Holder(view)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int
        ) {
            holder.bind(items[position], onClick, loadLogo)
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            view: View
        ) : RecyclerView.ViewHolder(view) {

            private val logo =
                view.findViewById<ImageView>(
                    R.id.iptv_channel_logo
                )

            private val name =
                view.findViewById<TextView>(
                    R.id.iptv_channel_name
                )

            fun bind(
                channel: IptvChannel,
                onClick: (IptvChannel) -> Unit,
                loadLogo: (ImageView, String?) -> Unit
            ) {
                name.text = channel.name

                logo.setImageDrawable(null)

                loadLogo(logo, channel.logoUrl)

                itemView.setOnClickListener {
                    onClick(channel)
                }
            }
        }
    }

    private class LinearLayoutContainer(
        context: android.content.Context
    ) {
        val view = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 0, 40, 0)
        }

        fun add(child: View) {
            view.addView(
                child,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
            )
        }
    }
}
