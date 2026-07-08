import type { HlsMediaConfig } from "@videojs/core/dom/media/hls-js";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video } from "@videojs/react/video";
import { type CSSProperties, useMemo, useRef } from "react";
import { useSource, VideoPlayer } from "./provider.web";
import type { Subtitle, VideoSrc } from "./types/source";
import type { OmniViewProps } from "./types/view";

const isHls = (src: VideoSrc): boolean => {
	if (src.mimeType) {
		return /mpegurl/i.test(src.mimeType);
	}
	return /\.m3u8($|\?)/i.test(src.uri);
};

const SubtitleTracks = ({ subtitles }: { subtitles: Subtitle[] }) => (
	<>
		{subtitles.map((subtitle) => (
			<track
				key={subtitle.id}
				kind="subtitles"
				src={subtitle.link}
				srcLang={subtitle.language}
				label={subtitle.label ?? subtitle.language ?? subtitle.id}
			/>
		))}
	</>
);

export const OmniView = ({
	style,
	autoplay,
}: OmniViewProps & { style: CSSProperties }) => {
	const source = useSource();
	const containerRef = useRef<HTMLDivElement>(null);

	const src = source?.src[0];
	const hls = src ? isHls(src) : false;

	// Forward per-source request headers to hls.js xhr requests. Plain <video>
	// requests cannot carry custom headers from the browser, so this only
	// applies to the adaptive (hls.js) tech.
	const config = useMemo<HlsMediaConfig | undefined>(() => {
		const headers = src?.headers;
		if (!headers || Object.keys(headers).length === 0) return undefined;
		return {
			hlsJs: {
				xhrSetup: (xhr: XMLHttpRequest) => {
					for (const [key, value] of Object.entries(headers)) {
						xhr.setRequestHeader(key, value);
					}
				},
			},
		};
	}, [src?.headers]);

	const mediaStyle: CSSProperties = {
		width: "100%",
		height: "100%",
		objectFit: "contain",
	};

	return (
		<VideoPlayer.Container ref={containerRef} style={style}>
			{src &&
				(hls ? (
					<HlsJsVideo
						src={src.uri}
						config={config}
						autoPlay={autoplay}
						playsInline
						crossOrigin="anonymous"
						style={mediaStyle}
					>
						<SubtitleTracks subtitles={source?.subtitles ?? []} />
					</HlsJsVideo>
				) : (
					<Video
						src={src.uri}
						autoPlay={autoplay}
						playsInline
						crossOrigin="anonymous"
						style={mediaStyle}
					>
						<SubtitleTracks subtitles={source?.subtitles ?? []} />
					</Video>
				))}
		</VideoPlayer.Container>
	);
};
