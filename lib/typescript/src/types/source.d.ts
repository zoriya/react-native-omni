export interface Source {
    src: VideoSrc;
    startTime?: number;
    subtitles: Subtitle[];
    fonts?: string[];
    metadata?: Metadata;
    mixAudio?: MixAudioMode;
    castId?: string;
    castData?: Record<string, string>;
}
export interface CastOptions {
    receiverApplicationId?: string;
}
export interface VideoSrc {
    uri: string;
    mimeType?: string;
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
//# sourceMappingURL=source.d.ts.map