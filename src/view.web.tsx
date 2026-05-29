import { HlsVideo } from "@videojs/react/media/hls-video";
import { Video } from "@videojs/react/video";
import type { CSSProperties } from "react";
import { useRef } from "react";
import type { WebOmniPlayer } from "./player.web";
import { usePlayer, VideoPlayer } from "./provider.web";
import type { OmniViewProps } from "./types/view";

export const OmniView = ({
	style,
	autoplay,
}: OmniViewProps & { style: CSSProperties }) => {
	const player = usePlayer() as WebOmniPlayer;
	const containerRef = useRef<HTMLDivElement>(null);

	const uri = player.source?.src[0]?.uri;
	const Tech = uri?.endsWith("m3u8") ? HlsVideo : Video;

	return (
		<VideoPlayer.Container ref={containerRef} style={style}>
			{uri && (
				<Tech
					src={uri}
					autoPlay={autoplay}
					playsInline
					crossOrigin="anonymous"
				/>
			)}
		</VideoPlayer.Container>
	);
};
