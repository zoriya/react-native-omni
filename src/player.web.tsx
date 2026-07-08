import type { MediaVideoRendition } from "@videojs/core";
import type { VideoPlayerStore } from "@videojs/core/dom";
import { selectAudioTrack, selectQuality } from "@videojs/react";
import { stateMapper } from "./events.web";
import type {
	OmniPlayer,
	PlayerStatus,
	Rendition,
	Track,
} from "./types/player";
import type { Source } from "./types/source";

export class WebOmniPlayer implements OmniPlayer {
	_store: VideoPlayerStore;
	onPrev = new Set<() => void>();
	onNext = new Set<() => void>();

	constructor(store: VideoPlayerStore) {
		this._store = store;
	}

	_source: Source | null = null;
	private _showNotification = false;

	get source(): Source | null {
		return this._source;
	}

	set source(source: Source | null) {
		this._source = source;
		this.updateMediaSession();
	}

	get showNotification(): boolean {
		return this._showNotification;
	}

	set showNotification(value: boolean) {
		this._showNotification = value;
		this.updateMediaSession();
	}

	get status(): PlayerStatus {
		const state = this._store.state;
		return stateMapper.status.mapper(state);
	}

	get isPlaying(): boolean {
		return !this._store.state.paused && !this._store.state.ended;
	}

	get currentTime(): number {
		return this._store.state.currentTime;
	}

	set currentTime(time: number) {
		this._store.seek(time).catch(() => {});
	}

	get buffered(): number {
		const buffered = this._store.state.buffered;
		if (buffered.length === 0) return 0;
		return buffered[buffered.length - 1]?.[1] ?? 0;
	}

	get duration(): number {
		return this._store.state.duration;
	}

	get playbackRate(): number {
		return this._store.state.playbackRate;
	}

	set playbackRate(rate: number) {
		this._store.setPlaybackRate(rate);
	}

	get volume(): number {
		return this._store.state.volume;
	}

	set volume(vol: number) {
		this._store.setVolume(vol);
	}

	get muted(): boolean {
		return this._store.state.muted;
	}

	set muted(value: boolean) {
		if (value !== this.muted) {
			this._store.toggleMuted();
		}
	}

	get isAutoQuality(): boolean {
		const quality = selectQuality(this._store.state);
		if (!quality) return true;
		// ABR ("auto") is on whenever no rendition is explicitly pinned.
		return !quality.videoRenditionList.some((r) => r.selected);
	}

	play(): void {
		this._store.play();
		this.setPlaybackState("playing");
	}

	pause(): void {
		this._store.pause();
		this.setPlaybackState("paused");
	}

	seekBy(offset: number): void {
		this._store.seek(this.currentTime + offset).catch(() => {});
	}

	playPrev(): void {
		for (const cb of this.onPrev) cb();
	}

	playNext(): void {
		for (const cb of this.onNext) cb();
	}

	get hasPrev(): boolean {
		return this._source?.metadata?.hasPrev ?? false;
	}

	get hasNext(): boolean {
		return this._source?.metadata?.hasNext ?? false;
	}

	get videos(): Track[] {
		// hls.js does not support alternative video tracks (e.g. camera angles)
		return [];
	}

	selectVideo(_video: Track): void {
		// hls.js does not support alternative video tracks
	}

	get audios(): Track[] {
		const audio = selectAudioTrack(this._store.state);
		if (!audio) return [];
		return audio.audioTrackList.map((track, i) => ({
			id: track.id ?? i.toString(),
			label: track.label,
			language: track.language,
			selected: track.enabled,
		}));
	}

	selectAudio(audio: Track): void {
		const tracks = selectAudioTrack(this._store.state);
		if (!tracks) return;
		tracks.selectAudioTrack(audio.id);
	}

	get subtitles(): Track[] {
		return this._store.textTrackList
			.map((x, i) => ({
				id: i.toString(),
				kind: x.kind,
				label: x.label,
				language: x.language,
				selected: x.mode === "showing",
			}))
			.filter((x) => x.kind === "subtitles" || x.kind === "captions");
	}

	selectSubtitle(subtitle?: Track): void {
		for (let i = 0; i < this._store.textTrackList.length; i++) {
			const track = this._store.textTrackList[i]!;
			if (track.kind !== "subtitles" && track.kind !== "captions") continue;
			track.mode =
				subtitle && i.toString() === subtitle.id ? "showing" : "hidden";
		}
	}

	get rendition(): Rendition[] {
		function isSameRendition(
			a: MediaVideoRendition,
			b: MediaVideoRendition | null,
		): boolean {
			return (
				b != null &&
				a.id === b.id &&
				a.width === b.width &&
				a.height === b.height &&
				a.bitrate === b.bitrate
			);
		}

		const quality = selectQuality(this._store.state);
		if (!quality) return [];
		const active = quality.activeVideoRendition;
		return quality.videoRenditionList.map((rendition, i) => ({
			id: rendition.id ?? i.toString(),
			width: rendition.width ?? 0,
			height: rendition.height ?? 0,
			bitrate: rendition.bitrate ?? 0,
			selected: rendition.selected || isSameRendition(rendition, active),
		}));
	}

	selectRendition(rendition?: Rendition): void {
		const tracks = selectQuality(this._store.state);
		if (!tracks) return;
		tracks.selectVideoRendition(rendition ? rendition.id : "auto");
	}

	private setPlaybackState(state: MediaSessionPlaybackState): void {
		if (typeof navigator === "undefined" || !("mediaSession" in navigator)) {
			return;
		}
		if (this._showNotification) navigator.mediaSession.playbackState = state;
	}

	private updateMediaSession(): void {
		if (typeof navigator === "undefined" || !("mediaSession" in navigator)) {
			return;
		}
		const session = navigator.mediaSession;
		const actions: MediaSessionAction[] = [
			"play",
			"pause",
			"seekbackward",
			"seekforward",
			"seekto",
			"previoustrack",
			"nexttrack",
		];

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
				artwork: metadata.imageLink ? [{ src: metadata.imageLink }] : [],
			});
		}

		const set = (
			action: MediaSessionAction,
			handler: MediaSessionActionHandler | null,
		) => {
			try {
				session.setActionHandler(action, handler);
			} catch {}
		};
		set("play", () => this.play());
		set("pause", () => this.pause());
		set("seekbackward", (d) => this.seekBy(-(d.seekOffset ?? 10)));
		set("seekforward", (d) => this.seekBy(d.seekOffset ?? 10));
		set("seekto", (d) => {
			if (d.seekTime != null) this.currentTime = d.seekTime;
		});
		set("previoustrack", this.hasPrev ? () => this.playPrev() : null);
		set("nexttrack", this.hasNext ? () => this.playNext() : null);
	}
}
