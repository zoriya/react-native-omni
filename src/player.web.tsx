import type { VideoPlayerStore } from "@videojs/core/dom";
import type Hls from "hls.js";
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
	onPrev?: () => void;
	onNext?: () => void;

	constructor(store: VideoPlayerStore) {
		this._store = store;
	}

	private getHls(): Hls | null {
		const media = this._store.target?.media;
		return (media?.engine as Hls | undefined) ?? null;
	}

	source: Source | null = null;

	get showNotification(): boolean {
		// TODO
		return false;
	}

	set showNotification(_: boolean) {
		// TODO
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
		const hls = this.getHls();
		if (!hls) return false;
		return hls.autoLevelEnabled;
	}

	play(): void {
		this._store.play();
	}

	pause(): void {
		this._store.pause();
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
		// TODO
		return false;
	}

	get hasNext(): boolean {
		// TODO
		return false;
	}

	get videos(): Track[] {
		// hls.js does not support alternative video tracks (e.g. camera angles)
		return [];
	}

	selectVideo(_video: Track): void {
		// hls.js does not support alternative video tracks
	}

	get audios(): Track[] {
		const hls = this.getHls();
		if (!hls) return [];
		const currentTrackId = hls.audioTrack;
		return hls.audioTracks.map((track, index) => ({
			id: index.toString(),
			label: track.name,
			language: track.lang,
			selected: index === currentTrackId,
		}));
	}

	selectAudio(audio: Track): void {
		const hls = this.getHls();
		if (!hls) return;
		hls.audioTrack = parseInt(audio.id, 10);
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
		const hls = this.getHls();
		if (!hls) return [];
		const currentLevel = hls.currentLevel;
		return hls.levels.map((level, index) => ({
			id: index.toString(),
			width: level.width,
			height: level.height,
			bitrate: level.bitrate,
			selected: index === currentLevel,
		}));
	}

	selectRendition(rendition?: Rendition): void {
		const hls = this.getHls();
		if (!hls) return;
		hls.nextLevel = rendition ? parseInt(rendition.id, 10) : -1;
	}
}
