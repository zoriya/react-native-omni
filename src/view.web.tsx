import { Video } from "@videojs/react/video";
import type { CSSProperties } from "react";
import { useRef } from "react";
import { usePlayer, VideoPlayer } from "./provider.web";
import type { OmniViewProps } from "./types/view";

export const OmniView = ({
	style,
	autoplay,
}: OmniViewProps & { style: CSSProperties }) => {
	const player = usePlayer();
	const containerRef = useRef<HTMLDivElement>(null);

	const src = player.source.src[0]?.uri;

	return (
		<VideoPlayer.Container ref={containerRef} style={style}>
			{src && (
				<Video
					src={src}
					autoPlay={autoplay}
					playsInline
					crossOrigin="anonymous"
				/>
			)}
		</VideoPlayer.Container>
	);
};
