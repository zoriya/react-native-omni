import type { Rendition, Track } from "./player";

export interface OmniEvents {
	end: () => void;
	prev: () => void;
	next: () => void;
	error: (type: ErrorType, message: string) => void;
	audioFocusChange: (status: AudioFocus) => void;

	videoTrackChange: (track: Track) => void;
	audioTrackChange: (track: Track) => void;
	subtitleChange: (track?: Track) => void;
	renditionChange: (rendition: Rendition) => void;
}

export interface CastLoadRequest {
	castId: string;
	customData?: Record<string, string>;
	startTime?: number;
	autoplay: boolean;
}

export type ErrorType = string;
export type AudioFocus = string;
