package dev.zoriya.omni

import android.annotation.SuppressLint
import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata

@SuppressLint("UnsafeOptInUsageError")
class OmniMediaItemConverter : MediaItemConverter {
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val uri = info?.contentUrl ?: info?.contentId ?: ""
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(info?.contentId ?: uri)
        info?.contentType?.let { builder.setMimeType(it) }
        info?.metadata?.let { md ->
            builder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(md.getString(CastMediaMetadata.KEY_TITLE))
                    .setArtist(md.getString(CastMediaMetadata.KEY_ARTIST))
                    .setAlbumTitle(md.getString(CastMediaMetadata.KEY_ALBUM_TITLE))
                    .build()
            )
        }
        return builder.build()
    }

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = requireNotNull(mediaItem.localConfiguration) {
            "MediaItem must have a localConfiguration to be cast"
        }
        val uri = local.uri.toString()
        val extras = mediaItem.requestMetadata.extras
        val contentId = extras?.getString(OmniPlayer.CAST_ID_EXTRA) ?: uri

        val meta = mediaItem.mediaMetadata
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MOVIE).apply {
            meta.title?.let { putString(CastMediaMetadata.KEY_TITLE, it.toString()) }
            meta.artist?.let {
                putString(CastMediaMetadata.KEY_ARTIST, it.toString())
                putString(CastMediaMetadata.KEY_SUBTITLE, it.toString())
            }
            meta.albumTitle?.let {
                putString(CastMediaMetadata.KEY_ALBUM_TITLE, it.toString())
                putString(CastMediaMetadata.KEY_STUDIO, it.toString())
            }
            meta.artworkUri?.let { addImage(WebImage(it)) }
        }

        val customData = extras?.getString(OmniPlayer.CAST_DATA_EXTRA)?.let {
            try {
                JSONObject(it)
            } catch (_: Throwable) {
                null
            }
        }

        val tracks = local.subtitleConfigurations.mapIndexed { index, subtitle ->
            // caf tracks are 1 based instead of 0 based. videojs does the same
            MediaTrack.Builder((index + 1).toLong(), MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitle.uri.toString())
                .setContentType(subtitle.mimeType ?: "text/vtt")
                .apply {
                    subtitle.label?.let { setName(it) }
                    subtitle.language?.let { setLanguage(it) }
                }
                .build()
        }

        val path = uri.toUri().path?.lowercase() ?: uri.lowercase()
        val contentType = local.mimeType ?: when {
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            else -> "application/x-mpegurl"
        }

        val mediaInfo = MediaInfo.Builder(contentId)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentUrl(uri)
            .setContentType(contentType)
            .setMetadata(castMetadata)
            .apply {
                if (tracks.isNotEmpty()) setMediaTracks(tracks)
                customData?.let { setCustomData(it) }
            }
            .build()

        return MediaQueueItem.Builder(mediaInfo)
            .setAutoplay(true)
            .build()
    }
}
