import { createPlayer } from "@videojs/react";
import { videoFeatures } from "@videojs/react/video";
import type { VideoPlayerStore } from "@videojs/core/dom";
import {
	createContext,
	type ReactNode,
	useContext,
	useEffect,
} from "react";
import type {
	OmniPlayer,
	PlayerStatus,
	Rendition,
	Track,
} from "./types/player";
import type { Source } from "./types/source";
import { useLazyRef } from "./utils/lazy-ref";

interface VideoTrack {
	readonly id: string;
	readonly label: string;
	readonly language: string;
	enabled: boolean;
}

interface VideoTrackList {
	readonly length: number;
	[index: number]: VideoTrack;
	addEventListener(type: "change", listener: () => void): void;
	removeEventListener(type: "change", listener: () => void): void;
}

interface AudioTrack {
	readonly id: string;
	readonly label: string;
	readonly language: string;
	enabled: boolean;
}

interface AudioTrackList {
	readonly length: number;
	[index: number]: AudioTrack;
	addEventListener(type: "change", listener: () => void): void;
	removeEventListener(type: "change", listener: () => void): void;
}

interface HtmlVideoElementWithTracks extends HTMLVideoElement {
	videoTracks?: VideoTrackList;
	audioTracks?: AudioTrackList;
}

export const VideoPlayer = createPlayer({ features: videoFeatures });

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	showNotification = false,
}: {
	source: Source;
	children: ReactNode;
	showNotification?: boolean;
}) => {
	return (
		<VideoPlayer.Provider>
			<PlayerInitializer source={source} showNotification={showNotification}>
				{children}
			</PlayerInitializer>
		</VideoPlayer.Provider>
	);
};

