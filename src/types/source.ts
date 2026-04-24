export interface BaseSource {
	src: VideoSrc[];
	startTime?: number;
	subtitles: Subtitle[];
	metadata?: Metadata;
	mixAudio?: MixAudioMode;
}

export interface Source extends BaseSource {
	prev?: BaseSource
	next?: BaseSource
}

export interface VideoSrc {
	uri: string;
	mimeType?: string;
	// header sends with xhr requests (especially useful for HLS)
	// might not be available depending on browser/platform.
	headers: Record<string, string>;
}

export interface Subtitle {
	id: string;
	link: string;
	mimeType?: string;
	language?: string;
	label?: string;
}

export interface Metadata {
	title: string;
	album?: string;
	artist?: string;
	imageLink?: string;
}

export type MixAudioMode = "mixWithOthers" | "doNotMix" | "duckOthers" | "auto";
