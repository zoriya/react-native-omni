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
    override fun evaluate(request: CastTrackSelectorRequest): CastTrackSelectorResult {
        val params = request.trackSelectionParameters
        val selections = LinkedHashSet(request.currentlySelectedTrackGroups)

        selectType(
            selections,
            request,
            castType = MediaTrack.TYPE_AUDIO,
            languages = params.preferredAudioLanguages,
            labels = params.preferredAudioLabels,
            disabled = C.TRACK_TYPE_AUDIO in params.disabledTrackTypes,
            selectDefault = true,
        )
        selectType(
            selections,
            request,
            castType = MediaTrack.TYPE_TEXT,
            languages = params.preferredTextLanguages,
            labels = params.preferredTextLabels,
            disabled = C.TRACK_TYPE_TEXT in params.disabledTrackTypes,
            selectDefault = false,
        )

        return request.buildResultUpon()
            .setSelections(ImmutableSet.copyOf(selections))
            .build()
    }

    private fun selectType(
        selections: MutableSet<TrackGroup>,
        request: CastTrackSelectorRequest,
        castType: Int,
        languages: List<String>,
        labels: List<String>,
        disabled: Boolean,
        selectDefault: Boolean,
    ) {
        val tracks = request.mediaTracks
        val groups = request.trackGroupList
        val indices = tracks.indices.filter { tracks[it].type == castType }
        if (indices.isEmpty()) return

        val previous = indices.filter { groups[it] in selections }
        indices.forEach { selections.remove(groups[it]) }
        if (disabled) return

        val overrides = request.trackSelectionParameters.overrides
        val overrideMatch = indices.firstOrNull { i ->
            overrides.keys.any { it.id == groups[i].id }
        }
        val matchesLabel = { i: Int ->
            val name: String? = tracks[i].name
            labels.isNotEmpty() && name != null && name in labels
        }
        val matchesLanguage = { i: Int ->
            val language: String? = tracks[i].language
            languages.isNotEmpty() && language != null &&
                Util.normalizeLanguageCode(language) in languages
        }
        val match = indices.firstOrNull { matchesLabel(it) && matchesLanguage(it) }
            ?: indices.firstOrNull { matchesLabel(it) }
            ?: indices.firstOrNull { matchesLanguage(it) }
        val chosen = when {
            overrideMatch != null -> overrideMatch
            match != null -> match
            previous.isNotEmpty() -> previous.first()
            selectDefault -> indices.first()
            else -> null
        }
        chosen?.let { selections.add(groups[it]) }
    }
}
