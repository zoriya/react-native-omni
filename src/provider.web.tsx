import { createPlayer } from "@videojs/react";
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
import { useLazyRef } from "./utils/lazy-ref";

export const VideoPlayer = createPlayer({ features: videoFeatures });

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	showNotification = false,
}: {
	source: Source;
	children: ReactNode;
	showNotification?: boolean;
}) => {
	return (
		<VideoPlayer.Provider>
			<PlayerInitializer source={source} showNotification={showNotification}>
				{children}
			</PlayerInitializer>
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
	const seekedForSrc = useRef<string | undefined>(undefined);

	useEffect(() => {
		player.source = source;
		const uri = source.src[0]?.uri;
		if (uri !== seekedForSrc.current) {
			seekedForSrc.current = uri;
			if (source.startTime) store.seek(source.startTime)
		}
	}, [source, store]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => useContext(PlayerCtx);
