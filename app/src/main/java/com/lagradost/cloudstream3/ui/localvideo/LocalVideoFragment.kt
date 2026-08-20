package com.lagradost.cloudstream3.ui.localvideo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper

class LocalVideoFragment : Fragment(R.layout.fragment_local_video) {

    private var videos = emptyList<ExtractorUri>()

    private val requestVideoPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            loadVideos()
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        loadVideos()
    }

    private fun loadVideos() {
        val root = view ?: return

        val recycler =
            root.findViewById<RecyclerView>(
                R.id.local_video_recycler
            )

        val empty =
            root.findViewById<View>(
                R.id.local_video_empty
            )

        if (!hasVideoPermission()) {
            empty.visibility = View.VISIBLE

            root.findViewById<TextView>(
                R.id.local_video_empty_text
            )?.text = getString(
                R.string.local_video_permission_required
            )

            requestVideoPermission.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            )

            return
        }

        videos = LocalVideoScanner.scan(requireContext())

        empty.visibility =
            if (videos.isEmpty()) View.VISIBLE else View.GONE

        recycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.VERTICAL,
            false
        )

        recycler.adapter = LocalVideoAdapter(videos) { video ->
            OfflinePlaybackHelper.playUri(
                requireActivity(),
                video.uri
            )
        }
    }

    private fun hasVideoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private class LocalVideoAdapter(
        private val items: List<ExtractorUri>,
        private val onClick: (ExtractorUri) -> Unit
    ) : RecyclerView.Adapter<LocalVideoAdapter.Holder>() {

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_local_video,
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
                onClick
            )
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            view: View
        ) : RecyclerView.ViewHolder(view) {

            private val thumbnail =
                view.findViewById<ImageView>(
                    R.id.local_video_thumbnail
                )

            private val name =
                view.findViewById<TextView>(
                    R.id.local_video_name
                )

            private val detail =
                view.findViewById<TextView>(
                    R.id.local_video_detail
                )

            fun bind(
                video: ExtractorUri,
                onClick: (ExtractorUri) -> Unit
            ) {
                name.text = video.name

                val extension = video.name
                    .substringAfterLast('.', "")
                    .uppercase()

                detail.text = if (extension.isNotBlank()) {
                    extension
                } else {
                    "Video lokal"
                }

                /*
                 * Thumbnail sementara dibuat dari file path.
                 * Jika URI content:// tidak punya file path,
                 * thumbnail boleh kosong tetapi video tetap bisa diputar.
                 */
                try {
                    val path = video.uri.path

                    if (!path.isNullOrBlank()) {
                        thumbnail.setImageBitmap(
                            android.media.ThumbnailUtils
                                .createVideoThumbnail(
                                    path,
                                    android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                                )
                        )
                    } else {
                        thumbnail.setImageResource(
                            R.drawable.ic_baseline_ondemand_video_24
                        )
                    }
                } catch (_: Exception) {
                    thumbnail.setImageResource(
                        R.drawable.ic_baseline_ondemand_video_24
                    )
                }

                itemView.setOnClickListener {
                    onClick(video)
                }
            }
        }
    }
}
