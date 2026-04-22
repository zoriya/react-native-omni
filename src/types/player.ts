import type { Source } from "./source";

export interface OmniPlayer extends OmniPlayerState {
	source: Source;
	showNotification?: boolean;

	play(): void;
	pause(): void;
	seekBy(offset: number): void;

	// trigger the prev/next event manually, the user has to implement the event
	playPrev(): void;
	playNext(): void;
	readonly hasPrev: boolean;
	readonly hasNext: boolean;

	readonly videos: Track[];
	selectVideo(video: Track): void;
	readonly audios: Track[];
	selectAudio(audio: Track): void;
	readonly subtitles: Track[];
	selectSubtitle(subtitle?: Track): void;
	readonly rendition: Rendition[];
	selectRendition(rendition?: Rendition): void;
}

export interface OmniPlayerState {
	readonly status: PlayerStatus;
	readonly isPlaying: boolean;
	currentTime: number;
	readonly buffered: number;
	readonly duration: number;
	playbackRate: number;
	// between 0 and 1
	volume: number;
	muted: boolean;
}

export type PlayerStatus = "idle" | "loading" | "readyToPlay" | "error";

export interface Track {
	id: string;
	label?: string;
	language?: string;
	selected: boolean;
}

export interface Rendition {
	id: string;
	width: number;
	height: number;
	bitrate: number;
	selected: boolean;
}
