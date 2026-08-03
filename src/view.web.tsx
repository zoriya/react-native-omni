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
import { usePlayerState } from "./events";
import { getSubtitleFormat, type WebOmniPlayer } from "./player.web";
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
			import("jassub")
				.then(({ default: JASSUB }) => {
					const instance = new JASSUB({
						video: el,
						subUrl: subtitle.link,
						...(videoWidth && { videoWidth }),
						...(videoHeight && { videoHeight }),
						...(fonts?.length && { fonts }),
						...(jassub?.workerUrl && { workerUrl: jassub.workerUrl }),
						...(jassub?.wasmUrl && { wasmUrl: jassub.wasmUrl }),
						...(jassub?.modernWasmUrl && {
							modernWasmUrl: jassub.modernWasmUrl,
						}),
						...(jassub?.fontUrl && {
							availableFonts: { "liberation sans": jassub.fontUrl },
							defaultFont: "liberation sans",
						}),
					});
					attach({ destroy: () => instance.destroy() });
				})
				.catch((e) => console.error("[omni] failed to render ass subtitle", e));
		} else {
			const pgs = assetsRef.current?.pgs;
			import("libpgs")
				.then(({ PgsRenderer }) => {
					const instance = new PgsRenderer({
						video: el,
						subUrl: subtitle.link,
						workerUrl:
							pgs?.workerUrl ??
							new URL("libpgs/dist/libpgs.worker.js", import.meta.url).href,
					});
					attach({ destroy: () => instance.dispose() });
				})
				.catch((e) => console.error("[omni] failed to render pgs subtitle", e));
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
	const source = usePlayerState("source");

	const isHls =
		source?.src?.mimeType?.toLowerCase().includes("mpegurl") ||
		source?.src?.uri.split(/[?#]/)[0]?.toLowerCase().endsWith(".m3u8") ||
		false;
	const Tech = isHls ? HlsJsVideo : Video;

	const headersRef = useRef(source?.src?.headers);
	headersRef.current = source?.src?.headers;
	const castId = source?.castId;
	const castData = source?.castData;

	const config = useMemo<HlsMediaConfig>(
		() => ({
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
				...(castId && { src: castId }),
			},
		}),
		[player.castOptions, castData, castId],
	);

	// While casting, the receiver renders subtitles (the player forwards the
	// selection); skip rendering them locally on the idle <video>.
	const castStatus = usePlayerState("castStatus");

	return (
		<VideoPlayer.Container
			ref={containerRef}
			style={{ position: "relative", ...style }}
		>
			<Tech
				ref={ref}
				src={source?.src.uri}
				config={config}
				autoPlay={autoplay}
				playsInline
				crossOrigin="anonymous"
				style={{ width: "100%", height: "100%", objectFit: "contain" }}
			>
				{(source?.subtitles ?? []).map((subtitle) => (
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
			{castStatus !== "connected" && castStatus !== "connecting" && (
				<SubtitleOverlay
					video={ref}
					assets={subtitleAssets}
					fonts={source?.fonts}
				/>
			)}
		</VideoPlayer.Container>
	);
};
