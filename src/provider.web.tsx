import { createPlayer, selectSource } from "@videojs/react";
import { videoFeatures } from "@videojs/react/video";
import {
	createContext,
	type ReactNode,
	useContext,
	useEffect,
	useRef,
} from "react";
import { WebOmniPlayer } from "./player.web";
import type { OmniPlayer } from "./types/player";
import type { Source } from "./types/source";
import type { SubtitleAssets } from "./types/subtitles";
import { useLazyRef } from "./utils/lazy-ref";

export const VideoPlayer = createPlayer({ features: videoFeatures });

const PlayerCtx = createContext<OmniPlayer>(null!);
const SourceCtx = createContext<Source | null>(null);
const SubtitleAssetsCtx = createContext<SubtitleAssets | undefined>(undefined);

export const OmniProvider = ({
	children,
	source,
	showNotification = false,
	subtitleAssets,
}: {
	source: Source;
	children: ReactNode;
	showNotification?: boolean;
	/** Override URLs for the web ASS/PGS subtitle renderer assets. */
	subtitleAssets?: SubtitleAssets;
}) => {
	return (
		<VideoPlayer.Provider>
			<SubtitleAssetsCtx.Provider value={subtitleAssets}>
				<PlayerInitializer source={source} showNotification={showNotification}>
					{children}
				</PlayerInitializer>
			</SubtitleAssetsCtx.Provider>
		</VideoPlayer.Provider>
	);
};

const PlayerInitializer = ({
	children,
	source,
	showNotification,
}: {
	children: ReactNode;
	source: Source;
	showNotification: boolean;
}) => {
	const store = VideoPlayer.usePlayer();
	const player = useLazyRef(() => new WebOmniPlayer(store));

	useEffect(() => {
		player.source = source;
	}, [source]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	// Apply `startTime` once the new source is ready to play. Seeking before the
	// media can play is a no-op, so we wait for `canPlay`. We also wait for the
	// media to reload (`canPlay` going false) after a source change so we don't
	// seek the previous media on a stale `canPlay`.
	const canPlay =
		VideoPlayer.usePlayer((state) => selectSource(state)?.canPlay) ?? false;
	const seekRef = useRef({
		uri: undefined as string | undefined,
		done: false,
		reloaded: false,
	});
	useEffect(() => {
		const uri = source.src[0]?.uri;
		if (seekRef.current.uri !== uri) {
			seekRef.current = { uri, done: false, reloaded: false };
		}
	}, [source]);
	useEffect(() => {
		const state = seekRef.current;
		if (!canPlay) {
			state.reloaded = true;
			return;
		}
		if (!state.reloaded || state.done) return;
		state.done = true;
		if (source.startTime && source.startTime > 0) {
			store.seek(source.startTime).catch(() => {});
		}
	}, [canPlay, source, store]);

	return (
		<PlayerCtx.Provider value={player}>
			<SourceCtx.Provider value={source}>{children}</SourceCtx.Provider>
		</PlayerCtx.Provider>
	);
};

export const usePlayer = () => useContext(PlayerCtx);
export const useSource = () => useContext(SourceCtx);
export const useSubtitleAssets = () => useContext(SubtitleAssetsCtx);
