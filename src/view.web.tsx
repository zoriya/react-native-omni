import type { HlsMediaConfig } from "@videojs/core/dom/media/hls-js";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video } from "@videojs/react/video";
import { type CSSProperties, useMemo, useRef } from "react";
import type { WebOmniPlayer } from "./player.web";
import { usePlayer } from "./provider";
import { VideoPlayer } from "./provider.web";
import type { Subtitle } from "./types/source";
import type { OmniViewProps } from "./types/view";

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
	const player = usePlayer() as WebOmniPlayer;
	const containerRef = useRef<HTMLDivElement>(null);

	const src = player.source?.src[0];
	const Tech = src?.uri.endsWith("m3u8") ? HlsJsVideo : Video;

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

	return (
		<VideoPlayer.Container ref={containerRef} style={style}>
			{src && (
				<Tech
					src={src.uri}
					config={config}
					autoPlay={autoplay}
					playsInline
					crossOrigin="anonymous"
					style={{
						width: "100%",
						height: "100%",
						objectFit: "contain",
					}}
				>
					<SubtitleTracks subtitles={player.source?.subtitles ?? []} />
				</Tech>
			)}
		</VideoPlayer.Container>
	);
};
