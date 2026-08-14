import { createPlayer } from "@videojs/react";
import { GoogleCast } from "@videojs/react/media/google-cast";
import { videoFeatures } from "@videojs/react/video";
import { createContext, type ReactNode, useContext, useEffect } from "react";
import { usePlayerState } from "./events";
import { WebOmniPlayer } from "./player.web";
import type { OmniPlayer, PlayerBackend } from "./types/player";
import type { CastOptions, Source } from "./types/source";
import { useLazyRef } from "./utils/lazy-ref";

export const VideoPlayer = createPlayer({ features: videoFeatures });

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	cast,
	// Web always uses video.js; accepted for API parity with native.
	backend: _backend,
	showNotification = false,
}: {
	source?: Source;
	cast?: CastOptions;
	backend?: PlayerBackend;
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
	source?: Source;
	cast?: CastOptions;
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

	return (
		<PlayerCtx.Provider value={player}>
			<CastBridge cast={cast} />
			{children}
		</PlayerCtx.Provider>
	);
};

const CastBridge = ({ cast }: { cast?: CastOptions | null }) => {
	const source = usePlayerState("source");
	return (
		<GoogleCast
			receiver={cast?.receiverApplicationId}
			src={source?.castId}
			customData={source?.castData}
		/>
	);
};

export const usePlayer = () => useContext(PlayerCtx);