const PlayerInitializer = ({
	children,
	source,
	showNotification,
}: {
	children: ReactNode;
	source: Source;
	showNotification: boolean;
}) => {
	const store = VideoPlayer.usePlayer();
	const player = useLazyRef(() => new WebOmniPlayer(store, source));

	useEffect(() => {
		player.updateSource(source);
	}, [source]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => useContext(PlayerCtx);

interface ExtendedStoreState {
	onPrev?: () => void;
	onNext?: () => void;
}

export class WebOmniPlayer implements OmniPlayer {
	private _store: VideoPlayerStore;
	private _source: Source;
	private _showNotification = false;
	private _mediaElement: HtmlVideoElementWithTracks | null = null;

	constructor(store: VideoPlayerStore, source: Source) {
		this._store = store;
		this._source = source;
	}

	setMediaElement(media: HTMLVideoElement | null) {
		this._mediaElement = media as HtmlVideoElementWithTracks | null;
		if (media) {
			this.setupSubtitleTracks(media);
		}
	}

	get mediaElement(): HtmlVideoElementWithTracks | null {
		return this._mediaElement;
	}

	get store(): VideoPlayerStore {
		return this._store;
	}

	updateSource(source: Source) {
		this._source = source;
	}

	get source(): Source {
		return this._source;
	}

	set source(source: Source) {
		this.updateSource(source);
	}

	get showNotification(): boolean {
		return this._showNotification;
	}

	set showNotification(value: boolean) {
		this._showNotification = value;
	}

	get status(): PlayerStatus {
		const state = this._store.state;
		if (state.error) return "error";
		if (state.canPlay) return "readyToPlay";
		if (this._source.src.length > 0) return "loading";
		return "idle";
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
		const lastRange = buffered[buffered.length - 1];
		return lastRange ? lastRange[1] : 0;
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
		return true;
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

	get onPrev(): (() => void) | undefined {
		return (this._store.state as ExtendedStoreState).onPrev;
	}

	set onPrev(cb: (() => void) | undefined) {
		(this._store.state as ExtendedStoreState).onPrev = cb;
	}

	get onNext(): (() => void) | undefined {
		return (this._store.state as ExtendedStoreState).onNext;
	}

	set onNext(cb: (() => void) | undefined) {
		(this._store.state as ExtendedStoreState).onNext = cb;
	}

	playPrev(): void {
		this.onPrev?.();
	}

	playNext(): void {
		this.onNext?.();
	}

	get hasPrev(): boolean {
		return this._source.metadata?.hasPrev ?? false;
	}

	get hasNext(): boolean {
		return this._source.metadata?.hasNext ?? false;
	}

	get videos(): Track[] {
		const videoTracks = this._mediaElement?.videoTracks;
		if (!videoTracks) return [];
		const tracks: Track[] = [];
		for (let i = 0; i < videoTracks.length; i++) {
			const track = videoTracks[i];
			if (track) {
				tracks.push({
					id: track.id || `video-${i}`,
					label: track.label,
					language: track.language,
					selected: track.enabled,
				});
			}
		}
		return tracks;
	}

	selectVideo(video: Track): void {
		const videoTracks = this._mediaElement?.videoTracks;
		if (!videoTracks) return;
		for (let i = 0; i < videoTracks.length; i++) {
			const track = videoTracks[i];
			if (track) {
				track.enabled = track.id === video.id;
			}
		}
	}

	get audios(): Track[] {
		const audioTracks = this._mediaElement?.audioTracks;
		if (!audioTracks) return [];
		const tracks: Track[] = [];
		for (let i = 0; i < audioTracks.length; i++) {
			const track = audioTracks[i];
			if (track) {
				tracks.push({
					id: track.id || `audio-${i}`,
					label: track.label,
					language: track.language,
					selected: track.enabled,
				});
			}
		}
		return tracks;
	}

	selectAudio(audio: Track): void {
		const audioTracks = this._mediaElement?.audioTracks;
		if (!audioTracks) return;
		for (let i = 0; i < audioTracks.length; i++) {
			const track = audioTracks[i];
			if (track) {
				track.enabled = track.id === audio.id;
			}
		}
	}

	get subtitles(): Track[] {
		const sourceSubtitles = this._source.subtitles.map((s) => ({
			id: s.id,
			label: s.label,
			language: s.language,
			selected: false,
		}));

		const textTracks = this._mediaElement?.textTracks;
		if (!textTracks) return sourceSubtitles;

		const mediaTracks: Track[] = [];
		for (let i = 0; i < textTracks.length; i++) {
			const track = textTracks[i];
			if (track && (track.kind === "subtitles" || track.kind === "captions")) {
				mediaTracks.push({
					id: track.id || `text-${i}`,
					label: track.label,
					language: track.language,
					selected: track.mode === "showing",
				});
			}
		}

		return sourceSubtitles.length > 0 ? sourceSubtitles : mediaTracks;
	}

	selectSubtitle(subtitle?: Track): void {
		const textTracks = this._mediaElement?.textTracks;
		if (!textTracks) return;
		for (let i = 0; i < textTracks.length; i++) {
			const track = textTracks[i];
			if (track && (track.kind === "subtitles" || track.kind === "captions")) {
				track.mode =
					subtitle && (track.id === subtitle.id || track.label === subtitle.label)
						? "showing"
						: "hidden";
			}
		}
	}

	get rendition(): Rendition[] {
		return [];
	}

	selectRendition(_rendition?: Rendition): void {
		// Auto quality is handled by videojs/HLS
	}

	private setupSubtitleTracks(media: HTMLVideoElement) {
		const existingTracks = media.querySelectorAll("track");
		existingTracks.forEach((t) => t.remove());

		for (const subtitle of this._source.subtitles) {
			const track = document.createElement("track");
			track.kind = "subtitles";
			track.src = subtitle.link;
			if (subtitle.label) track.label = subtitle.label;
			if (subtitle.language) track.srclang = subtitle.language;
			track.id = subtitle.id;
			media.appendChild(track);
		}
	}
}
