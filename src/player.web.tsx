import type { VideoPlayerStore } from "@videojs/core/dom";
import {
	selectAudioTrack,
	selectQuality,
	selectTextTrack,
} from "@videojs/react";
import { stateMapper } from "./events.web";
import type {
	CastStatus,
	OmniPlayer,
	PlayerStatus,
	Rendition,
	Track,
} from "./types/player";
import type { CastOptions, Source, Subtitle } from "./types/source";

export type SubtitleFormat = "vtt" | "ass" | "pgs" | "native";

export const getSubtitleFormat = (subtitle: {
	mimeType?: string;
	link: string;
}): SubtitleFormat => {
	const mime = subtitle.mimeType?.toLowerCase() ?? "";
	const ext = subtitle.link.split(/[?#]/)[0]?.split(".").pop()?.toLowerCase();
	if (
		mime.includes("ass") ||
		mime.includes("ssa") ||
		ext === "ass" ||
		ext === "ssa"
	)
		return "ass";
	if (mime.includes("pgs") || ext === "sup") return "pgs";
	if (mime.includes("vtt") || ext === "vtt") return "vtt";
	return "native";
};

export const isCustomSubtitle = (subtitle: {
	mimeType?: string;
	link: string;
}): boolean => {
	const format = getSubtitleFormat(subtitle);
	return format === "ass" || format === "pgs";
};

export class WebOmniPlayer implements OmniPlayer {
	_store: VideoPlayerStore;
	onPrev = new Set<() => void>();
	onNext = new Set<() => void>();

	constructor(store: VideoPlayerStore) {
		this._store = store;
	}

	get castStatus(): CastStatus {
		return stateMapper.castStatus.mapper(this._store.state);
	}

	toggleCastStatus(): void {
		this._store.state.toggleRemotePlayback();
	}

	castOptions: CastOptions | null = null;
	_source: Source | undefined = undefined;
	private _showNotification = false;

	// Selected ASS/PGS subtitle (drawn by the overlay); `null` when the active
	// subtitle is native or off. Exposed as an external store so the view can
	// react to selection changes.
	private overlaySubtitle: Subtitle | null = null;
	private overlayListeners = new Set<() => void>();

	private sourceListeners = new Set<() => void>();
	subscribeSource = (callback: () => void): (() => void) => {
		this.sourceListeners.add(callback);
		return () => this.sourceListeners.delete(callback);
	};

	get source(): Source | undefined {
		return this._source;
	}

	set source(source: Source | undefined) {
		this._source = source;
		// Drop the overlay subtitle if it is not part of the new source.
		if (
			this.overlaySubtitle &&
			!source?.subtitles.some((s) => s.id === this.overlaySubtitle?.id)
		) {
			this.setOverlaySubtitle(null);
		}
		this.updateMediaSession();
		for (const listener of this.sourceListeners) listener();
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
		return stateMapper.audios.mapper(selectAudioTrack(this._store.state));
	}

	selectAudio(audio: Track): void {
		const tracks = selectAudioTrack(this._store.state);
		if (!tracks) return;
		tracks.selectAudioTrack(audio.id);
	}

	private get overlaySubtitles(): Subtitle[] {
		return (this._source?.subtitles ?? []).filter(isCustomSubtitle);
	}

	get subtitles(): Track[] {
		const textTracks = selectTextTrack(this._store.state)?.textTrackList ?? [];
		return textTracks
			.filter((x) => x.kind === "subtitles" || x.kind === "captions")
			.map((track, i) => ({
				id: track.id!,
				index: i,
				label: track.label,
				language: track.language,
				selected:
					track.mode === "showing" || this.overlaySubtitle?.id === track.id,
			}));
	}

	selectSubtitle(subtitle?: Track): void {
		const tracks = selectTextTrack(this._store.state);
		const overlay = subtitle
			? this.overlaySubtitles.find((s) => s.id === subtitle.id)
			: undefined;

		if (this.castStatus === "connected") {
			tracks?.selectSubtitlesTrack(subtitle ? subtitle.id : "off");
			this.setOverlaySubtitle(null);
			return;
		}

		tracks?.selectSubtitlesTrack(overlay || !subtitle ? "off" : subtitle.id);
		this.setOverlaySubtitle(overlay ?? null);
	}

	private setOverlaySubtitle(sub: Subtitle | null): void {
		if (this.overlaySubtitle === sub) return;
		this.overlaySubtitle = sub;
		for (const listener of this.overlayListeners) listener();
	}

	// External store used by the view to render the ASS/PGS overlay.
	subscribeOverlaySubtitle = (callback: () => void): (() => void) => {
		this.overlayListeners.add(callback);
		return () => this.overlayListeners.delete(callback);
	};

	getOverlaySubtitle = (): Subtitle | null => this.overlaySubtitle;

	get renditions(): Rendition[] {
		return stateMapper.renditions.mapper(selectQuality(this._store.state));
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
