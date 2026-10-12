package dev.zoriya.omni

import android.annotation.SuppressLint
import androidx.media3.cast.CastTrackSelector
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Util
import com.google.android.gms.cast.MediaTrack
import com.google.common.collect.ImmutableSet

@SuppressLint("UnsafeOptInUsageError")
class OmniCastTrackSelector : CastTrackSelector() {
    private var last: CastTrackSelectorRequest? = null

    override fun evaluate(request: CastTrackSelectorRequest): CastTrackSelectorResult {
        val previous = last
        last = request
        val tracks = request.mediaTracks.zip(request.trackGroupList)
        val active = { type: Int ->
            tracks.firstOrNull { (track, group) ->
                track.type == type && group in request.currentlySelectedTrackGroups
            }
        }

        // someone else switched tracks of this media (the tv's remote, another sender...):
        if (
            request.trackSelectionRequestReason == TRACK_SELECTION_REQUEST_REASON_RECEIVER_UPDATE &&
            previous?.mediaItemId == request.mediaItemId &&
            previous.mediaTracks == request.mediaTracks &&
            previous.currentlySelectedTrackGroups != request.currentlySelectedTrackGroups
        ) {
            val audio = active(MediaTrack.TYPE_AUDIO)?.first
            val text = active(MediaTrack.TYPE_TEXT)?.first
            val params = request.trackSelectionParameters.buildUpon()
            if (audio != null) {
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredAudioLanguage(audio.language)
                    .setPreferredAudioLabels(*listOfNotNull(audio.name).toTypedArray())
            }
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(text?.language)
                .setPreferredTextLabels(*listOfNotNull(text?.name).toTypedArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, text == null)
            return request.buildResultUpon().setTrackSelectionParameters(params.build()).build()
        }

        // like the local player: an explicit pick, then the preferred label/language, then
        // what the receiver plays (or any audio, a media is never muted by default).
        val params = request.trackSelectionParameters
        fun pick(type: Int, disabled: Boolean, languages: List<String>, labels: List<String>): TrackGroup? {
            if (disabled) return null
            val candidates = tracks.filter { it.first.type == type }
            val byLabel = candidates.filter { (track, _) -> track.name in labels }
            val byLanguage = candidates.filter { (track, _) ->
                track.language?.let { Util.normalizeLanguageCode(it) } in languages
            }
            val chosen = candidates.firstOrNull { (_, group) -> params.overrides.keys.any { it.id == group.id } }
                ?: byLabel.firstOrNull { it in byLanguage }
                ?: byLabel.firstOrNull()
                ?: byLanguage.firstOrNull()
                ?: active(type)
                ?: candidates.firstOrNull()?.takeIf { type == MediaTrack.TYPE_AUDIO }
            return chosen?.second
        }

        val audio = pick(
            MediaTrack.TYPE_AUDIO,
            disabled = C.TRACK_TYPE_AUDIO in params.disabledTrackTypes,
            params.preferredAudioLanguages,
            params.preferredAudioLabels,
        )
        val text = pick(
            MediaTrack.TYPE_TEXT,
            disabled = C.TRACK_TYPE_TEXT in params.disabledTrackTypes,
            params.preferredTextLanguages,
            params.preferredTextLabels,
        )
        val others = tracks
            .filter { (track, group) ->
                track.type == MediaTrack.TYPE_VIDEO && group in request.currentlySelectedTrackGroups
            }
            .map { it.second }
        return request.buildResultUpon()
            .setSelections(ImmutableSet.copyOf(others + listOfNotNull(audio, text)))
            .build()
    }
}
