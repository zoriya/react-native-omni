import type { Source } from "./source";

export interface OmniPlayer extends OmniPlayerState {
	showNotification?: boolean;

	play(): void;
	pause(): void;
	seekBy(offset: number): void;

	toggleCastStatus(): void;

	// trigger the prev/next event manually, the user has to implement the event
	playPrev(): void;
	playNext(): void;
	readonly hasPrev: boolean;
	readonly hasNext: boolean;

	selectVideo(video: Track): void;
	selectAudio(audio: Track): void;
	selectSubtitle(subtitle?: Track): void;
	selectRendition(rendition?: Rendition): void;
}

export interface OmniPlayerState {
	source?: Source;
	readonly status: PlayerStatus;
	readonly isPlaying: boolean;
	currentTime: number;
	readonly buffered: number;
	readonly duration: number;
	playbackRate: number;
	// between 0 and 1
	volume: number;
	muted: boolean;
	readonly isAutoQuality: boolean;
	readonly castStatus: CastStatus;

	readonly videos: Track[];
	readonly audios: Track[];
	readonly subtitles: Track[];
	readonly renditions: Rendition[];
}

export type PlayerStatus = "idle" | "loading" | "readyToPlay" | "error";

export type CastStatus =
	| "connecting"
	| "connected"
	| "available"
	| "unavailable"
	| "unsupported";

export interface Track {
	readonly id: string;
	// -1 for external tracks
	readonly index: number;
	readonly label?: string;
	readonly language?: string;
	readonly selected: boolean;
}

export interface Rendition {
	readonly id: string;
	readonly width: number;
	readonly height: number;
	readonly bitrate: number;
	readonly selected: boolean;
}

export type AndroidBackend = "vlc" | "exoplayer";

export interface PlayerBackend {
	android?: AndroidBackend;
}
