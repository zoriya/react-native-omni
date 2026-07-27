"use strict";

import { selectAudioTrack, selectQuality, selectTextTrack } from "@videojs/react";
import { stateMapper } from "./events.web";
export const getSubtitleFormat = subtitle => {
  const mime = subtitle.mimeType?.toLowerCase() ?? "";
  const ext = subtitle.link.split(/[?#]/)[0]?.split(".").pop()?.toLowerCase();
  if (mime.includes("ass") || mime.includes("ssa") || ext === "ass" || ext === "ssa") return "ass";
  if (mime.includes("pgs") || ext === "sup") return "pgs";
  if (mime.includes("vtt") || ext === "vtt") return "vtt";
  return "native";
};
export const isCustomSubtitle = subtitle => {
  const format = getSubtitleFormat(subtitle);
  return format === "ass" || format === "pgs";
};
export class WebOmniPlayer {
  onPrev = new Set();
  onNext = new Set();
  constructor(store) {
    this._store = store;
  }
  get castStatus() {
    return stateMapper.castStatus.mapper(this._store.state);
  }
  toggleCastStatus() {
    this._store.state.toggleRemotePlayback();
  }
  castOptions = null;
  _source = undefined;
  _showNotification = false;

  // Selected ASS/PGS subtitle (drawn by the overlay); `null` when the active
  // subtitle is native or off. Exposed as an external store so the view can
  // react to selection changes.
  overlaySubtitle = null;
  overlayListeners = new Set();
  get source() {
    return this._source;
  }
  set source(source) {
    this._source = source;
    // Drop the overlay subtitle if it is not part of the new source.
    if (this.overlaySubtitle && !source?.subtitles.some(s => s.id === this.overlaySubtitle?.id)) {
      this.setOverlaySubtitle(null);
    }
    this.updateMediaSession();
  }
  get showNotification() {
    return this._showNotification;
  }
  set showNotification(value) {
    this._showNotification = value;
    this.updateMediaSession();
  }
  get status() {
    const state = this._store.state;
    return stateMapper.status.mapper(state);
  }
  get isPlaying() {
    return !this._store.state.paused && !this._store.state.ended;
  }
  get currentTime() {
    return this._store.state.currentTime;
  }
  set currentTime(time) {
    this._store.seek(time).catch(() => {});
  }
  get buffered() {
    const buffered = this._store.state.buffered;
    if (buffered.length === 0) return 0;
    return buffered[buffered.length - 1]?.[1] ?? 0;
  }
  get duration() {
    return this._store.state.duration;
  }
  get playbackRate() {
    return this._store.state.playbackRate;
  }
  set playbackRate(rate) {
    this._store.setPlaybackRate(rate);
  }
  get volume() {
    return this._store.state.volume;
  }
  set volume(vol) {
    this._store.setVolume(vol);
  }
  get muted() {
    return this._store.state.muted;
  }
  set muted(value) {
    if (value !== this.muted) {
      this._store.toggleMuted();
    }
  }
  get isAutoQuality() {
    const quality = selectQuality(this._store.state);
    if (!quality) return true;
    // ABR ("auto") is on whenever no rendition is explicitly pinned.
    return !quality.videoRenditionList.some(r => r.selected);
  }
  play() {
    this._store.play();
    this.setPlaybackState("playing");
  }
  pause() {
    this._store.pause();
    this.setPlaybackState("paused");
  }
  seekBy(offset) {
    this._store.seek(this.currentTime + offset).catch(() => {});
  }
  playPrev() {
    for (const cb of this.onPrev) cb();
  }
  playNext() {
    for (const cb of this.onNext) cb();
  }
  get hasPrev() {
    return this._source?.metadata?.hasPrev ?? false;
  }
  get hasNext() {
    return this._source?.metadata?.hasNext ?? false;
  }
  get videos() {
    // hls.js does not support alternative video tracks (e.g. camera angles)
    return [];
  }
  selectVideo(_video) {
    // hls.js does not support alternative video tracks
  }
  get audios() {
    const audio = selectAudioTrack(this._store.state);
    if (!audio) return [];
    return audio.audioTrackList.map((track, i) => ({
      id: track.id ?? i.toString(),
      label: track.label,
      language: track.language,
      selected: track.enabled
    }));
  }
  selectAudio(audio) {
    const tracks = selectAudioTrack(this._store.state);
    if (!tracks) return;
    tracks.selectAudioTrack(audio.id);
  }
  get overlaySubtitles() {
    return (this._source?.subtitles ?? []).filter(isCustomSubtitle);
  }
  get subtitles() {
    // Every subtitle (incl. ass/pgs) is a real text track now; the cast
    // receiver draws the ones it can't render natively, keyed off the active
    // track. So the text-track list already contains all of them.
    const textTracks = selectTextTrack(this._store.state)?.textTrackList ?? [];
    return textTracks.filter(x => x.kind === "subtitles" || x.kind === "captions").map(track => ({
      id: track.id,
      label: track.label,
      language: track.language,
      selected: track.mode === "showing"
    }));
  }
  selectSubtitle(subtitle) {
    // Selecting a text track drives local playback and, while casting,
    // video.js forwards it to the receiver as an active cast track. ass/pgs
    // additionally need our local overlay since the browser can't render them.
    const tracks = selectTextTrack(this._store.state);
    tracks?.selectSubtitlesTrack(subtitle ? subtitle.id : "off");
    const overlay = subtitle ? this.overlaySubtitles.find(s => s.id === subtitle.id) : undefined;
    this.setOverlaySubtitle(overlay ?? null);
  }
  setOverlaySubtitle(sub) {
    if (this.overlaySubtitle === sub) return;
    this.overlaySubtitle = sub;
    for (const listener of this.overlayListeners) listener();
  }

  // External store used by the view to render the ASS/PGS overlay.
  subscribeOverlaySubtitle = callback => {
    this.overlayListeners.add(callback);
    return () => this.overlayListeners.delete(callback);
  };
  getOverlaySubtitle = () => this.overlaySubtitle;
  get rendition() {
    function isSameRendition(a, b) {
      return b != null && a.id === b.id && a.width === b.width && a.height === b.height && a.bitrate === b.bitrate;
    }
    const quality = selectQuality(this._store.state);
    if (!quality) return [];
    const active = quality.activeVideoRendition;
    return quality.videoRenditionList.map((rendition, i) => ({
      id: rendition.id ?? i.toString(),
      width: rendition.width ?? 0,
      height: rendition.height ?? 0,
      bitrate: rendition.bitrate ?? 0,
      selected: rendition.selected || isSameRendition(rendition, active)
    }));
  }
  selectRendition(rendition) {
    const tracks = selectQuality(this._store.state);
    if (!tracks) return;
    tracks.selectVideoRendition(rendition ? rendition.id : "auto");
  }
  setPlaybackState(state) {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) {
      return;
    }
    if (this._showNotification) navigator.mediaSession.playbackState = state;
  }
  updateMediaSession() {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) {
      return;
    }
    const session = navigator.mediaSession;
    const actions = ["play", "pause", "seekbackward", "seekforward", "seekto", "previoustrack", "nexttrack"];
    if (!this._showNotification) {
      session.metadata = null;
      for (const action of actions) {
        try {
          session.setActionHandler(action, null);
        } catch {}
      }
      return;
    }
    const metadata = this._source?.metadata;
    if (metadata && typeof MediaMetadata !== "undefined") {
      session.metadata = new MediaMetadata({
        title: metadata.title,
        artist: metadata.artist ?? "",
        album: metadata.album ?? "",
        artwork: metadata.imageLink ? [{
          src: metadata.imageLink
        }] : []
      });
    }
    const set = (action, handler) => {
      try {
        session.setActionHandler(action, handler);
      } catch {}
    };
    set("play", () => this.play());
    set("pause", () => this.pause());
    set("seekbackward", d => this.seekBy(-(d.seekOffset ?? 10)));
    set("seekforward", d => this.seekBy(d.seekOffset ?? 10));
    set("seekto", d => {
      if (d.seekTime != null) this.currentTime = d.seekTime;
    });
    set("previoustrack", this.hasPrev ? () => this.playPrev() : null);
    set("nexttrack", this.hasNext ? () => this.playNext() : null);
  }
}
//# sourceMappingURL=player.web.js.map