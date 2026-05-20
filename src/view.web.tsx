import "@videojs/react/video/skin.css";

import { memo, useEffect, useRef } from "react";
import { Video } from "@videojs/react/video";
import type { CSSProperties } from "react";
import { VideoPlayer, usePlayer } from "./provider.web";
import type { OmniViewProps } from "./types/view";

export const OmniView = memo(
	({
		style,
		autoplay,
		autoPip,
	}: OmniViewProps & { style: CSSProperties }) => {
		const player = usePlayer();
		const containerRef = useRef<HTMLDivElement>(null);

		useEffect(() => {
			const media = containerRef.current?.querySelector("video");
			(player as any).setMediaElement(media ?? null);
		}, []);

		useEffect(() => {
			if (!autoPip) return;
			const media = containerRef.current?.querySelector("video");
			if (media && document.pictureInPictureEnabled) {
				media.requestPictureInPicture().catch(() => {});
			}
		}, [autoPip]);

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
	},
);
