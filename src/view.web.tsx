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
			import("jassub").then(({ default: JASSUB }) => {
				const instance = new JASSUB({
					video: el,
					subUrl: subtitle.link,
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
					...(pgs?.workerUrl && { workerUrl: pgs.workerUrl }),
				});
				attach({ destroy: () => instance.dispose() });
			});
		}

		return () => {
			cancelled = true;
			renderer?.destroy();
		};
	}, [video, subtitle]);

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
