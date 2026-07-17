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
import type { CastOptions, Source } from "./types/source";
import { useLazyRef } from "./utils/lazy-ref";

export const VideoPlayer = createPlayer({ features: videoFeatures });

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	cast,
	showNotification = false,
}: {
	source: Source;
	cast?: CastOptions;
	children: ReactNode;
	showNotification?: boolean;
}) => {
	return (
		<VideoPlayer.Provider>
			<PlayerInitializer
				source={source}
				cast={cast}
				showNotification={showNotification}
			>
				{children}
			</PlayerInitializer>
		</VideoPlayer.Provider>
	);
};

const PlayerInitializer = ({
	children,
	source,
	cast,
	showNotification,
}: {
	children: ReactNode;
	source: Source;
	cast?: CastOptions;
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
			if (source.startTime) store.seek(source.startTime);
		}
	}, [source, store]);

	useEffect(() => {
		player.castOptions = cast ?? null;
	}, [cast]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => useContext(PlayerCtx);
