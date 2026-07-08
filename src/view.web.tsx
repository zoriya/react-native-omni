import type { HlsMediaConfig } from "@videojs/core/dom/media/hls-js";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video } from "@videojs/react/video";
import {
	type CSSProperties,
	useEffect,
	useMemo,
	useRef,
	useState,
	useSyncExternalStore,
} from "react";
import type { WebOmniPlayer } from "./player.web";
import {
	usePlayer,
	useSource,
	useSubtitleAssets,
	VideoPlayer,
} from "./provider.web";
import {
	createSubtitleRenderer,
	isCustomSubtitle,
	type SubtitleRenderer,
} from "./subtitles.web";
import type { Subtitle, VideoSrc } from "./types/source";
import type { OmniViewProps } from "./types/view";

const isHls = (src: VideoSrc): boolean => {
	if (src.mimeType) {
		return /mpegurl/i.test(src.mimeType);
	}
	return /\.m3u8($|\?)/i.test(src.uri);
};

// Only WebVTT / plain text tracks can be rendered by a native <track>; ASS and
// PGS are drawn by the overlay renderer instead.
const NativeSubtitleTracks = ({ subtitles }: { subtitles: Subtitle[] }) => (
	<>
		{subtitles
			.filter((subtitle) => !isCustomSubtitle(subtitle))
			.map((subtitle) => (
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

// Draws the selected ASS/PGS subtitle on a canvas over the video element.
const SubtitleOverlay = ({ video }: { video: HTMLVideoElement | null }) => {
	const player = usePlayer() as WebOmniPlayer;
	const assets = useSubtitleAssets();
	const subtitle = useSyncExternalStore(
		player.subscribeCustomSubtitle,
		player.getCustomSubtitle,
		() => null,
	);

	useEffect(() => {
		if (!video || !subtitle) return;
		let renderer: SubtitleRenderer | null = null;
		let cancelled = false;
		createSubtitleRenderer(video, subtitle, assets).then((created) => {
			if (cancelled) {
				created?.destroy();
				return;
			}
			renderer = created;
		});
		return () => {
			cancelled = true;
			renderer?.destroy();
		};
	}, [video, subtitle, assets]);

	return null;
};

export const OmniView = ({
	style,
	autoplay,
}: OmniViewProps & { style: CSSProperties }) => {
	const source = useSource();
	const containerRef = useRef<HTMLDivElement>(null);
	const [video, setVideo] = useState<HTMLVideoElement | null>(null);

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
	const subtitles = source?.subtitles ?? [];

	return (
		<VideoPlayer.Container ref={containerRef} style={style}>
			{src &&
				(hls ? (
					<HlsJsVideo
						ref={setVideo}
						src={src.uri}
						config={config}
						autoPlay={autoplay}
						playsInline
						crossOrigin="anonymous"
						style={mediaStyle}
					>
						<NativeSubtitleTracks subtitles={subtitles} />
					</HlsJsVideo>
				) : (
					<Video
						ref={setVideo}
						src={src.uri}
						autoPlay={autoplay}
						playsInline
						crossOrigin="anonymous"
						style={mediaStyle}
					>
						<NativeSubtitleTracks subtitles={subtitles} />
					</Video>
				))}
			<SubtitleOverlay video={video} />
		</VideoPlayer.Container>
	);
};
