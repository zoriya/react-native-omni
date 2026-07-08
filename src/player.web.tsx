import type { MediaVideoRendition } from "@videojs/core";
import type { VideoPlayerStore } from "@videojs/core/dom";
import { selectAudioTrack, selectQuality } from "@videojs/react";
import { stateMapper } from "./events.web";
import { isCustomSubtitle } from "./subtitles.web";
import type {
	OmniPlayer,
	PlayerStatus,
	Rendition,
	Track,
} from "./types/player";
import type { Source, Subtitle } from "./types/source";

// Track id prefixes distinguish native `<track>` entries from external
// ASS/PGS subtitles that are rendered by a JS overlay.
const NATIVE_PREFIX = "n:";
const CUSTOM_PREFIX = "x:";

/**
 * The quality feature reports the actively-playing rendition (`active`)
 * separately from the list. Match them so the UI can highlight which
 * rendition is currently on screen, including while ABR ("auto") is on.
 */
const isSameRendition = (
	a: MediaVideoRendition,
	b: MediaVideoRendition | null,
): boolean =>
	b != null &&
	a.id === b.id &&
	a.width === b.width &&
	a.height === b.height &&
	a.bitrate === b.bitrate;

export class WebOmniPlayer implements OmniPlayer {
	_store: VideoPlayerStore;
	onPrev?: () => void;
	onNext?: () => void;

	constructor(store: VideoPlayerStore) {
		this._store = store;
	}

	_source: Source | null = null;
	private _showNotification = false;

	// Selected ASS/PGS subtitle (rendered by the overlay); `null` when the
	// active subtitle is native or off. Exposed as an external store so the
	// view can react to selection changes.
	private _customSubtitle: Subtitle | null = null;
	private _customSubtitleListeners = new Set<() => void>();

	get source(): Source | null {
		return this._source;
	}

	set source(source: Source | null) {
		this._source = source;
		// The previous overlay subtitle no longer belongs to the new source.
		if (
			this._customSubtitle &&
			!source?.subtitles.some((s) => s.id === this._customSubtitle?.id)
		) {
			this.setCustomSubtitle(null);
		}
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
		this.onPrev?.();
	}

	playNext(): void {
		this.onNext?.();
	}

	get hasPrev(): boolean {
		return this._source?.metadata?.hasPrev ?? false;
	}

	get hasNext(): boolean {
		return this._source?.metadata?.hasNext ?? false;
	}

	get videos(): Track[] {
		// hls.js only ever exposes a single "main" video track; alternative video
		// tracks (e.g. camera angles) are not supported.
		return [];
	}

	selectVideo(_video: Track): void {
		// hls.js does not support alternative video tracks
	}

	get audios(): Track[] {
		const audio = selectAudioTrack(this._store.state);
		if (!audio) return [];
		return audio.audioTrackList.map((track, i) => ({
			// The id doubles as the menu value passed back to `selectAudioTrack`.
			id: track.id ?? i.toString(),
			label: track.label,
			language: track.language,
			selected: track.enabled,
		}));
	}

	selectAudio(audio: Track): void {
		selectAudioTrack(this._store.state)?.selectAudioTrack(audio.id);
	}

	get subtitles(): Track[] {
		// Native text tracks (in-manifest + external WebVTT rendered as <track>).
		const native: Track[] = [];
		this._store.textTrackList.forEach((track, i) => {
			if (track.kind !== "subtitles" && track.kind !== "captions") return;
			native.push({
				id: `${NATIVE_PREFIX}${i}`,
				label: track.label,
				language: track.language,
				selected: track.mode === "showing",
			});
		});

		// External ASS/PGS subtitles rendered by the overlay.
		const custom: Track[] = (this._source?.subtitles ?? [])
			.filter(isCustomSubtitle)
			.map((sub) => ({
				id: `${CUSTOM_PREFIX}${sub.id}`,
				label: sub.label,
				language: sub.language,
				selected: this._customSubtitle?.id === sub.id,
			}));

		return [...native, ...custom];
	}

	selectSubtitle(subtitle?: Track): void {
		const id = subtitle?.id;

		// External ASS/PGS subtitle -> overlay renderer.
		if (id?.startsWith(CUSTOM_PREFIX)) {
			const subId = id.slice(CUSTOM_PREFIX.length);
			const sub = this._source?.subtitles.find((s) => s.id === subId);
			if (sub) {
				this.setNativeSubtitle(undefined);
				this.setCustomSubtitle(sub);
				return;
			}
		}

		// Native subtitle or off.
		this.setCustomSubtitle(null);
		this.setNativeSubtitle(id?.startsWith(NATIVE_PREFIX) ? id : undefined);
	}

	private setNativeSubtitle(id?: string): void {
		for (let i = 0; i < this._store.textTrackList.length; i++) {
			const track = this._store.textTrackList[i]!;
			if (track.kind !== "subtitles" && track.kind !== "captions") continue;
			track.mode = id === `${NATIVE_PREFIX}${i}` ? "showing" : "hidden";
		}
	}

	private setCustomSubtitle(sub: Subtitle | null): void {
		if (this._customSubtitle === sub) return;
		this._customSubtitle = sub;
		for (const listener of this._customSubtitleListeners) listener();
	}

	// External store used by the view to render the ASS/PGS overlay.
	subscribeCustomSubtitle = (callback: () => void): (() => void) => {
		this._customSubtitleListeners.add(callback);
		return () => this._customSubtitleListeners.delete(callback);
	};

	getCustomSubtitle = (): Subtitle | null => this._customSubtitle;

	get rendition(): Rendition[] {
		const quality = selectQuality(this._store.state);
		if (!quality) return [];
		const active = quality.activeVideoRendition;
		return quality.videoRenditionList.map((rendition, i) => ({
			// The id doubles as the menu value passed back to `selectVideoRendition`.
			id: rendition.id ?? i.toString(),
			width: rendition.width ?? 0,
			height: rendition.height ?? 0,
			bitrate: rendition.bitrate ?? 0,
			// Mark the rendition currently on screen so callers can highlight it
			// even while ABR ("auto") is picking the level.
			selected: rendition.selected || isSameRendition(rendition, active),
		}));
	}

	selectRendition(rendition?: Rendition): void {
		// `"auto"` restores adaptive (ABR) selection.
		selectQuality(this._store.state)?.selectVideoRendition(
			rendition ? rendition.id : "auto",
		);
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
