import type { HlsMediaConfig } from "@videojs/core/dom/media/hls-js";
import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video } from "@videojs/react/video";
import {
	type CSSProperties,
	type RefObject,
	useEffect,
	useMemo,
	useRef,
	useSyncExternalStore,
} from "react";
import {
	getSubtitleFormat,
	isCustomSubtitle,
	type WebOmniPlayer,
} from "./player.web";
import { usePlayer, VideoPlayer } from "./provider.web";
import type { SubtitleAssets } from "./types/subtitles";
import type { OmniViewProps } from "./types/view";

const SubtitleOverlay = ({
	video,
	assets,
	fonts,
}: {
	video: RefObject<HTMLVideoElement>;
	assets?: SubtitleAssets;
	fonts?: string[];
}) => {
	const player = usePlayer() as WebOmniPlayer;
	const subtitle = useSyncExternalStore(
		player.subscribeOverlaySubtitle,
		player.getOverlaySubtitle,
		() => null,
	);
	const assetsRef = useRef(assets);
	assetsRef.current = assets;
	const fontsRef = useRef(fonts);
	fontsRef.current = fonts;

	useEffect(() => {
		const el = video.current;
		if (!el || !subtitle) return;
		let renderer: { destroy(): void } | null = null;
		let cancelled = false;
		const attach = (created: { destroy(): void }) => {
			if (cancelled) created.destroy();
			else renderer = created;
		};

		if (getSubtitleFormat(subtitle) === "ass") {
			const jassub = assetsRef.current?.jassub;
			const fonts = fontsRef.current;
			// The video's source resolution (its highest available rendition).
			// jassub uses this as libass' storage size so subtitles stay sharp
			// even while a low-bitrate rendition is playing — otherwise the
			// raster tracks the currently-decoded (possibly tiny) frame size.
			const renditions = player.rendition;
			const videoWidth = Math.max(0, ...renditions.map((r) => r.width));
			const videoHeight = Math.max(0, ...renditions.map((r) => r.height));
			import("jassub").then(({ default: JASSUB }) => {
				const instance = new JASSUB({
					video: el,
					subUrl: subtitle.link,
					...(videoWidth && { videoWidth }),
					...(videoHeight && { videoHeight }),
					...(fonts?.length && { fonts }),
					...(jassub?.workerUrl && { workerUrl: jassub.workerUrl }),
					...(jassub?.wasmUrl && { wasmUrl: jassub.wasmUrl }),
					...(jassub?.modernWasmUrl && { modernWasmUrl: jassub.modernWasmUrl }),
					...(jassub?.fontUrl && {
						availableFonts: { "liberation sans": jassub.fontUrl },
						defaultFont: "liberation sans",
					}),
				});
				attach({ destroy: () => instance.destroy() });
			});
		} else {
			const pgs = assetsRef.current?.pgs;
			import("libpgs").then(({ PgsRenderer }) => {
				const instance = new PgsRenderer({
					video: el,
					subUrl: subtitle.link,
					workerUrl:
						pgs?.workerUrl ??
						new URL("libpgs/dist/libpgs.worker.js", import.meta.url).href,
				});
				attach({ destroy: () => instance.dispose() });
			});
		}

		return () => {
			cancelled = true;
			renderer?.destroy();
		};
	}, [video, subtitle, player]);

	return null;
};

export const OmniView = ({
	style,
	autoplay,
	subtitleAssets,
}: OmniViewProps & { style: CSSProperties }) => {
	const player = usePlayer() as WebOmniPlayer;
	const containerRef = useRef<HTMLDivElement>(null);
	const ref = useRef<HTMLVideoElement>(undefined!);

	const src = player.source?.src[0];
	const isHls =
		src?.mimeType?.toLowerCase().includes("mpegurl") ||
		src?.uri.split(/[?#]/)[0]?.toLowerCase().endsWith(".m3u8") ||
		false;
	const Tech = isHls ? HlsJsVideo : Video;

	const headersRef = useRef(src?.headers);
	headersRef.current = src?.headers;
	const castData = player.source?.castData;

	const config = useMemo<HlsMediaConfig>(
		() =>
			({
				hlsJs: {
					xhrSetup: (xhr: XMLHttpRequest) => {
						const headers = headersRef.current;
						if (!headers) return;
						for (const [key, value] of Object.entries(headers)) {
							if (value) xhr.setRequestHeader(key, value);
						}
					},
				},
				googleCast: {
					receiver: player.castOptions?.receiverApplicationId,
					customData: castData ?? null,
				},
			}) as HlsMediaConfig,
		[player.castOptions, castData],
	);

	return (
		<VideoPlayer.Container
			ref={containerRef}
			style={{ position: "relative", ...style }}
		>
			{src && (
				<Tech
					ref={ref}
					src={src.uri}
					config={config}
					autoPlay={autoplay}
					playsInline
					crossOrigin="anonymous"
					style={{ width: "100%", height: "100%", objectFit: "contain" }}
				>
					{(player.source?.subtitles ?? [])
						.filter((subtitle) => !isCustomSubtitle(subtitle))
						.map((subtitle) => (
							<track
								key={subtitle.id}
								id={subtitle.id}
								kind="subtitles"
								src={subtitle.link}
								srcLang={subtitle.language}
								label={subtitle.label ?? subtitle.language ?? subtitle.id}
							/>
						))}
				</Tech>
			)}
			<SubtitleOverlay
				video={ref}
				assets={subtitleAssets}
				fonts={player.source?.fonts}
			/>
		</VideoPlayer.Container>
	);
};
