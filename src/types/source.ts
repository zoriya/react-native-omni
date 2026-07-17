export interface Source {
	src: VideoSrc[];
	startTime?: number;
	subtitles: Subtitle[];
	// fonts that can be used by ass subtitles on the web (native will use
	// embedded fonts)
	fonts?: string[];
	metadata?: Metadata;
	mixAudio?: MixAudioMode;
	// Opaque, consumer-defined payload forwarded verbatim to the cast receiver
	// as the load request's customData. omni does not interpret it.
	castData?: unknown;
}

export interface CastOptions {
	receiverApplicationId?: string;
}

export interface VideoSrc {
	uri: string;
	mimeType?: string;
	// header sends with xhr requests (especially useful for HLS)
	// might not be available depending on browser/platform.
	headers: Record<string, string | undefined>;
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
	hasPrev?: boolean;
	hasNext?: boolean;
}

export type MixAudioMode = "mixWithOthers" | "doNotMix" | "duckOthers" | "auto";
